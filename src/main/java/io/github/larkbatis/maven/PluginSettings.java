package io.github.larkbatis.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * The plugin declaration's {@code <configuration>}, resolved:
 *
 * <pre>{@code
 * <configuration>
 *     <mapperDir>src/main/mappers</mapperDir>      <!-- default: src/main/resources -->
 *     <mapperDirs>                                 <!-- default: empty -->
 *         <mapperDir>src/main/legacy-mappers</mapperDir>
 *         <mapperDir>../shared/src/main/mappers</mapperDir>
 *     </mapperDirs>
 *     <addProcessorPath>false</addProcessorPath>   <!-- default: true -->
 *     <addParameters>false</addParameters>         <!-- default: true -->
 *     <mapUnderscoreToCamelCase>false</mapUnderscoreToCamelCase>  <!-- default: not set -->
 *     <typeHandlers>com.example.Money:com.example.MoneyHandler</typeHandlers>
 *     <registryPackage>com.example.app</registryPackage>
 *     <springConfig>false</springConfig>
 *     <springConfigPackage>com.example.config</springConfigPackage>
 * </configuration>
 * }</pre>
 *
 * <p>Read by the lifecycle participant, not by mojo parameter injection —
 * the participant runs before any mojo is configured.
 *
 * @param mapperDirs directories scanned (recursively) for mapper XML; only
 *     files with a {@code <mapper>} root element are picked up, so the
 *     {@code src/main/resources} default is safe next to other XML. Relative
 *     paths resolve against the project basedir. {@code <mapperDir>} comes
 *     first, then the {@code <mapperDirs>} list, duplicates removed; the
 *     default applies only when the build names neither, so listing mapper
 *     trees does not quietly add a resources directory nobody mentioned
 * @param addProcessorPath whether {@code larkbatis-processor} is appended to
 *     {@code maven-compiler-plugin}'s {@code <annotationProcessorPaths>}
 *     automatically. Switch off to manage the processor path yourself.
 * @param addParameters whether {@code <parameters>true</parameters>} is set on
 *     {@code maven-compiler-plugin} when the build has no opinion of its own.
 *     Not a convenience: without parameter names in the class files, an
 *     incremental build that re-runs the processor over unchanged mappers sees
 *     {@code arg0} and every {@code #{name}} stops resolving. Switch off only
 *     with {@code @Param} on every mapper parameter
 * @param mapUnderscoreToCamelCase {@code "false"} makes underscores significant
 *     when a column label is matched to a property. {@code null} when unset —
 *     the processor applies its own default ({@code true})
 * @param typeHandlers default type handlers, comma-separated
 *     {@code javaType:handlerClass} pairs. {@code null} when unset
 * @param registryPackage package for the generated {@code LarkBatisMappers}
 *     registry class. {@code null} when unset — the processor picks the common
 *     package prefix
 * @param springConfig {@code "false"} suppresses the generated Spring
 *     {@code @Configuration}. {@code null} when unset
 * @param springConfigPackage package for the generated Spring
 *     {@code @Configuration}. {@code null} when unset
 */
record PluginSettings(List<Path> mapperDirs, boolean addProcessorPath, boolean addParameters,
        String mapUnderscoreToCamelCase, String typeHandlers, String registryPackage,
        String springConfig, String springConfigPackage) {

    static final String DEFAULT_MAPPER_DIR = "src/main/resources";

    static PluginSettings from(Plugin declaration, File basedir) {
        Xpp3Dom configuration = (Xpp3Dom) declaration.getConfiguration();
        String addProcessorPathValue = childValue(configuration, "addProcessorPath");
        String addParametersValue = childValue(configuration, "addParameters");

        List<String> declared = new ArrayList<>();
        addIfPresent(declared, childValue(configuration, "mapperDir"));
        for (String value : childValues(configuration, "mapperDirs")) {
            addIfPresent(declared, value);
        }
        if (declared.isEmpty()) {
            declared.add(DEFAULT_MAPPER_DIR);
        }

        // A directory reached twice would be walked twice, and the second walk
        // reports every namespace in it as declared by two files.
        Set<Path> mapperDirs = new LinkedHashSet<>();
        for (String value : declared) {
            Path dir = Path.of(value);
            if (!dir.isAbsolute()) {
                dir = basedir.toPath().resolve(dir);
            }
            mapperDirs.add(dir.normalize());
        }

        boolean addProcessorPath = addProcessorPathValue == null
                || Boolean.parseBoolean(addProcessorPathValue.trim());
        boolean addParameters = addParametersValue == null
                || Boolean.parseBoolean(addParametersValue.trim());

        // Processor options — null when absent, passed through verbatim.
        String mapUnderscoreToCamelCase = trimmedOrNull(childValue(configuration,
                "mapUnderscoreToCamelCase"));
        String typeHandlers = trimmedOrNull(childValue(configuration, "typeHandlers"));
        String registryPackage = trimmedOrNull(childValue(configuration, "registryPackage"));
        String springConfig = trimmedOrNull(childValue(configuration, "springConfig"));
        String springConfigPackage = trimmedOrNull(childValue(configuration,
                "springConfigPackage"));

        return new PluginSettings(List.copyOf(mapperDirs), addProcessorPath, addParameters,
                mapUnderscoreToCamelCase, typeHandlers, registryPackage,
                springConfig, springConfigPackage);
    }

    /**
     * The directories as one {@code -A} option value, and as one project
     * property. The processor splits on the platform path separator or a
     * comma; the separator is the safer of the two to write, being {@code ;}
     * on the one platform where a path can contain a colon, while a comma is
     * legal in a directory name everywhere.
     */
    static String join(List<Path> mapperDirs) {
        StringBuilder joined = new StringBuilder();
        for (Path dir : mapperDirs) {
            if (joined.length() > 0) {
                joined.append(File.pathSeparatorChar);
            }
            joined.append(dir);
        }
        return joined.toString();
    }

    /** The inverse of {@link #join}, for the goals that read the property. */
    static List<Path> split(String joined) {
        List<Path> dirs = new ArrayList<>();
        for (String value : joined.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!value.isBlank()) {
                dirs.add(Path.of(value));
            }
        }
        return List.copyOf(dirs);
    }

    private static void addIfPresent(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private static String childValue(Xpp3Dom configuration, String name) {
        if (configuration == null) {
            return null;
        }
        Xpp3Dom child = configuration.getChild(name);
        return child == null ? null : child.getValue();
    }

    /**
     * The values under a wrapper element. Plexus pairs a list parameter with
     * its children by position rather than by name, so {@code <mapperDir>} is
     * a convention for the reader and any child name is accepted.
     */
    private static List<String> childValues(Xpp3Dom configuration, String name) {
        if (configuration == null) {
            return List.of();
        }
        Xpp3Dom wrapper = configuration.getChild(name);
        if (wrapper == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Xpp3Dom child : wrapper.getChildren()) {
            values.add(child.getValue());
        }
        return values;
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

