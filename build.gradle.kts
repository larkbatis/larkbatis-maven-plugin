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
    `maven-publish`
    signing
    // Builds a Maven plugin WITH Gradle: this plugin scans the compiled mojo
    // classes and generates the descriptor (META-INF/maven/plugin.xml).
    id("de.benediktritter.maven-plugin-development") version "0.4.3"
}

group = "io.github.lightbatis"
version = providers.gradleProperty("version").get()
description = "Maven plugin for LightBatis: wires mapper XML into the annotation processor"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
    withJavadocJar()
}

// --- the core version this plugin injects ---------------------------------
//
// It used to be a literal in CompilerConfigInjection.java with a comment
// promising the release process kept it in step. Generating it is what makes
// that promise true: a released plugin that injects
// `lightbatis-processor:0.1.0-SNAPSHOT` fails in the *consumer's* build, long
// after ours went green.

val coreVersion = providers.gradleProperty("lightbatisCoreVersion").get()

val generateCoreVersion = tasks.register("generateCoreVersion") {
    description = "Write the lightbatis core version this plugin injects into consumer builds"
    val outputDir = layout.buildDirectory.dir("generated/sources/coreversion/java/main")
    outputs.dir(outputDir)
    // Captured as plain data so the task action holds no project reference
    // (Gradle configuration cache).
    val version = coreVersion
    doLast {
        val dir = outputDir.get().asFile.resolve("io/github/lightbatis/maven")
        dir.mkdirs()
        dir.resolve("CoreVersion.java").writeText(
            """
            package io.github.lightbatis.maven;

            /** Generated from the lightbatisCoreVersion build property — do not edit. */
            final class CoreVersion {

                static final String VALUE = "$version";

                private CoreVersion() {
                }
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets["main"].java.srcDir(generateCoreVersion)

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    // No dependency on the processor: the plugin only injects its Maven
    // coordinates (annotationProcessorPaths) and a directory path (-A option).
    // Generation happens inside javac; nothing leaks anywhere.
    compileOnly("org.apache.maven:maven-core:3.9.9")
    compileOnly("org.apache.maven:maven-plugin-api:3.9.9")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:3.15.1")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.apache.maven:maven-core:3.9.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// --- publishing ------------------------------------------------------------
//
// The published POM must say `maven-plugin`, not `jar`: that packaging is how
// Maven knows to read META-INF/maven/plugin.xml out of the artifact, and
// maven-plugin-development only generates the descriptor — it does not touch
// publishing.
//
// The POM carries no dependencies, and that is correct rather than an
// oversight. Everything this plugin compiles against (maven-core,
// maven-plugin-api, the mojo annotations) is exported to every plugin realm by
// Maven core itself — `compileOnly` here is `provided` there.

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                packaging = "maven-plugin"
                name = project.name
                description = provider { project.description }
                url = "https://github.com/lightbatis/lightbatis-maven-plugin"
                inceptionYear = "2026"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "lightbatis"
                        name = "LightBatis contributors"
                        url = "https://github.com/lightbatis"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/lightbatis/lightbatis-maven-plugin.git"
                    developerConnection = "scm:git:ssh://git@github.com/lightbatis/lightbatis-maven-plugin.git"
                    url = "https://github.com/lightbatis/lightbatis-maven-plugin"
                }
                issueManagement {
                    system = "GitHub Issues"
                    url = "https://github.com/lightbatis/lightbatis-maven-plugin/issues"
                }
            }
        }
    }

    repositories {
        // The Central Portal takes a zipped bundle, not a deploy over the wire:
        // publish into a local Maven layout that
        // .github/scripts/publish-to-central.sh zips and uploads.
        maven {
            name = "centralBundle"
            url = uri(layout.buildDirectory.dir("central-bundle"))
        }
        maven {
            name = "centralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            credentials {
                username = providers.environmentVariable("CENTRAL_USERNAME").orNull
                password = providers.environmentVariable("CENTRAL_PASSWORD").orNull
            }
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
