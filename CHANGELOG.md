# Changelog

Notable changes to `larkbatis-maven-plugin`. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The release workflow reads the section for the version being tagged out of this
file and uses it verbatim as the GitHub Release body, so a version with no
section here does not get released.

## [0.1.2] - 2026-08-31

### Added

- **Expose 5 processor options directly in `<configuration>`**:
  `<mapUnderscoreToCamelCase>`, `<typeHandlers>`, `<registryPackage>`,
  `<springConfig>`, and `<springConfigPackage>` can now be configured directly
  instead of passing `-A` compiler arguments manually. The plugin will skip any
  unset options, letting the processor apply its own defaults.

- **Mapper XML can live in more than one directory**, through a new
  `<mapperDirs>` list:

  ```xml
  <configuration>
    <mapperDirs>
      <mapperDir>src/main/mappers</mapperDir>
      <mapperDir>src/main/legacy-mappers</mapperDir>
    </mapperDirs>
  </configuration>
  ```

  Every directory is scanned recursively, and all of them reach javac in one
  `-Alarkbatis.mapperDir` option — a repeated `-A` of the same name is the last
  one javac reads, not the union. `larkbatis:refresh` watches all of them, and
  its state file is now keyed by absolute path: two directories can hold the
  same relative path, and a shared key would let one file's hash answer for the
  other's.

  `<mapperDir>` still takes a single directory and is scanned first. The
  `src/main/resources` default now applies only when the build names neither.
  A directory reached through both settings is scanned once, because scanning it
  twice makes the processor report every namespace in it as declared by two
  files.

  `mvn larkbatis:check` prints every resolved directory and marks any that does
  not exist.

- **`<parameters>true</parameters>` is set on `maven-compiler-plugin`**,
  controlled by `<addParameters>` (default true). This was the one wiring step
  the first real migration still had to do by hand, and it is not a
  convenience: an incremental build re-runs an aggregating processor over
  *unchanged* mappers from their class files, where a parameter name survives
  only if that flag was on. Without it a clean build resolves `#{name}` and the
  next incremental build fails on `arg0`.

  The build's own opinion wins in every direction. An explicit `<parameters>`,
  or a manual `<compilerArgs><arg>-parameters</arg></compilerArgs>`, is left
  exactly as written — including `<parameters>false</parameters>`, which is
  honoured and then warned about, because it is the configuration under which
  `#{name}` resolves on a clean build and fails on the next incremental one.
  Written through the compiler plugin's own `<parameters>` element rather than
  as a `-parameters` arg, so it shows up where a reader of
  `mvn help:effective-pom` expects to find it.

## [0.1.0] - 2026-08-30

First public release.

```xml
<plugin>
  <groupId>io.github.larkbatis</groupId>
  <artifactId>larkbatis-maven-plugin</artifactId>
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
  - injects `-Alarkbatis.mapperDir=<dir>` into `maven-compiler-plugin`'s
    `<compilerArgs>` (default `src/main/resources`; skipped where the option is
    already passed by hand),
  - appends `io.github.larkbatis:larkbatis-processor` to
    `<annotationProcessorPaths>`, creating the element when absent,
  - binds `larkbatis:refresh` at `generate-sources`,
  - sets the `larkbatis.mapperDir` project property.

  Both injections target **every `compile`-bound execution** of the compiler
  plugin, not just its plugin-level `<configuration>` — Maven copies plugin-level
  configuration into those executions while the project is being read, before any
  extension runs.
- **`larkbatis:refresh`** touches a mapper interface source whose mapper XML
  changed since the last build, so an XML-only edit still regenerates code —
  `maven-compiler-plugin` recompiles on stale `.java` files only. Change
  detection is by content hash (recorded in
  `target/larkbatis/mapper-xml.properties`), not timestamps, so neither a
  future-dated file nor a coarse filesystem clock can mislead it. IO problems
  become warnings, never a failed build.
- **`larkbatis:check`** diagnoses the silent failure mode: without
  `<extensions>true</extensions>` none of the above runs, and nothing says so.
- **Configuration:** `<mapperDir>` (default `src/main/resources`) and
  `<addProcessorPath>` (default `true`).
- **The injected core version is generated from the build**, not typed into the
  source. `larkbatisCoreVersion` in `gradle.properties` decides which
  `larkbatis-processor` consumers resolve, and the release workflow refuses to
  publish while it still reads `-SNAPSHOT`.

All code generation stays inside javac. The plugin generates nothing itself and
adds nothing to the application's runtime classpath.

### Three things worth knowing

- **Other annotation processors.** If `<annotationProcessorPaths>` did not exist
  before, creating it switches javac from classpath processor discovery to
  explicit paths only. Add your other processors (Lombok, for one) to it, or set
  `<addProcessorPath>false</addProcessorPath>` and manage the paths yourself.
- **`addProcessorPath=false` on JDK 23+.** javac no longer discovers processors
  from the compile classpath, and `-Alarkbatis.mapperDir` does not count as
  asking for annotation processing. Add the processor to
  `<annotationProcessorPaths>` or set `<proc>full</proc>` yourself, or you get no
  generated mappers and no error.
- **Do not set `<useIncrementalCompilation>false</useIncrementalCompilation>`.**
  The processor is aggregating: it writes one `LarkBatisMappers` registry
  listing every mapper in the compilation, and compiling only the stale sources
  would regenerate that registry from a partial view.

### Known limitations

- **Goal-prefix resolution (`mvn larkbatis:check`) needs configuration.** The
  group-level `maven-metadata.xml` that makes a bare prefix resolve is produced
  by Maven's own deploy plugin, and this artifact is built and published with
  Gradle. Either add `io.github.larkbatis` to `<pluginGroups>` in your
  `settings.xml`, or use the full coordinate
  `mvn io.github.larkbatis:larkbatis-maven-plugin:0.1.0:check`. Binding the
  goals in the POM — the normal case — is unaffected.
- **`compile` only** — test-scoped mappers are not wired.
- A `mapperDir` path containing a comma is not supported.
- Functional tests against a real `mvn` invocation are still deferred; the unit
  tests pin the model mutations against the shape Maven 3.9.x presents at
  extension time.

[Unreleased]: https://github.com/larkbatis/larkbatis-maven-plugin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/larkbatis/larkbatis-maven-plugin/releases/tag/v0.1.0
