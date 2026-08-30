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
 */
record PluginSettings(Path mapperDir, boolean addProcessorPath) {

    static final String DEFAULT_MAPPER_DIR = "src/main/resources";

    static PluginSettings from(Plugin declaration, File basedir) {
        Xpp3Dom configuration = (Xpp3Dom) declaration.getConfiguration();
        String mapperDirValue = childValue(configuration, "mapperDir");
        String addProcessorPathValue = childValue(configuration, "addProcessorPath");

        Path mapperDir = Path.of(
                mapperDirValue == null || mapperDirValue.isBlank()
                        ? DEFAULT_MAPPER_DIR
                        : mapperDirValue.trim());
        if (!mapperDir.isAbsolute()) {
            mapperDir = basedir.toPath().resolve(mapperDir);
        }
        boolean addProcessorPath = addProcessorPathValue == null
                || Boolean.parseBoolean(addProcessorPathValue.trim());
        return new PluginSettings(mapperDir.normalize(), addProcessorPath);
    }

    private static String childValue(Xpp3Dom configuration, String name) {
        if (configuration == null) {
            return null;
        }
        Xpp3Dom child = configuration.getChild(name);
        return child == null ? null : child.getValue();
    }
}
