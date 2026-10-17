dependencies {
    testImplementation(enforcedPlatform(libs.junit5.bom))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(project(":platform"))
    testImplementation(project(":ledger-core"))
    testImplementation(project(":ledger"))
    testImplementation(project(":extbank"))
    testImplementation(testFixtures(project(":platform")))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testImplementation(libs.kafka.clients)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.toxiproxy)
    testRuntimeOnly(libs.postgresql)
    testImplementation(libs.junit.launcher)
}

tasks.processTestResources {
    val tries = if ((findProperty("proof.size") ?: "ci") == "eval") "500" else "30"
    inputs.property("jqwikTries", tries)
    filesMatching("junit-platform.properties") { expand("jqwikTries" to tries) }
}

val proofProps = listOf("proof.size", "proof.seed", "proof.strategy", "proof.scenario", "proof.base")

fun Test.passProofProps() {
    proofProps.forEach { key -> findProperty(key)?.let { systemProperty(key, it.toString()) } }
    systemProperty("proof.root", rootDir.path)
    outputs.upToDateWhen { false }
}

val testSet = the<SourceSetContainer>()["test"]
val evalExitCodeFile = rootDir.resolve("build/eval/exit_code")

tasks.register<Test>("proofTest") {
    description = "Runs the proof suites at the selected size."
    group = "verification"
    testClassesDirs = testSet.output.classesDirs
    classpath = testSet.runtimeClasspath
    useJUnitPlatform { includeTags("property", "concurrency", "fault", "seeded", "load") }
    maxHeapSize = "2g"
    passProofProps()
}

tasks.register<Test>("e2eTest") {
    description = "Runs end-to-end tests against the running stack."
    group = "verification"
    testClassesDirs = testSet.output.classesDirs
    classpath = testSet.runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    passProofProps()
}

tasks.register<JavaExec>("eval") {
    description = "Runs the evaluation and writes build/eval/results.json."
    group = "verification"
    classpath = testSet.runtimeClasspath
    mainClass = "tally.proof.eval.EvalMain"
    workingDir = rootDir
    maxHeapSize = "2g"
    args = findProperty("cmdArgs")?.toString()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    systemProperty("proof.size", "eval")
    systemProperty("proof.root", rootDir.path)
    systemProperty("proof.seed", findProperty("proof.seed") ?: "20260101")
    dependsOn("testClasses")
    isIgnoreExitValue = true
    doLast {
        evalExitCodeFile.parentFile.mkdirs()
        evalExitCodeFile.writeText("${executionResult.get().exitValue}\n")
    }
}
