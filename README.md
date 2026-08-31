# larkbatis-maven-plugin

Maven plugin for LarkBatis: wires mapper XML into the generator core
(`larkbatis-processor`) at build time — same rationale as the Gradle plugin:
the `Filer.getResource` spec does not guarantee access to files
under `src/main/resources`, so a build-tool plugin hands the processor a real
directory path.

## Usage

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.larkbatis</groupId>
            <artifactId>larkbatis-maven-plugin</artifactId>
            <version>0.1.0</version>
            <extensions>true</extensions> <!-- required, see below -->
        </plugin>
    </plugins>
</build>

<dependencies>
    <dependency>
        <groupId>io.github.larkbatis</groupId>
        <artifactId>larkbatis-runtime</artifactId>
        <version>0.1.0</version>
    </dependency>
    <dependency>
        <groupId>io.github.larkbatis</groupId>
        <artifactId>larkbatis-annotations</artifactId>
        <version>0.1.0</version>
    </dependency>
    <!-- larkbatis-processor lands on annotationProcessorPaths automatically -->
</dependencies>
```

## How it works — and why `<extensions>true</extensions>`

Maven finalizes every mojo's configuration before the first mojo of a project
runs, so a mojo in an early phase cannot add compiler arguments to the
`compile` execution. The plugin therefore works as a **build extension**
(`AbstractMavenLifecycleParticipant`), which runs before execution plans are
calculated and, for each project declaring the plugin:

- injects `-Alarkbatis.mapperDir=<dirs>` into `maven-compiler-plugin`'s
  `<compilerArgs>` (default: `src/main/resources`; only files with a
  `<mapper>` root element are read, so other XML in the same tree is ignored;
  skipped where that option is already passed manually),
- appends `io.github.larkbatis:larkbatis-processor` to
  `<annotationProcessorPaths>`, creating the element when absent,
- sets `<parameters>true</parameters>` where the build has no opinion of its
  own (see below),
- binds `larkbatis:refresh` at `generate-sources`. That goal touches a mapper
  interface source whose mapper XML content changed since the last build, so
  an XML-only edit still regenerates code — `maven-compiler-plugin` recompiles
  on stale `.java` files only. Change detection is by content hash (recorded
  in `target/larkbatis/mapper-xml.properties`), not timestamps, so neither a
  future-dated file nor a coarse filesystem clock can mislead it. The goal is
  best-effort: IO problems become warnings, never a failed build,
- sets the `larkbatis.mapperDir` project property, holding every resolved
  directory.

Both injections target **every `compile`-bound execution** of the compiler
plugin, not just its plugin-level `<configuration>`. Maven copies plugin-level
configuration into those executions while the project is being read — before
any extension runs — and the execution's own configuration is what the plan
uses, so a plugin-level edit made at extension time would never reach
`mvn compile`. The plugin-level node is injected as well, for direct
`mvn compiler:compile` invocations.

Without `<extensions>true</extensions>` none of this runs — silently. Run
`mvn larkbatis:check` (or bind the `check` goal) to diagnose that case.

Configuration (all optional):

```xml
<configuration>
    <mapperDir>src/main/mappers</mapperDir>      <!-- default: src/main/resources -->
    <addProcessorPath>false</addProcessorPath>   <!-- default: true -->
    <addParameters>false</addParameters>         <!-- default: true -->
</configuration>
```

All code generation stays inside javac; the plugin generates nothing itself
and adds nothing to the application's runtime classpath.

### Mapper XML in more than one directory

`<mapperDirs>` takes as many as the module has — rewritten mappers kept beside
the legacy ones, or generated mapper XML under `target/`:

```xml
<configuration>
    <mapperDirs>
        <mapperDir>src/main/mappers</mapperDir>
        <mapperDir>src/main/legacy-mappers</mapperDir>
    </mapperDirs>
</configuration>
```

Each is scanned recursively and all of them reach javac in a single
`-Alarkbatis.mapperDir` option; a second `-A` of the same name would be the
last one javac reads, not the union. `larkbatis:refresh` watches every
directory, so an XML-only edit in any of them still recompiles its mapper
interface.

`<mapperDir>` and `<mapperDirs>` can both be set — the singular one is scanned
first — and a directory named through both is scanned once. The
`src/main/resources` default applies only when the build names neither, so
listing mapper trees does not quietly add a resources directory nobody
mentioned. Relative paths resolve against the project basedir.

Two directories declaring the same mapper namespace is a compile error rather
than a last-one-wins merge: the two files disagree about one mapper and
nothing in the build can say which was meant.

The directories may sit anywhere, but every namespace found still has to name
a mapper interface compiled in *this* module — a file that matches none is
reported and ignored, so pointing at another module's mapper tree buys nothing.

`mvn larkbatis:check` prints every resolved directory and flags any that does
not exist — a mistyped one in a list generates nothing and would otherwise say
nothing.

### Why `<parameters>true</parameters>`

Parameter names have to reach the class files. An incremental build re-runs an
aggregating annotation processor over *unchanged* mappers from their **class
files**, and a parameter name survives there only when it was compiled with
`-parameters`. Without it a clean build resolves `#{name}` and the next
incremental build sees `arg0` — the same source, two outcomes, decided by
whether the file happened to be touched.

The build's own opinion always wins. An explicit `<parameters>` — `true` or
`false` — is left exactly as written, as is a manual
`<compilerArgs><arg>-parameters</arg></compilerArgs>`. A `false` is honoured
and **warned about**, because it is precisely the configuration under which
`#{name}` works until it does not:

```text
[WARNING] LarkBatis (my-service): maven-compiler-plugin has
<parameters>false</parameters>, and that has been left alone. ...
```

A build that genuinely cannot carry the flag sets `<addParameters>false</addParameters>`
and puts `@Param` on every mapper parameter, which needs no parameter names.

### Three things worth knowing

**Other annotation processors.** If `<annotationProcessorPaths>` did not exist
before, creating it switches javac from classpath processor discovery to
explicit paths only. Add your other processors (e.g. Lombok) to it, or set
`<addProcessorPath>false</addProcessorPath>` and manage the paths yourself.

**`addProcessorPath=false` on JDK 23+.** javac no longer discovers processors
from the compile classpath, and the `-Alarkbatis.mapperDir` option does not
count as asking for annotation processing either. If you opt out and put
`larkbatis-processor` on the classpath instead, add it to
`<annotationProcessorPaths>` or set `<proc>full</proc>` yourself — otherwise
you get no generated mappers and no error. (The plugin does not set `<proc>`:
`<annotationProcessorPaths>` already passes `-processorpath`, which keeps
processing enabled, and `-proc:full` is an invalid flag below JDK 17.0.9.)

**Do not set `<useIncrementalCompilation>false</useIncrementalCompilation>`.**
The processor is `aggregating`: it writes one `LarkBatisMappers` registry
listing every mapper in the compilation. maven-compiler-plugin's default
behavior recompiles all sources once any of them is stale, which is what makes
that whole. Compiling only the stale ones would regenerate the registry from a
partial view.

### Test-scoped mappers

Not supported yet: only the `compile` execution is wired, matching the Gradle
plugin (`compileJava`, not `compileTestJava`). Mapper interfaces belong in
`src/main/java`. Test sources use them normally — they are ordinary classes on
the test classpath.

### Multi-module builds

Everything is per-project: each module declaring the plugin gets its own
mapper directory, and a mapper XML must live in the same module as the mapper
interface its `namespace` names. A mapper XML pointing at an interface in
another module is ignored with a build warning from the processor.

A `mapperDir` path containing a comma is not supported — the processor treats
commas as separators between directories.

## Building this repo

Built **with Gradle** (`./gradlew build`, JDK 17 via toolchain); the Maven
plugin descriptor (`META-INF/maven/plugin.xml`) is generated by the
`de.benediktritter.maven-plugin-development` plugin, and the lifecycle
participant is registered via a hand-written
`META-INF/plexus/components.xml`.

Functional tests against a real `mvn` invocation are deferred until the
LarkBatis artifacts are publishable to a local repository (same status as the
Gradle plugin's TestKit tests). Until then the unit tests pin the model
mutations against the shape Maven 3.9.x actually presents at extension time.

Local development: `settings.gradle.kts` already has `includeBuild("../larkbatis")`.
