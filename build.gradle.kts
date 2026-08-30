buildscript {
    configurations.classpath {
        resolutionStrategy {
            // maven-plugin-development 0.4.3 pulls qdox 2.0.0, whose parser
            // predates Java records and fails on this repo's sources.
            force("com.thoughtworks.qdox:qdox:2.1.0")
        }
    }
}

plugins {
    `java-library`
    // Builds a Maven plugin WITH Gradle: this plugin scans the compiled mojo
    // classes and generates the descriptor (META-INF/maven/plugin.xml).
    id("de.benediktritter.maven-plugin-development") version "0.4.3"
}

group = "io.github.lightbatis"
version = "0.1.0-SNAPSHOT"
description = "Maven plugin for LightBatis: wires mapper XML into the annotation processor"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    // No dependency on the processor: the plugin only injects its Maven
    // coordinates (annotationProcessorPaths) and a directory path (-A option).
    // Generation happens inside javac; nothing leaks anywhere (§03).
    compileOnly("org.apache.maven:maven-core:3.9.9")
    compileOnly("org.apache.maven:maven-plugin-api:3.9.9")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:3.15.1")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.apache.maven:maven-core:3.9.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
