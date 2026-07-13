// Root build file. Module configuration lives in each subproject; shared conventions
// come from build-logic precompiled script plugins.

tasks.register("banned-version-markers") {
    group = "verification"
    description = "Fails if any committed build file pins a dependency as 'latest' (spec §5)."
    val buildFiles = layout.projectDirectory.asFileTree.matching {
        include("**/build.gradle.kts", "**/settings.gradle.kts", "gradle/libs.versions.toml", "**/package.json")
        exclude("**/build/**", "**/node_modules/**", "**/.gradle/**")
    }
    inputs.files(buildFiles)
    doLast {
        val dynamicVersion = Regex("""[0-9]\.\+|latest\.release|latest\.integration|"latest"|:latest["']""")
        val offenders = buildFiles.files.filter { file ->
            file.readLines().any { line ->
                !line.trimStart().startsWith("//") && !line.trimStart().startsWith("#") &&
                    dynamicVersion.containsMatchIn(line)
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("'latest'/dynamic versions found in: ${offenders.joinToString { it.path }}")
        }
    }
}
