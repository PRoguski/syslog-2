plugins {
    java
    application
}

group = "pl.example"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

val jacksonVersion = "2.17.2"
val kafkaVersion = "3.7.1"
val junitVersion = "5.10.3"
val slf4jVersion = "2.0.13"

dependencies {
    // Config: YAML -> records
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    // Kafka Streams
    implementation("org.apache.kafka:kafka-streams:$kafkaVersion")

    // CLI
    implementation("info.picocli:picocli:4.7.6")

    // Logging
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")

    // Tests
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.apache.kafka:kafka-streams-test-utils:$kafkaVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("pl.example.syslogparser.App")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "pl.example.syslogparser.App")
    }
}
