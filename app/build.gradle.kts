plugins {
    id("jdesk-editor.java-conventions")
    id("dev.jdesk.application")
    application
}

group = "dev.jdesk.editor"
version = "0.1.0"

val jdeskVersion = providers.gradleProperty("jdeskVersion").getOrElse("0.1.2")

dependencies {
    implementation("dev.jdesk:jdesk-api:$jdeskVersion")
    implementation("dev.jdesk:jdesk-runtime:$jdeskVersion")
    implementation(project(":editor-api"))
    implementation(project(":editor-core"))
    implementation(project(":agent-mcp"))
    compileOnly(libs.jackson.databind)
    // Compile-time @DesktopCommand registration + TypeScript binding generation (we are the
    // first real consumer of the typed-bindings path — see docs/JDESK_AUDIT.md gap 6).
    annotationProcessor("dev.jdesk:jdesk-codegen:$jdeskVersion")

    val platform = providers.gradleProperty("jdeskPlatform").orNull
    if (platform != null) {
        runtimeOnly("dev.jdesk:jdesk-platform-$platform:$jdeskVersion")
    }
    // Automation endpoint is TEST-only; a production package must never include it. It is added
    // here only when explicitly driving E2E: -PjdeskAutomation=true.
    if (providers.gradleProperty("jdeskAutomation").orNull == "true") {
        runtimeOnly("dev.jdesk:jdesk-automation:$jdeskVersion")
    }
}

application {
    mainModule = "dev.jdesk.editor.app"
    mainClass = "dev.jdesk.editor.app.Main"
}

jdesk {
    applicationId = "dev.jdesk.editor"
    mainClass = "dev.jdesk.editor.app.Main"
    frontend {
        directory = rootProject.layout.projectDirectory.dir("ui")
        devCommand = listOf("npm", "run", "dev")
        buildCommand = listOf("npm", "run", "build")
        devUrl = "http://127.0.0.1:5173"
        distDirectory = rootProject.layout.projectDirectory.dir("ui/dist")
    }
}

tasks.named<JavaExec>("run") {
    dependsOn("jdeskFrontendBuild")
    doNotTrackState("launches a desktop application")
    val platform = providers.gradleProperty("jdeskPlatform").orNull
    if (platform != null) {
        jvmArgs("--enable-native-access=dev.jdesk.platform.$platform", "--illegal-native-access=deny")
        if (platform == "macos") jvmArgs("-XstartOnFirstThread")
    }
    systemProperty("jdesk.assets.dir",
        rootProject.layout.projectDirectory.dir("ui/dist").asFile.absolutePath)
    if (providers.gradleProperty("jdeskAutomation").orNull == "true") {
        systemProperty("jdesk.automation", "true")
        systemProperty("jdesk.automation.dir",
            layout.buildDirectory.dir("automation").get().asFile.absolutePath)
        systemProperty("jdesk.console.forward", "true")
        systemProperty("jdesk.editor.mcp.dir",
            layout.buildDirectory.dir("mcp").get().asFile.absolutePath)
    }
    providers.gradleProperty("editorArgs").orNull?.let { args(it.split(" ")) }
}
