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
    testRuntimeOnly(libs.junit.launcher)
}

val proofProps = listOf("proof.size", "proof.seed", "proof.strategy", "proof.scenario", "proof.base")

fun Test.passProofProps() {
    proofProps.forEach { key -> findProperty(key)?.let { systemProperty(key, it.toString()) } }
    systemProperty("proof.root", rootDir.path)
    outputs.upToDateWhen { false }
}

val testSet = the<SourceSetContainer>()["test"]

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
