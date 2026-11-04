import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Conjunction's shared engines: natal chart astronomy, Chinese zodiac / BaZi, blood type,
 * compatibility, match filter, palm features and on-device prompt builders.
 *
 * Plain Kotlin/JVM (java.time only, no Android APIs) so the engines unit-test on the JVM and the
 * Android app simply depends on project(":core"). Java resources (ephemeris tables, Delta T,
 * fallback reading text) are packaged into the APK and read with Class.getResourceAsStream.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val testdataDir = rootProject.layout.projectDirectory.dir("../testdata").asFile

tasks.test {
    systemProperty("conjunction.testdata", testdataDir.absolutePath)
    inputs.dir(testdataDir).withPropertyName("testdata").optional()
    testLogging {
        events("failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * Regenerates testdata/golden_compat.json, golden_match_filter.json and golden_prompts.json from
 * THIS implementation (the reference the Swift and TypeScript ports must reproduce).
 * Run: ./gradlew :core:writeGoldens   then review the diff before committing.
 */
tasks.register<Test>("writeGoldens") {
    description = "Writes the compatibility / match-filter / prompt golden vectors from the Kotlin engines."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("conjunction.testdata", testdataDir.absolutePath)
    systemProperty("conjunction.writeGoldens", "true")
    filter { includeTestsMatching("com.metavirtuoso.conjunction.core.GoldenWriter") }
    outputs.upToDateWhen { false }
}
