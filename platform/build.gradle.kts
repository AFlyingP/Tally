plugins {
    `java-test-fixtures`
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.spring.boot.starter.webmvc)
    api(libs.spring.boot.starter.validation)
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.boot.starter.resource.server)
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.spring.boot.starter.resource.server)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testRuntimeOnly(libs.junit.launcher)
}
