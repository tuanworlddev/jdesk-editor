// Phase-0 Monaco worker gate + semantic micro-proof app. Boots a real JDesk window with the
// production asset mechanism (jdesk://app served from the app jar), runs the gate probes in the
// page, reads results back through the automation endpoint, and writes an evidence run.
plugins {
    id("jdesk-editor.java-conventions")
    application
}

val jdeskVersion = providers.gradleProperty("jdeskVersion").getOrElse("0.1.2")

dependencies {
    implementation("dev.jdesk:jdesk-api:$jdeskVersion")
    implementation("dev.jdesk:jdesk-runtime:$jdeskVersion")
    implementation("dev.jdesk:jdesk-webview-spi:$jdeskVersion")
    implementation(libs.jackson.databind)
    // Automation endpoint is a TEST-only dependency (never in a production package). The gate is
    // a test app, so it declares it directly.
    runtimeOnly("dev.jdesk:jdesk-automation:$jdeskVersion")
    // Platform adapter selected per-OS at run time via -PjdeskPlatform; no adapter => loud failure.
    providers.gradleProperty("jdeskPlatform").orNull?.let {
        runtimeOnly("dev.jdesk:jdesk-platform-$it:$jdeskVersion")
    }
}

application {
    mainModule = "dev.jdesk.editor.gate"
    mainClass = "dev.jdesk.editor.gate.Main"
    // Baked into the installDist start script so the packaged image launches with native access
    // and, on macOS, the AppKit main-thread requirement.
    val platform = providers.gradleProperty("jdeskPlatform").orNull
    applicationDefaultJvmArgs = buildList {
        if (platform != null) {
            add("--enable-native-access=dev.jdesk.platform.$platform")
            add("--illegal-native-access=deny")
            if (platform == "macos") add("-XstartOnFirstThread")
        }
    }
}

// Build the Vite frontend into src/main/resources/web before packaging resources.
val frontendBuild = tasks.register<Exec>("frontendBuild") {
    workingDir = file("ui")
    inputs.dir("ui/src")
    inputs.files("ui/index.html", "ui/semantic.html", "ui/vite.config.ts", "ui/package.json")
    outputs.dir("src/main/resources/web")
    commandLine("npm", "run", "build")
}
tasks.named("processResources") { dependsOn(frontendBuild) }

tasks.named<JavaExec>("run") {
    dependsOn(frontendBuild)
    doNotTrackState("launches a desktop application")
    providers.gradleProperty("jdeskPlatform").orNull?.let {
        jvmArgs("--enable-native-access=dev.jdesk.platform.$it", "--illegal-native-access=deny")
        if (it == "macos") jvmArgs("-XstartOnFirstThread")
    }
    providers.gradleProperty("gateArgs").orNull?.let { args(it.split(" ")) }
}
