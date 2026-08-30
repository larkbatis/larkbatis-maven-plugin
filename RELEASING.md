# Releasing

The full runbook — secrets, signing key, the Central Portal flow, the order the
four repositories release in — lives in **`lightbatis/RELEASING.md`**. This file
covers only what is specific to this repository.

## `maven-plugin` packaging

The published POM says `<packaging>maven-plugin</packaging>`, which is how Maven
knows to read `META-INF/maven/plugin.xml` out of the artifact. The
`de.benediktritter.maven-plugin-development` plugin generates that descriptor but
does not touch publishing, so the packaging is set explicitly in the publication
block in `build.gradle.kts`.

The POM carries no dependencies, and that is correct rather than an oversight.
Everything this plugin compiles against — `maven-core`, `maven-plugin-api`, the
mojo annotations — is exported to every plugin realm by Maven core itself:
`compileOnly` here is `provided` there.

## Goal-prefix resolution is not published

`mvn lightbatis:check` resolves through a group-level `maven-metadata.xml` that
Maven's own deploy plugin writes at deploy time. This artifact is built and
published with Gradle, which does not produce that file, and a deployment bundle
is not the place to hand-write one.

Consumers therefore either add the plugin group to `settings.xml`:

```xml
<pluginGroups>
  <pluginGroup>io.github.lightbatis</pluginGroup>
</pluginGroups>
```

or use the full coordinate:

```bash
mvn io.github.lightbatis:lightbatis-maven-plugin:0.1.0:check
```

Goals bound in a POM — the normal case, and what `<extensions>true</extensions>`
sets up — are unaffected. `CHANGELOG.md` states this under known limitations.

## `lightbatisCoreVersion`

`gradle.properties` carries the version of `lightbatis-processor` that this
plugin injects into **consumer** builds. It is generated into `CoreVersion.java`
at build time rather than typed into the source, and the release workflow refuses
to run while it still reads `-SNAPSHOT`.

Release `lightbatis` first, then set:

```properties
version=0.1.0
lightbatisCoreVersion=0.1.0
```

## Rehearse first

```bash
gh workflow run release.yml -f version=0.1.0 -f dry-run=true
```

Builds, tests, signs and assembles the bundle — and uploads nothing.
