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
val evalClasspathDir = layout.buildDirectory.dir("eval-classpath")
val runtimeClasspathEntries = testSet.runtimeClasspath.files.toList()
val evalClasspathFiles = runtimeClasspathEntries.mapIndexed { index, entry ->
    val entryDir = evalClasspathDir.get().dir(index.toString()).asFile
    if (entry.isDirectory) entryDir else entryDir.resolve(entry.name)
}

val evalClasspath = tasks.register<Sync>("evalClasspath") {
    inputs.files(testSet.runtimeClasspath)
    runtimeClasspathEntries.forEachIndexed { index, entry ->
        from(entry) { into(index.toString()) }
    }
    into(evalClasspathDir)
}

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
    classpath = files(evalClasspathFiles)
    mainClass = "tally.proof.eval.EvalMain"
    workingDir = rootDir
    maxHeapSize = "2g"
    args = findProperty("cmdArgs")?.toString()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    systemProperty("proof.size", "eval")
    systemProperty("proof.root", rootDir.path)
    systemProperty("proof.seed", findProperty("proof.seed") ?: "20260101")
    dependsOn("testClasses", evalClasspath)
    isIgnoreExitValue = true
    doLast {
        evalExitCodeFile.parentFile.mkdirs()
        evalExitCodeFile.writeText("${executionResult.get().exitValue}\n")
    }
}

tasks.register<JavaExec>("demo") {
    description = "Runs the 12-step demo against the running stack."
    group = "verification"
    classpath = testSet.runtimeClasspath
    mainClass = "tally.proof.demo.Demo"
    workingDir = rootDir
    maxHeapSize = "2g"
    args = findProperty("cmdArgs")?.toString()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    systemProperty("proof.root", rootDir.path)
    dependsOn("testClasses")
}

tasks.register<JavaExec>("render") {
    description = "Renders evaluation results into the documentation."
    group = "verification"
    classpath = testSet.runtimeClasspath
    mainClass = "tally.proof.eval.ResultsRenderer"
    workingDir = rootDir
    maxHeapSize = "2g"
    args = findProperty("cmdArgs")?.toString()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    systemProperty("proof.root", rootDir.path)
    dependsOn("testClasses")
}
