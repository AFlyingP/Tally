plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":platform"))
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgresql)
    implementation(libs.spring.boot.starter.authorization.server)
    runtimeOnly(libs.postgresql)
    testImplementation(testFixtures(project(":platform")))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
}
