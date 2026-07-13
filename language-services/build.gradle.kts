plugins {
    id("jdesk-editor.java-conventions")
}

// Classpath (non-modular) module: LSP4J and its Gson dependency are automatic modules, so keeping
// this off the module path avoids JPMS friction. Consumed by the app as an automatic module.
dependencies {
    api(project(":editor-api"))
    implementation(project(":editor-core"))
    implementation(libs.lsp4j)
}
