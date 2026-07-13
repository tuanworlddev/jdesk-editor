package dev.jdesk.editor.evidence;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Minimal client for the JDesk automation endpoint (test builds only). Used by the gate app
 * and the E2E runner to collect console dumps, real PNG snapshots, and in-page evaluation
 * results as evidence. The bearer token is registered with the run's redactor by the caller.
 */
public final class AutomationClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final URI base;
    private final String token;

    public AutomationClient(int port, String token) {
        this.base = URI.create("http://127.0.0.1:" + port);
        this.token = token;
    }

    /** Reads the owner-only descriptor JSON ({pid, port, token}) written by the app. */
    public static AutomationClient fromDescriptor(Path descriptorFile) throws IOException {
        JsonNode descriptor = JsonIo.mapper().readTree(Files.readString(descriptorFile));
        return new AutomationClient(descriptor.path("port").asInt(), descriptor.path("token").asText());
    }

    public JsonNode windows() throws IOException, InterruptedException {
        return JsonIo.mapper().readTree(send(get("/windows")).body());
    }

    public JsonNode evaluate(String window, String script) throws IOException, InterruptedException {
        var payload = JsonIo.object();
        payload.put("window", window);
        payload.put("script", script);
        HttpRequest request = authed("/evaluate")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonIo.line(payload)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        expect2xx(response, "/evaluate");
        return JsonIo.mapper().readTree(response.body());
    }

    public String console(String window) throws IOException, InterruptedException {
        return send(get("/console?window=" + encode(window))).body();
    }

    /** Fetches a snapshot PNG, validating it structurally before returning. */
    public byte[] snapshot(String window) throws IOException, InterruptedException {
        HttpRequest request = authed("/snapshot?window=" + encode(window)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        expect2xx(response, "/snapshot");
        byte[] png = response.body();
        if (!PngValidator.isRealPng(png)) {
            throw new IOException("Snapshot for window '" + window + "' is not a structurally valid PNG ("
                    + png.length + " bytes)");
        }
        return png;
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        expect2xx(response, request.uri().getPath());
        return response;
    }

    private HttpRequest get(String pathAndQuery) {
        return authed(pathAndQuery).GET().build();
    }

    private HttpRequest.Builder authed(String pathAndQuery) {
        return HttpRequest.newBuilder(base.resolve(pathAndQuery))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30));
    }

    private static void expect2xx(HttpResponse<?> response, String endpoint) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Automation " + endpoint + " returned HTTP " + response.statusCode());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
