package io.github.lightbatis.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.ConfigurationContainer;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Mutates {@code maven-compiler-plugin}'s model configuration before the
 * execution plan is calculated (see {@link LightBatisLifecycleParticipant}
 * for why this must happen at extension time):
 *
 * <ul>
 *   <li>appends {@code -Alightbatis.mapperDir=<dir>} to {@code <compilerArgs>}
 *       — skipped where that option is already passed manually,</li>
 *   <li>appends {@code lightbatis-processor} to
 *       {@code <annotationProcessorPaths>}, creating the element when absent.</li>
 * </ul>
 *
 * <p><b>Where the configuration goes.</b> Injecting only into the plugin-level
 * {@code <configuration>} would be a silent no-op for {@code mvn compile}: the
 * model builder injects the packaging's {@code default-compile} /
 * {@code default-testCompile} executions and copies plugin-level configuration
 * down into them (DefaultModelBuilder → DefaultPluginConfigurationExpander),
 * both while the project is being read — that is, <em>before</em> this code
 * runs. At plan time, DefaultMojoExecutionConfigurator reads the matching
 * execution's configuration and consults the plugin-level one only when no
 * execution matches (a direct {@code mvn compiler:compile} invocation). So
 * every compile-bound execution is injected into individually, and the
 * plugin-level node is kept for the direct-invocation case.
 *
 * <p>Configuration is edited node by node rather than merged in:
 * {@code Xpp3Dom.mergeXpp3Dom} pairs same-named children positionally and
 * would drop an existing {@code <compilerArgs>} list.
 *
 * <p>No {@code <proc>} is set. Annotation processing stays enabled on JDK 23+
 * (where javac no longer processes implicitly) because
 * {@code annotationProcessorPaths} makes the plugin pass {@code -processorpath},
 * which is itself an explicit request — while {@code -proc:full} would be an
 * invalid flag on JDK 17 patch levels below 17.0.9.
 *
 * <p>Pure model manipulation, no Maven session — unit-testable.
 */
final class CompilerConfigInjection {

    static final String COMPILER_GROUP_ID = "org.apache.maven.plugins";
    static final String COMPILER_ARTIFACT_ID = "maven-compiler-plugin";
    static final String MAPPER_DIR_ARG_PREFIX = "-Alightbatis.mapperDir=";

    static final String PROCESSOR_GROUP_ID = "io.github.lightbatis";
    static final String PROCESSOR_ARTIFACT_ID = "lightbatis-processor";
    /** Kept in lockstep with the plugin's own version by the release process. */
    static final String PROCESSOR_VERSION = "0.1.0-SNAPSHOT";

    /**
     * Main sources only — {@code testCompile} is deliberately excluded, which
     * keeps Maven aligned with the Gradle plugin (it wires {@code compileJava}
     * and {@code annotationProcessor}, never their test counterparts). Running
     * the processor over test sources with the <em>main</em> mapper directory
     * would make it report every main mapper XML as unmatched — a mandatory
     * warning per file, a failed build under {@code -Werror} — and emit a
     * second {@code LightBatisMappers} registry that shadows the real one for
     * test code. Test-scoped mappers need their own directory and registry
     * package before this can widen.
     */
    private static final List<String> COMPILE_GOALS = List.of("compile");

    /**
     * What actually changed, for logging.
     *
     * @param targets              configuration nodes injected into, named for
     *                             the log: {@code "plugin-level"},
     *                             {@code "execution default-compile"}
     * @param manualArgFound       some node already passed the {@code -A}
     *                             option; it was left alone
     * @param processorPathsCreated a fresh {@code <annotationProcessorPaths>}
     *                             was created somewhere — the footgun worth a
     *                             warning, because it switches javac from
     *                             classpath processor discovery to explicit
     *                             paths only
     */
    record Result(List<String> targets, boolean manualArgFound, boolean processorPathsCreated) {
    }

    private CompilerConfigInjection() {
    }

    /**
     * @param createCompilerPlugin add {@code maven-compiler-plugin} to the
     *     model when it is absent. False for {@code pom} packaging, which
     *     compiles nothing: an executionless, versionless compiler plugin
     *     there would be inert but visible in {@code help:effective-pom}
     */
    static Result inject(Build build, Path mapperDir, boolean addProcessorPath,
            boolean createCompilerPlugin) {
        Plugin compiler = findCompilerPlugin(build);
        if (compiler == null) {
            if (!createCompilerPlugin) {
                return new Result(List.of(), false, false);
            }
            compiler = createCompilerPlugin(build);
        }

        List<String> targets = new ArrayList<>();
        boolean manualArgFound = false;
        boolean processorPathsCreated = false;

        // Read by direct invocations (mvn compiler:compile) only.
        NodeResult pluginLevel = injectInto(compiler, mapperDir, addProcessorPath);
        if (pluginLevel.changed()) {
            targets.add("plugin-level");
        }
        manualArgFound |= pluginLevel.manualArgFound();
        processorPathsCreated |= pluginLevel.processorPathsCreated();

        // Read by every lifecycle-bound compile — the case that matters.
        for (PluginExecution execution : compiler.getExecutions()) {
            if (execution.getGoals().stream().noneMatch(COMPILE_GOALS::contains)) {
                continue;
            }
            NodeResult result = injectInto(execution, mapperDir, addProcessorPath);
            if (result.changed()) {
                targets.add("execution " + execution.getId());
            }
            manualArgFound |= result.manualArgFound();
            processorPathsCreated |= result.processorPathsCreated();
        }
        return new Result(List.copyOf(targets), manualArgFound, processorPathsCreated);
    }

    private static Plugin findCompilerPlugin(Build build) {
        return build.getPlugins().stream()
                .filter(plugin -> COMPILER_GROUP_ID.equals(plugin.getGroupId())
                        && COMPILER_ARTIFACT_ID.equals(plugin.getArtifactId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Absent from the effective model (unusual — the default lifecycle
     * normally injects it). No version: resolving that is Maven's job, via
     * pluginManagement or its defaults.
     */
    private static Plugin createCompilerPlugin(Build build) {
        Plugin created = new Plugin();
        created.setGroupId(COMPILER_GROUP_ID);
        created.setArtifactId(COMPILER_ARTIFACT_ID);
        build.addPlugin(created);
        return created;
    }

    private record NodeResult(boolean argInjected, boolean manualArgFound,
                              boolean processorPathInjected, boolean processorPathsCreated) {

        boolean changed() {
            return argInjected || processorPathInjected;
        }
    }

    /** Injects into one {@code <configuration>} node, creating it when absent. */
    private static NodeResult injectInto(ConfigurationContainer container, Path mapperDir,
            boolean addProcessorPath) {
        Xpp3Dom configuration = (Xpp3Dom) container.getConfiguration();
        if (configuration == null) {
            configuration = new Xpp3Dom("configuration");
            container.setConfiguration(configuration);
        }

        boolean argInjected = injectMapperDirArg(configuration, mapperDir);
        if (!addProcessorPath) {
            return new NodeResult(argInjected, !argInjected, false, false);
        }
        boolean processorPathsCreated = false;
        Xpp3Dom paths = configuration.getChild("annotationProcessorPaths");
        if (paths == null) {
            paths = new Xpp3Dom("annotationProcessorPaths");
            configuration.addChild(paths);
            processorPathsCreated = true;
        }
        return new NodeResult(argInjected, !argInjected,
                injectProcessorPath(paths), processorPathsCreated);
    }

    /** Appends the -A option unless this node already passes it manually. */
    private static boolean injectMapperDirArg(Xpp3Dom configuration, Path mapperDir) {
        Xpp3Dom args = configuration.getChild("compilerArgs");
        if (args == null) {
            args = new Xpp3Dom("compilerArgs");
            configuration.addChild(args);
        }
        boolean alreadyConfigured = Arrays.stream(args.getChildren())
                .anyMatch(arg -> arg.getValue() != null
                        && arg.getValue().startsWith(MAPPER_DIR_ARG_PREFIX));
        if (alreadyConfigured) {
            return false;
        }
        Xpp3Dom arg = new Xpp3Dom("arg");
        arg.setValue(MAPPER_DIR_ARG_PREFIX + mapperDir);
        args.addChild(arg);
        return true;
    }

    /** Appends the processor path unless some version of it is already listed. */
    private static boolean injectProcessorPath(Xpp3Dom paths) {
        boolean alreadyListed = Arrays.stream(paths.getChildren())
                .anyMatch(path -> PROCESSOR_GROUP_ID.equals(childValue(path, "groupId"))
                        && PROCESSOR_ARTIFACT_ID.equals(childValue(path, "artifactId")));
        if (alreadyListed) {
            return false;
        }
        Xpp3Dom path = new Xpp3Dom("path");
        path.addChild(valued("groupId", PROCESSOR_GROUP_ID));
        path.addChild(valued("artifactId", PROCESSOR_ARTIFACT_ID));
        path.addChild(valued("version", PROCESSOR_VERSION));
        paths.addChild(path);
        return true;
    }

    private static String childValue(Xpp3Dom node, String name) {
        Xpp3Dom child = node.getChild(name);
        return child == null ? null : child.getValue();
    }

    private static Xpp3Dom valued(String name, String value) {
        Xpp3Dom node = new Xpp3Dom(name);
        node.setValue(value);
        return node;
    }
}
