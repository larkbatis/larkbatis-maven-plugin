package io.github.larkbatis.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Plugin;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {

    private static final File BASEDIR = new File("/project");

    @Test
    void defaultsWithoutConfiguration() {
        PluginSettings settings = PluginSettings.from(new Plugin(), BASEDIR);

        assertEquals(List.of(Path.of("/project/src/main/resources")), settings.mapperDirs());
        assertTrue(settings.addProcessorPath());
    }

    @Test
    void relativeMapperDirResolvesAgainstBasedir() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration><mapperDir>src/main/mappers</mapperDir>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals(List.of(Path.of("/project/src/main/mappers")), settings.mapperDirs());
    }

    @Test
    void absoluteMapperDirKept() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration><mapperDir>/elsewhere/mappers</mapperDir>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals(List.of(Path.of("/elsewhere/mappers")), settings.mapperDirs());
    }

    @Test
    void blankMapperDirFallsBackToDefault() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration><mapperDir>  </mapperDir></configuration>"),
                BASEDIR);

        assertEquals(List.of(Path.of("/project/src/main/resources")), settings.mapperDirs());
    }

    @Test
    void mapperDirsListedAfterTheSingularOne() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration>"
                        + "<mapperDir>src/main/mappers</mapperDir>"
                        + "<mapperDirs>"
                        + "  <mapperDir>src/main/legacy-mappers</mapperDir>"
                        + "  <mapperDir>/elsewhere/mappers</mapperDir>"
                        + "</mapperDirs>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals(List.of(
                        Path.of("/project/src/main/mappers"),
                        Path.of("/project/src/main/legacy-mappers"),
                        Path.of("/elsewhere/mappers")),
                settings.mapperDirs());
    }

    /**
     * The resources default is a fallback, not a floor. A build that lists its
     * mapper trees gets those trees — quietly adding a directory it never named
     * would pull an unrelated {@code <mapper>} file into the compilation.
     */
    @Test
    void mapperDirsAloneReplacesTheDefault() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration><mapperDirs>"
                        + "<mapperDir>src/main/mappers</mapperDir>"
                        + "</mapperDirs></configuration>"),
                BASEDIR);

        assertEquals(List.of(Path.of("/project/src/main/mappers")), settings.mapperDirs());
    }

    /**
     * Walking one directory twice would make the processor read every mapper
     * XML in it twice and report each namespace as declared by two files — a
     * compile error, out of a configuration that only looks redundant.
     */
    @Test
    void deduplicatesADirectoryNamedTwice() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration>"
                        + "<mapperDir>src/main/mappers</mapperDir>"
                        + "<mapperDirs><mapperDir>./src/main/mappers</mapperDir></mapperDirs>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals(List.of(Path.of("/project/src/main/mappers")), settings.mapperDirs());
    }

    @Test
    void blankEntriesInTheListAreSkipped() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration><mapperDirs>"
                        + "<mapperDir>  </mapperDir>"
                        + "<mapperDir>src/main/mappers</mapperDir>"
                        + "</mapperDirs></configuration>"),
                BASEDIR);

        assertEquals(List.of(Path.of("/project/src/main/mappers")), settings.mapperDirs());
    }

    /**
     * One option, several directories: the processor splits on the platform
     * path separator, and the round trip is what the goals reading the project
     * property depend on.
     */
    @Test
    void joinAndSplitRoundTrip() {
        List<Path> dirs = List.of(Path.of("/project/a"), Path.of("/project/b"));

        assertEquals(dirs, PluginSettings.split(PluginSettings.join(dirs)));
    }

    @Test
    void addProcessorPathFalseParsed() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration><addProcessorPath>false</addProcessorPath>"
                        + "</configuration>"),
                BASEDIR);

        assertFalse(settings.addProcessorPath());
    }

    // ── Processor options ────────────────────────────────────────────

    @Test
    void processorOptionsAreNullByDefault() {
        PluginSettings settings = PluginSettings.from(new Plugin(), BASEDIR);

        assertEquals(null, settings.mapUnderscoreToCamelCase());
        assertEquals(null, settings.typeHandlers());
        assertEquals(null, settings.registryPackage());
        assertEquals(null, settings.springConfig());
        assertEquals(null, settings.springConfigPackage());
    }

    @Test
    void mapUnderscoreToCamelCaseParsed() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration>"
                        + "<mapUnderscoreToCamelCase>false</mapUnderscoreToCamelCase>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals("false", settings.mapUnderscoreToCamelCase());
    }

    @Test
    void typeHandlersParsed() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration>"
                        + "<typeHandlers>com.example.Money:com.example.MoneyHandler</typeHandlers>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals("com.example.Money:com.example.MoneyHandler", settings.typeHandlers());
    }

    @Test
    void registryPackageParsed() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration>"
                        + "<registryPackage>com.example.app</registryPackage>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals("com.example.app", settings.registryPackage());
    }

    @Test
    void springConfigParsed() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration>"
                        + "<springConfig>false</springConfig>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals("false", settings.springConfig());
    }

    @Test
    void springConfigPackageParsed() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration>"
                        + "<springConfigPackage>com.example.config</springConfigPackage>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals("com.example.config", settings.springConfigPackage());
    }

    @Test
    void blankProcessorOptionsAreNull() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration>"
                        + "<mapUnderscoreToCamelCase>  </mapUnderscoreToCamelCase>"
                        + "<registryPackage></registryPackage>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals(null, settings.mapUnderscoreToCamelCase());
        assertEquals(null, settings.registryPackage());
    }

    private static Plugin declaration(String configurationXml) {
        Plugin plugin = new Plugin();
        plugin.setConfiguration(TestXml.dom(configurationXml));
        return plugin;
    }
}

