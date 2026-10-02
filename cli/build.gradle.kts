plugins {
    checkstyle
    application
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
    implementation(project(":core"))
    testImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "jfrq"
    mainClass = "dev.jfrq.cli.Main"
    // Start-up is most of the cost on a small recording. AppCDS keeps a dynamic archive of the
    // loaded classes next to the jars after the first run; later runs map it instead of
    // loading and verifying jdk.jfr and the tool again. Logging is off so an unwritable
    // install directory degrades silently to a normal start.
    applicationDefaultJvmArgs = listOf(
        "-XX:+AutoCreateSharedArchive",
        "-XX:SharedArchiveFile=APP_HOME_PLACEHOLDER/lib/jfrq.jsa",
        "-Xlog:cds*=off",
    )
}

tasks.startScripts {
    doLast {
        // The start scripts quote each JVM argument; APP_HOME is only known at run time.
        unixScript.writeText(unixScript.readText().replace("APP_HOME_PLACEHOLDER", "'\"\$APP_HOME\"'"))
        windowsScript.writeText(windowsScript.readText().replace("APP_HOME_PLACEHOLDER", "%APP_HOME%"))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
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
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.javadoc)
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// Javadoc is checked, not published: doclint over every member, private ones included, so a broken
// {@link}, reference or tag fails the build. A member without a comment is not an error (G-10.4).
tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        memberLevel = JavadocMemberLevel.PRIVATE
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Werror", true)
    }
}

// Unused imports, which -Xlint does not report: Checkstyle with that one rule, over every source set.
checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}
