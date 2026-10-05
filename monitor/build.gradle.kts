plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":platform"))
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgresql)
    implementation(libs.spring.boot.starter.kafka)
    runtimeOnly(libs.postgresql)
    testImplementation(testFixtures(project(":platform")))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
}
