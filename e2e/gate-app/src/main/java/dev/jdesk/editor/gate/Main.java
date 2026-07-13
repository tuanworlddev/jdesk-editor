package dev.jdesk.editor.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.LifecycleListener;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.api.WindowId;
import dev.jdesk.webview.spi.PlatformProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase-0 gate application. Serves the Monaco gate (or the semantic micro-proof) from the
 * production {@code jdesk://app} custom scheme via the public {@link JDeskApplication} builder,
 * drives the in-page probes through the real automation endpoint, and writes a machine-checkable
 * result set. With no platform adapter on the path the app fails loudly — never a fake provider.
 *
 * <p>Output directory is {@code $JDESK_EDITOR_RUN_DIR} (set by {@code evidence-cli app-run}) or
 * {@code -Djdesk.editor.gate.outDir}. Writes {@code gate-results.json}, {@code console.json},
 * {@code app-info.json}, and {@code screenshots/gate.png}. Exits 0 iff every gate item passed.
 */
public final class Main {

    private static final WindowId MAIN = new WindowId("main");
    private static final ObjectMapper JSON = new ObjectMapper();

    private Main() {}

    public static void main(String[] args) throws Exception {
        boolean semantic = argValue(args, "--gate", "monaco").equals("semantic");
        String reportGlobal = semantic ? "window.__semanticReport" : "window.__gateReport";
        String page = semantic ? "semantic.html" : "index.html";
        String testPrefix = semantic ? "S" : "GATE-";

        Path outDir = resolveOutDir();
        Files.createDirectories(outDir.resolve("screenshots"));

        // Serve the built frontend from this module's web/ resources over jdesk://app — the exact
        // mechanism a packaged app uses. Automation + console forwarding must be on before the
        // window loads so the initial page load is captured.
        System.setProperty("jdesk.assets.module", "dev.jdesk.editor.gate");
        Path automationDir = Files.createTempDirectory("jdesk-gate-automation");
        System.setProperty("jdesk.automation", "true");
        System.setProperty("jdesk.automation.dir", automationDir.toString());
        System.setProperty("jdesk.console.forward", "true");
        // Monaco injects dynamic <style> nodes → style-src needs 'unsafe-inline'. script-src stays
        // 'self' (no unsafe-eval, ever). worker-src 'self' blob: gives blob-fallback headroom;
        // same-origin ESM workers ride script-src 'self'. Release validator permits this.
        System.setProperty("jdesk.security.acknowledgeUnsafeCsp", "true");
        String csp = "default-src 'self'; script-src 'self'; worker-src 'self' blob:; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
                + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";

        String backend = providerTypeName();

        AtomicReference<ApplicationHandle> handleRef = new AtomicReference<>();
        AtomicBoolean allPassed = new AtomicBoolean(false);

        LifecycleListener lifecycle = new LifecycleListener() {
            @Override
            public void onReady(ApplicationHandle application) {
                handleRef.set(application);
                Thread orchestrator = new Thread(() -> {
                    try {
                        allPassed.set(orchestrate(automationDir, outDir, reportGlobal, backend));
                    } catch (Exception e) {
                        writeFailure(outDir, testPrefix, "orchestrator crashed: " + e);
                    } finally {
                        application.window(MAIN).ifPresent(w -> w.close());
                    }
                }, "gate-orchestrator");
                orchestrator.setDaemon(true);
                orchestrator.start();
            }
        };

        int exit = JDeskApplication.builder()
                .id("dev.jdesk.editor.gate")
                .window(WindowConfig.builder()
                        .id(MAIN.value())
                        .title("JDesk Editor — Phase 0 Gate")
                        .size(1200, 820)
                        .entry("jdesk://app/" + page)
                        .build())
                .contentSecurityPolicy(csp)
                .lifecycle(lifecycle)
                .run(args);

        System.exit(allPassed.get() ? 0 : Math.max(exit, 1));
    }

    private static boolean orchestrate(Path automationDir, Path outDir, String reportGlobal,
            String backend) throws Exception {
        Descriptor descriptor = awaitDescriptor(automationDir, Duration.ofSeconds(30));
        HttpClient http = HttpClient.newHttpClient();

        JsonNode report = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode value = evaluate(http, descriptor, reportGlobal);
            if (value != null && value.path("done").asBoolean(false)) {
                report = value;
                break;
            }
            Thread.sleep(500);
        }

        String consoleBody = get(http, descriptor, "/console?window=main");
        Files.writeString(outDir.resolve("console.json"), consoleBody, StandardCharsets.UTF_8);
        // Independent Java-side console audit: the raw console may contain a benign Monaco
        // "Canceled" rejection (the diff editor superseding a worker computation). Any error line
        // that is NOT that benign cancellation is a genuine failure and fails the gate.
        boolean consoleClean = consoleHasNoGenuineErrors(consoleBody);

        boolean snapshotOk = false;
        try {
            byte[] png = getBytes(http, descriptor, "/snapshot?window=main");
            Files.write(outDir.resolve("screenshots/gate.png"), png);
            snapshotOk = png.length > 1024;
        } catch (Exception e) {
            Files.writeString(outDir.resolve("screenshots/gate.png.error"), String.valueOf(e));
        }

        ObjectNode appInfo = JSON.createObjectNode();
        appInfo.put("backend", backend);
        appInfo.put("pid", ProcessHandle.current().pid());
        appInfo.put("snapshotOk", snapshotOk);
        JSON.writerWithDefaultPrettyPrinter().writeValue(outDir.resolve("app-info.json").toFile(), appInfo);

        ObjectNode results = JSON.createObjectNode();
        ArrayNode testsOut = results.putArray("tests");
        if (report == null) {
            writeFailure(outDir, reportGlobal.contains("semantic") ? "S" : "GATE-",
                    "page report never reached done=true");
            return false;
        }
        boolean allPassed = true;
        for (JsonNode test : report.path("tests")) {
            String outcome = test.path("outcome").asText();
            ObjectNode node = testsOut.addObject();
            node.put("id", test.path("id").asText());
            node.put("outcome", outcome);
            node.put("detail", test.path("detail").asText(""));
            ArrayNode evidence = node.putArray("evidence");
            evidence.add("gate-results.json");
            evidence.add("console.json");
            if (snapshotOk) {
                evidence.add("screenshots/gate.png");
            }
            if (!"PASS".equals(outcome)) {
                allPassed = false;
            }
        }

        // S3-JAVA: independent cross-check that the frontend's @noble/hashes SHA-256 equals Java's
        // MessageDigest SHA-256 over identical bytes — the equivalence the whole edit pipeline rests
        // on (macOS has no crypto.subtle, so the frontend uses a pure-JS hash we must trust).
        if (reportGlobal.contains("semantic")) {
            JsonNode content = evaluate(http, descriptor, "window.__semantic.content()");
            JsonNode jsHashNode = evaluate(http, descriptor, "window.__semantic.contentHash()");
            String jsContent = content != null && content.isTextual() ? content.asText() : null;
            String jsHash = jsHashNode != null && jsHashNode.isTextual() ? jsHashNode.asText() : null;
            String javaHash = jsContent == null ? null : sha256Hex(jsContent);
            boolean match = javaHash != null && javaHash.equals(jsHash);
            ObjectNode node = testsOut.addObject();
            node.put("id", "S3-JAVA");
            node.put("outcome", match ? "PASS" : "FAIL");
            node.put("detail", "java MessageDigest SHA-256 == frontend @noble/hashes SHA-256: " + match
                    + " (" + (javaHash == null ? "null" : javaHash.substring(0, 12)) + "…)");
            node.putArray("evidence").add("gate-results.json");
            if (!match) {
                allPassed = false;
            }
        }
        results.put("consoleCleanJavaSide", consoleClean);
        results.set("appInfo", appInfo);
        JSON.writerWithDefaultPrettyPrinter().writeValue(outDir.resolve("gate-results.json").toFile(), results);
        return allPassed && snapshotOk && consoleClean;
    }

    /**
     * True when the forwarded console contains no genuine error, treating a single class of
     * Monaco cancellation rejection as benign (see the page-side isMonacoCancellation).
     */
    private static boolean consoleHasNoGenuineErrors(String consoleBody) {
        try {
            JsonNode lines = JSON.readTree(consoleBody).path("lines");
            for (JsonNode line : lines) {
                if (!"error".equals(line.path("level").asText())) {
                    continue;
                }
                String message = line.path("message").asText("");
                boolean benignCancellation = message.contains("cancel@")
                        && message.contains(".worker-") && message.contains("dispose@");
                if (!benignCancellation) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- automation HTTP helpers ----

    private record Descriptor(int port, String token) {}

    private static Descriptor awaitDescriptor(Path dir, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    Optional<Path> descriptor = files.filter(p -> p.toString().endsWith(".json")).findFirst();
                    if (descriptor.isPresent()) {
                        JsonNode node = JSON.readTree(Files.readString(descriptor.get()));
                        return new Descriptor(node.path("port").asInt(), node.path("token").asText());
                    }
                }
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Automation descriptor never appeared in " + dir);
    }

    private static JsonNode evaluate(HttpClient http, Descriptor d, String script) {
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("window", "main");
            body.put("script", script);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + d.port() + "/evaluate"))
                    .header("Authorization", "Bearer " + d.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonNode parsed = JSON.readTree(resp.body());
            JsonNode result = parsed.has("result") ? parsed.get("result") : parsed.get("value");
            if (result != null && result.isTextual()) {
                try { return JSON.readTree(result.asText()); } catch (Exception ignore) { return result; }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(HttpClient http, Descriptor d, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + path))
                .header("Authorization", "Bearer " + d.token()).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static byte[] getBytes(HttpClient http, Descriptor d, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + path))
                .header("Authorization", "Bearer " + d.token()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    // ---- misc ----

    private static String sha256Hex(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Provider service type name without instantiating it (native init happens once, in run()). */
    private static String providerTypeName() {
        return ServiceLoader.load(PlatformProvider.class).stream()
                .map(p -> p.type().getName())
                .findFirst()
                .orElse("unknown");
    }

    private static Path resolveOutDir() {
        String env = System.getenv("JDESK_EDITOR_RUN_DIR");
        String prop = System.getProperty("jdesk.editor.gate.outDir");
        String chosen = env != null ? env : (prop != null ? prop : "build/gate-out");
        return Path.of(chosen).toAbsolutePath();
    }

    private static String argValue(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static void writeFailure(Path outDir, String testPrefix, String reason) {
        try {
            ObjectNode results = JSON.createObjectNode();
            ArrayNode tests = results.putArray("tests");
            ObjectNode node = tests.addObject();
            node.put("id", testPrefix + "RUN");
            node.put("outcome", "FAIL");
            node.put("detail", reason);
            node.putArray("evidence").add("gate-results.json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(outDir.resolve("gate-results.json").toFile(), results);
        } catch (Exception ignore) {
            // best effort
        }
    }
}
