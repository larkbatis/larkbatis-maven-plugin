package io.github.lightbatis.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

class CompilerConfigInjectionTest {

    private static final Path MAPPER_DIR = Path.of("/project/src/main/resources");
    private static final String EXPECTED_ARG =
            "-Alightbatis.mapperDir=" + MAPPER_DIR;

    // --- what the model actually looks like at extension time -------------------

    /**
     * The state {@code afterProjectsRead} really sees on a jar project: the
     * model builder has already injected {@code default-compile} /
     * {@code default-testCompile} (DefaultLifecyclePluginAnalyzer) and copied
     * plugin-level configuration down into them
     * (DefaultPluginConfigurationExpander). Injecting only into the
     * plugin-level node would therefore never reach {@code mvn compile}.
     */
    @Test
    void injectsIntoLifecycleBoundExecutions() {
        Build build = buildWithLifecycleExecutions(null);

        CompilerConfigInjection.Result result =
                CompilerConfigInjection.inject(build, MAPPER_DIR, true, true);

        assertEquals(List.of("plugin-level", "execution default-compile"), result.targets());
        Xpp3Dom configuration = executionConfiguration(build, "default-compile");
        assertEquals(EXPECTED_ARG,
                configuration.getChild("compilerArgs").getChild(0).getValue());
        assertEquals("lightbatis-processor",
                configuration.getChild("annotationProcessorPaths").getChild(0)
                        .getChild("artifactId").getValue());
    }

    /**
     * Main sources only, matching the Gradle plugin. Running the processor
     * over test sources with the main mapper directory would report every
     * main mapper XML as unmatched and emit a second registry that shadows
     * the real one for test code.
     */
    @Test
    void testCompileIsLeftAlone() {
        Build build = buildWithLifecycleExecutions(null);

        CompilerConfigInjection.inject(build, MAPPER_DIR, true, true);

        assertNull(compilerPlugin(build).getExecutions().stream()
                .filter(execution -> "default-testCompile".equals(execution.getId()))
                .findFirst()
                .orElseThrow()
                .getConfiguration());
    }

    /** A pom project compiles nothing; a phantom compiler plugin only confuses. */
    @Test
    void pomPackagingGetsNoCompilerPlugin() {
        Build build = new Build();

        CompilerConfigInjection.Result result =
                CompilerConfigInjection.inject(build, MAPPER_DIR, true, false);

        assertEquals(List.of(), result.targets());
        assertTrue(build.getPlugins().isEmpty());
    }

    @Test
    void appendsToConfigurationTheExecutionAlreadyInherited() {
        // plugin-level <compilerArgs>-parameters</compilerArgs>, already copied
        // into each execution by the model builder
        Build build = buildWithLifecycleExecutions(
                "<configuration><compilerArgs><arg>-parameters</arg></compilerArgs>"
                + "</configuration>");

        CompilerConfigInjection.inject(build, MAPPER_DIR, true, true);

        Xpp3Dom args = executionConfiguration(build, "default-compile").getChild("compilerArgs");
        assertEquals(2, args.getChildCount());
        assertEquals("-parameters", args.getChild(0).getValue());
        assertEquals(EXPECTED_ARG, args.getChild(1).getValue());
    }

    @Test
    void executionsWithoutACompileGoalAreLeftAlone() {
        Build build = buildWithLifecycleExecutions(null);
        Plugin compiler = compilerPlugin(build);
        PluginExecution other = new PluginExecution();
        other.setId("report-only");
        other.addGoal("help");
        compiler.addExecution(other);

        CompilerConfigInjection.inject(build, MAPPER_DIR, true, true);

        assertNull(other.getConfiguration());
    }

    @Test
    void injectionIsIdempotent() {
        Build build = buildWithLifecycleExecutions(null);

        CompilerConfigInjection.inject(build, MAPPER_DIR, true, true);
        CompilerConfigInjection.Result second =
                CompilerConfigInjection.inject(build, MAPPER_DIR, true, true);

        assertEquals(List.of(), second.targets());
        assertTrue(second.manualArgFound());
        Xpp3Dom configuration = executionConfiguration(build, "default-compile");
        assertEquals(1, configuration.getChild("compilerArgs").getChildCount());
        assertEquals(1, configuration.getChild("annotationProcessorPaths").getChildCount());
    }

    // --- plugin-level node: direct `mvn compiler:compile` invocations -----------

    @Test
    void createsCompilerPluginWhenAbsent() {
        Build build = new Build();

        CompilerConfigInjection.Result result =
                CompilerConfigInjection.inject(build, MAPPER_DIR, true, true);

        assertEquals(List.of("plugin-level"), result.targets());
        assertTrue(result.processorPathsCreated());
        Xpp3Dom configuration = pluginConfiguration(build);
        assertEquals(EXPECTED_ARG,
                configuration.getChild("compilerArgs").getChild(0).getValue());
        Xpp3Dom path = configuration.getChild("annotationProcessorPaths").getChild(0);
        assertEquals("io.github.lightbatis", path.getChild("groupId").getValue());
        assertEquals("lightbatis-processor", path.getChild("artifactId").getValue());
        assertEquals("0.1.0-SNAPSHOT", path.getChild("version").getValue());
        // No plugin version: Maven resolves it via pluginManagement/defaults.
        assertNull(compilerPlugin(build).getVersion());
    }

    @Test
    void manualMapperDirArgWins() {
        Build build = buildWithLifecycleExecutions(
                "<configuration><compilerArgs>"
                + "<arg>-Alightbatis.mapperDir=/custom/mappers</arg>"
                + "</compilerArgs></configuration>");

        CompilerConfigInjection.Result result =
                CompilerConfigInjection.inject(build, MAPPER_DIR, true, true);

        assertTrue(result.manualArgFound());
        Xpp3Dom args = executionConfiguration(build, "default-compile").getChild("compilerArgs");
        assertEquals(1, args.getChildCount());
        assertEquals("-Alightbatis.mapperDir=/custom/mappers", args.getChild(0).getValue());
    }

    @Test
    void addProcessorPathFalseInjectsOnlyTheArg() {
        Build build = buildWithLifecycleExecutions(null);

        CompilerConfigInjection.inject(build, MAPPER_DIR, false, true);

        Xpp3Dom configuration = executionConfiguration(build, "default-compile");
        assertEquals(EXPECTED_ARG,
                configuration.getChild("compilerArgs").getChild(0).getValue());
        assertNull(configuration.getChild("annotationProcessorPaths"));
    }

    @Test
    void appendsToExistingAnnotationProcessorPaths() {
        Build build = buildWithLifecycleExecutions(
                "<configuration><annotationProcessorPaths><path>"
                + "<groupId>org.mapstruct</groupId>"
                + "<artifactId>mapstruct-processor</artifactId>"
                + "<version>1.6.0</version>"
                + "</path></annotationProcessorPaths></configuration>");

        CompilerConfigInjection.Result result =
                CompilerConfigInjection.inject(build, MAPPER_DIR, true, true);

        assertFalse(result.processorPathsCreated());
        Xpp3Dom paths = executionConfiguration(build, "default-compile")
                .getChild("annotationProcessorPaths");
        assertEquals(2, paths.getChildCount());
        assertEquals("lightbatis-processor",
                paths.getChild(1).getChild("artifactId").getValue());
    }

    @Test
    void existingProcessorPathNotDuplicated() {
        Build build = buildWithLifecycleExecutions(
                "<configuration><annotationProcessorPaths><path>"
                + "<groupId>io.github.lightbatis</groupId>"
                + "<artifactId>lightbatis-processor</artifactId>"
                + "<version>0.2.0</version>"
                + "</path></annotationProcessorPaths></configuration>");

        CompilerConfigInjection.inject(build, MAPPER_DIR, true, true);

        Xpp3Dom paths = executionConfiguration(build, "default-compile")
                .getChild("annotationProcessorPaths");
        assertEquals(1, paths.getChildCount());
        assertEquals("0.2.0", paths.getChild(0).getChild("version").getValue());
    }

    /**
     * {@code -proc:full} is not an option javac accepts before JDK 17.0.9, and
     * it is not needed: {@code annotationProcessorPaths} makes the plugin pass
     * {@code -processorpath}, which keeps processing enabled on JDK 23+.
     */
    @Test
    void procIsNeverSet() {
        Build build = buildWithLifecycleExecutions(null);

        CompilerConfigInjection.inject(build, MAPPER_DIR, true, true);

        assertNull(pluginConfiguration(build).getChild("proc"));
        assertNull(executionConfiguration(build, "default-compile").getChild("proc"));
    }

    // --- fixtures ---------------------------------------------------------------

    private static Build buildWithLifecycleExecutions(String configurationXml) {
        Build build = new Build();
        Plugin compiler = new Plugin();
        compiler.setGroupId("org.apache.maven.plugins");
        compiler.setArtifactId("maven-compiler-plugin");
        if (configurationXml != null) {
            compiler.setConfiguration(TestXml.dom(configurationXml));
        }
        compiler.addExecution(lifecycleExecution("default-compile", "compile",
                configurationXml));
        compiler.addExecution(lifecycleExecution("default-testCompile", "testCompile",
                configurationXml));
        build.addPlugin(compiler);
        return build;
    }

    /** Plugin-level configuration reaches executions as a deep copy. */
    private static PluginExecution lifecycleExecution(String id, String goal,
            String inheritedConfigurationXml) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.addGoal(goal);
        if (inheritedConfigurationXml != null) {
            execution.setConfiguration(TestXml.dom(inheritedConfigurationXml));
        }
        return execution;
    }

    private static Plugin compilerPlugin(Build build) {
        return build.getPlugins().stream()
                .filter(plugin -> "maven-compiler-plugin".equals(plugin.getArtifactId()))
                .findFirst()
                .orElseThrow();
    }

    private static Xpp3Dom pluginConfiguration(Build build) {
        Xpp3Dom configuration = (Xpp3Dom) compilerPlugin(build).getConfiguration();
        assertNotNull(configuration);
        return configuration;
    }

    private static Xpp3Dom executionConfiguration(Build build, String executionId) {
        Xpp3Dom configuration = (Xpp3Dom) compilerPlugin(build).getExecutions().stream()
                .filter(execution -> executionId.equals(execution.getId()))
                .findFirst()
                .orElseThrow()
                .getConfiguration();
        assertNotNull(configuration, executionId + " must have a configuration");
        return configuration;
    }
}
