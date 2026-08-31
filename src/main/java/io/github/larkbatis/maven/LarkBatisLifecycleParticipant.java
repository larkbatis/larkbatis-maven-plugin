package io.github.larkbatis.maven;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires mapper XML into the LarkBatis annotation processor,
 * the Maven way. Unlike Gradle, Maven finalizes every mojo's configuration
 * before the first mojo of a project runs, so a plain mojo executing in an
 * early phase cannot add {@code -Alarkbatis.mapperDir} to the {@code compile}
 * execution. A build extension can: {@link #afterProjectsRead} runs before
 * execution plans are calculated and mutates the project model. This is why
 * the plugin declaration needs {@code <extensions>true</extensions>}.
 *
 * <p>For each project declaring this plugin it:
 * <ul>
 *   <li>injects the {@code -Alarkbatis.mapperDir} compiler argument and the
 *       {@code larkbatis-processor} annotation processor path into every
 *       compile-bound execution of {@code maven-compiler-plugin}
 *       ({@link CompilerConfigInjection}),</li>
 *   <li>sets the {@code larkbatis.mapperDir} project property — every
 *       resolved directory, path-separator separated — which
 *       {@link RefreshMojo} reads and {@code larkbatis:check} looks for to
 *       diagnose a missing {@code <extensions>true</extensions>},</li>
 *   <li>binds {@link RefreshMojo}, which touches mapper interface sources
 *       whose mapper XML changed — {@code maven-compiler-plugin} only
 *       recompiles on stale {@code .java} files.</li>
 * </ul>
 *
 * <p>All code generation stays inside javac ({@code larkbatis-processor});
 * this extension never generates sources itself and adds nothing to the
 * application's runtime classpath. Registered in
 * {@code META-INF/plexus/components.xml}.
 */
public final class LarkBatisLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    /**
     * Property announcing the resolved mapper directories to later goals,
     * path-separator separated in the order they are scanned.
     */
    static final String MAPPER_DIR_PROPERTY = "larkbatis.mapperDir";

    static final String REFRESH_EXECUTION_ID = "larkbatis-refresh";

    private static final String PLUGIN_GROUP_ID = "io.github.larkbatis";
    private static final String PLUGIN_ARTIFACT_ID = "larkbatis-maven-plugin";

    private static final Logger logger =
            LoggerFactory.getLogger(LarkBatisLifecycleParticipant.class);

    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject project : session.getProjects()) {
            configure(project);
        }
    }

    /** Per-project wiring; package-private for tests (no MavenSession needed). */
    void configure(MavenProject project) {
        Plugin declaration = project.getBuild().getPlugins().stream()
                .filter(plugin -> PLUGIN_GROUP_ID.equals(plugin.getGroupId())
                        && PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId()))
                .findFirst()
                .orElse(null);
        if (declaration == null) {
            return;
        }
        PluginSettings settings = PluginSettings.from(declaration, project.getBasedir());

        // Idempotent: two plugin versions in one reactor both reach this, and
        // every step below is a no-op once its result is already in the model.
        String mapperDirs = PluginSettings.join(settings.mapperDirs());
        project.getProperties().setProperty(MAPPER_DIR_PROPERTY, mapperDirs);
        CompilerConfigInjection.Result result = CompilerConfigInjection.inject(
                project.getBuild(), settings.mapperDirs(), settings.addProcessorPath(),
                settings.addParameters(), !"pom".equals(project.getPackaging()));
        bindRefreshGoal(declaration);

        logger.info("LarkBatis ({}): -Alarkbatis.mapperDir={} → {}", project.getArtifactId(),
                mapperDirs,
                describe(result));
        if (result.processorPathsCreated()) {
            logger.warn("LarkBatis ({}): created <annotationProcessorPaths> on "
                    + "maven-compiler-plugin — javac now loads annotation processors only "
                    + "from there. Add your other processors (e.g. Lombok) to it, or set "
                    + "<addProcessorPath>false</addProcessorPath> and manage the paths "
                    + "yourself.", project.getArtifactId());
        }
        if (result.parametersDisabledByBuild()) {
            logger.warn("LarkBatis ({}): maven-compiler-plugin has "
                    + "<parameters>false</parameters>, and that has been left alone. Parameter "
                    + "names will not reach the class files, so an incremental build that "
                    + "re-runs the processor over unchanged mappers will read arg0 and fail to "
                    + "resolve #{name}. Remove it, or put @Param on every mapper parameter.",
                    project.getArtifactId());
        }
    }

    private static String describe(CompilerConfigInjection.Result result) {
        if (!result.targets().isEmpty()) {
            return String.join(", ", result.targets());
        }
        // Nothing to do can mean two different things, and saying the wrong
        // one sends the reader looking for a <compilerArgs> they never wrote.
        return result.manualArgFound()
                ? "already configured, left as-is"
                : "nothing to configure (no compile execution in this project)";
    }

    /**
     * Binds {@code larkbatis:refresh} unless the build already asks for it,
     * so the mapper-XML staleness check runs in compiling builds only.
     */
    private static void bindRefreshGoal(Plugin declaration) {
        boolean alreadyBound = declaration.getExecutions().stream()
                .anyMatch(execution -> execution.getGoals().contains(RefreshMojo.GOAL));
        if (alreadyBound) {
            return;
        }
        PluginExecution execution = new PluginExecution();
        execution.setId(REFRESH_EXECUTION_ID);
        execution.addGoal(RefreshMojo.GOAL);
        declaration.addExecution(execution);
    }
}
