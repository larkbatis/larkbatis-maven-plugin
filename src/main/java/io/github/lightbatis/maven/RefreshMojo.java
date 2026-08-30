package io.github.lightbatis.maven;

import java.io.File;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * {@code lightbatis:refresh} — bumps the timestamp of a mapper interface
 * source whose mapper XML changed, so {@code maven-compiler-plugin} (which
 * only looks at {@code .java} staleness) recompiles it and the processor
 * regenerates from the fresh XML. See {@link MapperXmlStalenessGuard}.
 *
 * <p>{@link LightBatisLifecycleParticipant} binds this goal automatically.
 * It is a goal rather than more work inside the extension so that touching
 * files in the source tree happens only in builds that actually compile —
 * {@code mvn clean} or {@code mvn help:effective-pom} leave the tree alone.
 */
@Mojo(name = "refresh", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public final class RefreshMojo extends AbstractMojo {

    static final String GOAL = "refresh";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Content hashes of the mapper XML as of the last run. */
    @Parameter(defaultValue = "${project.build.directory}/lightbatis/mapper-xml.properties",
            readonly = true)
    private File stateFile;

    @Override
    public void execute() {
        // Read straight off the project rather than through a
        // ${lightbatis.mapperDir} parameter default: expression evaluation
        // prefers session/user properties, so `mvn -Dlightbatis.mapperDir=…`
        // would scan one directory while javac was handed another.
        String mapperDir = project.getProperties()
                .getProperty(LightBatisLifecycleParticipant.MAPPER_DIR_PROPERTY);
        if (mapperDir == null) {
            // Only reachable if someone binds this goal by hand without the
            // build extension; CheckSetupMojo explains that case properly.
            getLog().warn("LightBatis: no mapper directory resolved — is "
                    + "<extensions>true</extensions> missing from the plugin declaration?");
            return;
        }
        MapperXmlStalenessGuard.Result result = MapperXmlStalenessGuard.refresh(
                Path.of(mapperDir),
                project.getCompileSourceRoots(),
                Path.of(project.getBuild().getOutputDirectory()),
                stateFile.toPath());

        result.warnings().forEach(warning -> getLog().warn("LightBatis: " + warning));
        if (!result.touched().isEmpty()) {
            getLog().info("LightBatis: mapper XML changed; recompiling "
                    + result.touched().size() + " mapper interface(s)");
            result.touched().forEach(source -> getLog().debug("  touched " + source));
        }
    }
}
