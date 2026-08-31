package io.github.larkbatis.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LarkBatisLifecycleParticipantTest {

    @TempDir
    Path projectDir;

    private final LarkBatisLifecycleParticipant participant =
            new LarkBatisLifecycleParticipant();

    @Test
    void configuresDeclaringProject() {
        MavenProject project = project(true,
                "<configuration><mapperDir>mappers</mapperDir></configuration>");

        participant.configure(project);

        Path expectedMapperDir = projectDir.resolve("mappers");
        assertEquals(expectedMapperDir.toString(),
                project.getProperties().getProperty("larkbatis.mapperDir"));
        Xpp3Dom args = compileExecutionConfiguration(project).getChild("compilerArgs");
        assertEquals("-Alarkbatis.mapperDir=" + expectedMapperDir,
                args.getChild(0).getValue());
    }

    /**
     * Several directories reach javac as one option and later goals as one
     * property, both path-separator separated — a second {@code -A} option of
     * the same name would be the last one javac reads, not the union.
     */
    @Test
    void passesEveryMapperDirectoryInOneOption() {
        MavenProject project = project(true,
                "<configuration><mapperDir>mappers</mapperDir>"
                        + "<mapperDirs><mapperDir>legacy-mappers</mapperDir></mapperDirs>"
                        + "</configuration>");

        participant.configure(project);

        String expected = projectDir.resolve("mappers") + java.io.File.pathSeparator
                + projectDir.resolve("legacy-mappers");
        assertEquals(expected, project.getProperties().getProperty("larkbatis.mapperDir"));
        Xpp3Dom args = compileExecutionConfiguration(project).getChild("compilerArgs");
        assertEquals(1, args.getChildCount());
        assertEquals("-Alarkbatis.mapperDir=" + expected, args.getChild(0).getValue());
    }

    @Test
    void bindsTheRefreshGoal() {
        MavenProject project = project(true, null);

        participant.configure(project);

        List<PluginExecution> executions = declaration(project).getExecutions();
        assertEquals(1, executions.size());
        assertEquals("larkbatis-refresh", executions.get(0).getId());
        assertEquals(List.of("refresh"), executions.get(0).getGoals());
        // No phase of its own: RefreshMojo's defaultPhase (generate-sources) governs.
        assertNull(executions.get(0).getPhase());
    }

    @Test
    void doesNotDuplicateAManuallyBoundRefreshGoal() {
        MavenProject project = project(true, null);
        PluginExecution manual = new PluginExecution();
        manual.setId("my-refresh");
        manual.setPhase("initialize");
        manual.addGoal("refresh");
        declaration(project).addExecution(manual);

        participant.configure(project);

        assertEquals(List.of("my-refresh"),
                declaration(project).getExecutions().stream()
                        .map(PluginExecution::getId).toList());
    }

    /** Two plugin versions in one reactor both reach configure(). */
    @Test
    void repeatedConfigurationIsIdempotent() {
        MavenProject project = project(true, null);

        participant.configure(project);
        participant.configure(project);

        assertEquals(1, declaration(project).getExecutions().size());
        assertEquals(1, compileExecutionConfiguration(project)
                .getChild("compilerArgs").getChildCount());
    }

    @Test
    void skipsProjectWithoutDeclaration() {
        MavenProject project = project(false, null);

        participant.configure(project);

        assertNull(project.getProperties().getProperty("larkbatis.mapperDir"));
        assertTrue(project.getBuild().getPlugins().isEmpty());
    }

    // --- fixtures ---------------------------------------------------------------

    private MavenProject project(boolean declarePlugin, String configurationXml) {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("com.example");
        model.setArtifactId("app");
        model.setVersion("1.0");
        Build build = new Build();
        build.setOutputDirectory(projectDir.resolve("target/classes").toString());
        model.setBuild(build);
        if (declarePlugin) {
            Plugin declaration = new Plugin();
            declaration.setGroupId("io.github.larkbatis");
            declaration.setArtifactId("larkbatis-maven-plugin");
            declaration.setExtensions(true);
            if (configurationXml != null) {
                declaration.setConfiguration(TestXml.dom(configurationXml));
            }
            build.addPlugin(declaration);
            // What the model builder has already injected by this point.
            Plugin compiler = new Plugin();
            compiler.setGroupId("org.apache.maven.plugins");
            compiler.setArtifactId("maven-compiler-plugin");
            PluginExecution defaultCompile = new PluginExecution();
            defaultCompile.setId("default-compile");
            defaultCompile.addGoal("compile");
            compiler.addExecution(defaultCompile);
            build.addPlugin(compiler);
        }
        MavenProject project = new MavenProject(model);
        project.setFile(projectDir.resolve("pom.xml").toFile());
        return project;
    }

    private static Plugin declaration(MavenProject project) {
        return findPlugin(project, "larkbatis-maven-plugin");
    }

    private static Xpp3Dom compileExecutionConfiguration(MavenProject project) {
        return (Xpp3Dom) findPlugin(project, "maven-compiler-plugin").getExecutions().stream()
                .filter(execution -> "default-compile".equals(execution.getId()))
                .findFirst()
                .orElseThrow()
                .getConfiguration();
    }

    private static Plugin findPlugin(MavenProject project, String artifactId) {
        return project.getBuild().getPlugins().stream()
                .filter(plugin -> artifactId.equals(plugin.getArtifactId()))
                .findFirst()
                .orElseThrow();
    }
}
