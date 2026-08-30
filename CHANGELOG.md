# Changelog

Notable changes to `lightbatis-maven-plugin`. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The release workflow reads the section for the version being tagged out of this
file and uses it verbatim as the GitHub Release body, so a version with no
section here does not get released.

## [Unreleased]

## [0.1.0] - 2026-08-30

First public release.

```xml
<plugin>
  <groupId>io.github.lightbatis</groupId>
  <artifactId>lightbatis-maven-plugin</artifactId>
  <version>0.1.0</version>
  <extensions>true</extensions> <!-- required -->
</plugin>
```

### Added

- **A build extension, not a mojo.** Maven finalizes every mojo's configuration
  before the first mojo of a project runs, so a mojo in an early phase cannot add
  compiler arguments to the `compile` execution. The plugin therefore runs as an
  `AbstractMavenLifecycleParticipant`, before execution plans are calculated, and
  for each project declaring it:
  - injects `-Alightbatis.mapperDir=<dir>` into `maven-compiler-plugin`'s
    `<compilerArgs>` (default `src/main/resources`; skipped where the option is
    already passed by hand),
  - appends `io.github.lightbatis:lightbatis-processor` to
    `<annotationProcessorPaths>`, creating the element when absent,
  - binds `lightbatis:refresh` at `generate-sources`,
  - sets the `lightbatis.mapperDir` project property.

  Both injections target **every `compile`-bound execution** of the compiler
  plugin, not just its plugin-level `<configuration>` — Maven copies plugin-level
  configuration into those executions while the project is being read, before any
  extension runs.
- **`lightbatis:refresh`** touches a mapper interface source whose mapper XML
  changed since the last build, so an XML-only edit still regenerates code —
  `maven-compiler-plugin` recompiles on stale `.java` files only. Change
  detection is by content hash (recorded in
  `target/lightbatis/mapper-xml.properties`), not timestamps, so neither a
  future-dated file nor a coarse filesystem clock can mislead it. IO problems
  become warnings, never a failed build.
- **`lightbatis:check`** diagnoses the silent failure mode: without
  `<extensions>true</extensions>` none of the above runs, and nothing says so.
- **Configuration:** `<mapperDir>` (default `src/main/resources`) and
  `<addProcessorPath>` (default `true`).
- **The injected core version is generated from the build**, not typed into the
  source. `lightbatisCoreVersion` in `gradle.properties` decides which
  `lightbatis-processor` consumers resolve, and the release workflow refuses to
  publish while it still reads `-SNAPSHOT`.

All code generation stays inside javac. The plugin generates nothing itself and
adds nothing to the application's runtime classpath.

### Three things worth knowing

- **Other annotation processors.** If `<annotationProcessorPaths>` did not exist
  before, creating it switches javac from classpath processor discovery to
  explicit paths only. Add your other processors (Lombok, for one) to it, or set
  `<addProcessorPath>false</addProcessorPath>` and manage the paths yourself.
- **`addProcessorPath=false` on JDK 23+.** javac no longer discovers processors
  from the compile classpath, and `-Alightbatis.mapperDir` does not count as
  asking for annotation processing. Add the processor to
  `<annotationProcessorPaths>` or set `<proc>full</proc>` yourself, or you get no
  generated mappers and no error.
- **Do not set `<useIncrementalCompilation>false</useIncrementalCompilation>`.**
  The processor is aggregating: it writes one `LightBatisMappers` registry
  listing every mapper in the compilation, and compiling only the stale sources
  would regenerate that registry from a partial view.

### Known limitations

- **Goal-prefix resolution (`mvn lightbatis:check`) needs configuration.** The
  group-level `maven-metadata.xml` that makes a bare prefix resolve is produced
  by Maven's own deploy plugin, and this artifact is built and published with
  Gradle. Either add `io.github.lightbatis` to `<pluginGroups>` in your
  `settings.xml`, or use the full coordinate
  `mvn io.github.lightbatis:lightbatis-maven-plugin:0.1.0:check`. Binding the
  goals in the POM — the normal case — is unaffected.
- **`compile` only** — test-scoped mappers are not wired.
- A `mapperDir` path containing a comma is not supported.
- Functional tests against a real `mvn` invocation are still deferred; the unit
  tests pin the model mutations against the shape Maven 3.9.x presents at
  extension time.

[Unreleased]: https://github.com/lightbatis/lightbatis-maven-plugin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/lightbatis/lightbatis-maven-plugin/releases/tag/v0.1.0
