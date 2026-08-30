package io.github.lightbatis.maven;

import java.io.File;
import java.nio.file.Path;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * The plugin declaration's {@code <configuration>}, resolved:
 *
 * <pre>{@code
 * <configuration>
 *     <mapperDir>src/main/mappers</mapperDir>      <!-- default: src/main/resources -->
 *     <addProcessorPath>false</addProcessorPath>   <!-- default: true -->
 *     <addParameters>false</addParameters>         <!-- default: true -->
 * </configuration>
 * }</pre>
 *
 * <p>Read by the lifecycle participant, not by mojo parameter injection —
 * the participant runs before any mojo is configured.
 *
 * @param mapperDir directory scanned (recursively) for mapper XML; only files
 *     with a {@code <mapper>} root element are picked up, so the
 *     {@code src/main/resources} default is safe next to other XML. Relative
 *     paths resolve against the project basedir.
 * @param addProcessorPath whether {@code lightbatis-processor} is appended to
 *     {@code maven-compiler-plugin}'s {@code <annotationProcessorPaths>}
 *     automatically. Switch off to manage the processor path yourself.
 * @param addParameters whether {@code <parameters>true</parameters>} is set on
 *     {@code maven-compiler-plugin} when the build has no opinion of its own.
 *     Not a convenience: without parameter names in the class files, an
 *     incremental build that re-runs the processor over unchanged mappers sees
 *     {@code arg0} and every {@code #{name}} stops resolving. Switch off only
 *     with {@code @Param} on every mapper parameter
 */
record PluginSettings(Path mapperDir, boolean addProcessorPath, boolean addParameters) {

    static final String DEFAULT_MAPPER_DIR = "src/main/resources";

    static PluginSettings from(Plugin declaration, File basedir) {
        Xpp3Dom configuration = (Xpp3Dom) declaration.getConfiguration();
        String mapperDirValue = childValue(configuration, "mapperDir");
        String addProcessorPathValue = childValue(configuration, "addProcessorPath");
        String addParametersValue = childValue(configuration, "addParameters");

        Path mapperDir = Path.of(
                mapperDirValue == null || mapperDirValue.isBlank()
                        ? DEFAULT_MAPPER_DIR
                        : mapperDirValue.trim());
        if (!mapperDir.isAbsolute()) {
            mapperDir = basedir.toPath().resolve(mapperDir);
        }
        boolean addProcessorPath = addProcessorPathValue == null
                || Boolean.parseBoolean(addProcessorPathValue.trim());
        boolean addParameters = addParametersValue == null
                || Boolean.parseBoolean(addParametersValue.trim());
        return new PluginSettings(mapperDir.normalize(), addProcessorPath, addParameters);
    }

    private static String childValue(Xpp3Dom configuration, String name) {
        if (configuration == null) {
            return null;
        }
        Xpp3Dom child = configuration.getChild(name);
        return child == null ? null : child.getValue();
    }
}
