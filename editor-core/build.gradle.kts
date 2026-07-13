plugins {
    id("jdesk-editor.java-conventions")
}

dependencies {
    api(project(":editor-api"))
    testImplementation(libs.jqwik)
}
