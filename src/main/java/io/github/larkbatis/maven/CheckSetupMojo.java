package io.github.larkbatis.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * {@code larkbatis:check} — verifies the build extension actually ran, and
 * reports what it resolved. The one silent failure mode of this plugin is a
 * declaration without {@code <extensions>true</extensions>}: the lifecycle
 * participant is never discovered, nothing is injected, and mappers just stop
 * being generated. This mojo turns that into a diagnosable error by checking
 * for the {@code larkbatis.mapperDir} property the participant sets.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public final class CheckSetupMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Declared so Maven recognizes the name. The plugin's configuration is
     * read by the build extension, not by mojo injection (it has to run
     * before any mojo is configured), but Maven warns about a configuration
     * element that is not a parameter of any goal — on exactly the command
     * meant to reassure the user their setup is right. See
     * {@link PluginSettings}.
     */
    @Parameter
    private File mapperDir;

    /** Declared for the same reason as {@link #mapperDir}. */
    @Parameter
    private List<File> mapperDirs;

    /** Declared for the same reason as {@link #mapperDir}. */
    @Parameter(defaultValue = "true")
    private boolean addProcessorPath;

    /** Declared for the same reason as {@link #mapperDir}. */
    @Parameter(defaultValue = "true")
    private boolean addParameters;

    @Override
    public void execute() throws MojoFailureException {
        String resolved = project.getProperties()
                .getProperty(LarkBatisLifecycleParticipant.MAPPER_DIR_PROPERTY);
        if (resolved == null) {
            throw new MojoFailureException(
                    "LarkBatis is not wired into this build: the plugin's lifecycle "
                    + "extension did not run. Add <extensions>true</extensions> to the "
                    + "larkbatis-maven-plugin declaration in <build><plugins>.");
        }
        // Naming each one, and saying which is missing: a mistyped directory
        // in a list of them generates nothing and reports nothing, and this is
        // the command whose whole job is to make that visible.
        for (Path dir : PluginSettings.split(resolved)) {
            getLog().info("LarkBatis mapper directory: " + dir
                    + (Files.isDirectory(dir) ? "" : " (does not exist)"));
        }
        getLog().info("LarkBatis processor path added automatically: " + addProcessorPath);
        getLog().info("LarkBatis -parameters set automatically: " + addParameters);
    }
}
