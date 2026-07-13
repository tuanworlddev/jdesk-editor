plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "-Xlint:all,-processing,-serial,-requires-automatic,-missing-explicit-ctor"))
}

// Main sources may be JPMS modules, but tests run on the classpath: JUnit/jqwik reflection
// into test classes fights the module system otherwise. Test code only touches exported API.
tasks.named<JavaCompile>("compileTestJava") {
    modularity.inferModulePath = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        excludeTags("integration", "e2e", "live")
    }
    modularity.inferModulePath = false
    systemProperty("junit.jupiter.execution.timeout.default", "120 s")
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showCauses = true
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs integration-tagged tests against real processes."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    systemProperty("junit.jupiter.execution.timeout.default", "300 s")
    shouldRunAfter(tasks.named("test"))
    testLogging {
        events("failed", "skipped", "passed")
        showExceptions = true
        showCauses = true
    }
}

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testImplementation"("org.assertj:assertj-core:3.27.3")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
