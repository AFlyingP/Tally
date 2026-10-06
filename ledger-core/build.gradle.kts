val pitest: Configuration by configurations.creating

dependencies {
    testImplementation(enforcedPlatform(libs.junit5.bom))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
    pitest(enforcedPlatform(libs.junit5.bom))
    pitest(libs.pitest.cli)
    pitest(libs.pitest.junit5)
    pitest(libs.junit.launcher)
}

val sets = the<SourceSetContainer>()

tasks.register<JavaExec>("pitest") {
    description = "Runs mutation tests on tally.ledger.core."
    group = "verification"
    dependsOn("testClasses")
    mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
    classpath = pitest
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--reportDir", layout.buildDirectory.dir("reports/pitest").get().asFile.path,
            "--targetClasses", "tally.ledger.core.*",
            "--targetTests", "tally.ledger.core.*Test",
            "--sourceDirs", file("src/main/java").path,
            "--mutableCodePaths", sets["main"].output.classesDirs.files.joinToString(","),
            "--classPath", (sets["test"].runtimeClasspath.files).joinToString(","),
            "--outputFormats", "XML,HTML",
            "--timestampedReports", "false",
            "--threads", "4",
            "--mutationThreshold", (findProperty("pitThreshold") ?: "75").toString())
    })
}
