plugins {
    `java-library`
    // JsonParser, which the cli tests read --json output with; the product never parses JSON.
    `java-test-fixtures`
    jacoco
}

group = "dev.jfrq"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // The core has no runtime dependencies: it reads recordings through jdk.jfr.consumer.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    // The JFR-backed tests start in-process recordings; give them a predictable heap.
    maxHeapSize = "1g"
    // HashTablesTest replays a failing run from its seed: ./gradlew :core:test -Djfrq.test.seed=<seed>
    providers.systemProperty("jfrq.test.seed").orNull?.let { systemProperty("jfrq.test.seed", it) }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
