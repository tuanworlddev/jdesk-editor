plugins {
    id("jdesk-editor.java-conventions")
}

dependencies {
    api(project(":editor-api"))
    implementation(project(":editor-core"))
    implementation(libs.jackson.databind)
    testImplementation(libs.jackson.databind)
}

// Standalone MCP server over a workspace, for live agent integration tests.
tasks.register<JavaExec>("mcpServe") {
    group = "verification"
    description = "Runs the MCP server over a workspace (McpLauncher)."
    mainClass = "dev.jdesk.editor.mcp.McpLauncher"
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    args = providers.gradleProperty("mcpArgs").getOrElse("").split(" ").filter { it.isNotEmpty() }
}
