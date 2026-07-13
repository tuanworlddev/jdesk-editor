plugins {
    id("jdesk-editor.java-conventions")
    application
}

dependencies {
    implementation(libs.jackson.databind)
    testImplementation(libs.jqwik)
}

application {
    mainClass = "dev.jdesk.editor.evidence.Cli"
    mainModule = "dev.jdesk.editor.evidence"
}
