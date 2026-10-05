import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spotless) apply false
}

allprojects {
    group = "tally"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "checkstyle")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf("-Xlint:all,-processing,-serial,-this-escape", "-Werror", "-parameters"))
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.36.1")
            removeUnusedImports()
            endWithNewline()
        }
    }

    extensions.configure<CheckstyleExtension> {
        toolVersion = "14.3.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        maxWarnings = 0
    }

    extensions.configure<JacocoPluginExtension> { toolVersion = "0.8.15" }

    tasks.withType<Test>().configureEach {
        jvmArgs("-Duser.timezone=UTC")
        maxHeapSize = "1g"
        failOnNoDiscoveredTests = false
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }

    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("integration", "property", "concurrency", "fault", "seeded", "load", "e2e")
        }
    }

    val testSourceSet = the<SourceSetContainer>()["test"]

    val integrationTest = tasks.register<Test>("integrationTest") {
        description = "Runs tests tagged integration."
        group = "verification"
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { includeTags("integration") }
        shouldRunAfter("test")
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        executionData(fileTree(layout.buildDirectory).include("jacoco/*.exec"))
        reports { xml.required = true }
    }

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        mustRunAfter("test", integrationTest)
        executionData(fileTree(layout.buildDirectory).include("jacoco/*.exec"))
        violationRules {
            rule {
                element = "PACKAGE"
                excludes = listOf("tally.ledger.failpoint")
                limit {
                    counter = "LINE"
                    minimum = (if (project.name == "ledger-core") "0.90" else "0.70").toBigDecimal()
                }
            }
        }
    }
}
