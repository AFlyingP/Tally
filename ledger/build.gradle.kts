plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":ledger-core"))
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgresql)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.restclient)
    implementation(libs.springdoc.webmvc.api)
    runtimeOnly(libs.postgresql)
    testImplementation(testFixtures(project(":platform")))
    testImplementation(project(":extbank"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
}
