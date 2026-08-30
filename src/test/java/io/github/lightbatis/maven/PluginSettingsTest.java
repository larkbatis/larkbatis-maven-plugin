package io.github.lightbatis.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import org.apache.maven.model.Plugin;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {

    private static final File BASEDIR = new File("/project");

    @Test
    void defaultsWithoutConfiguration() {
        PluginSettings settings = PluginSettings.from(new Plugin(), BASEDIR);

        assertEquals(Path.of("/project/src/main/resources"), settings.mapperDir());
        assertTrue(settings.addProcessorPath());
    }

    @Test
    void relativeMapperDirResolvesAgainstBasedir() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration><mapperDir>src/main/mappers</mapperDir>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals(Path.of("/project/src/main/mappers"), settings.mapperDir());
    }

    @Test
    void absoluteMapperDirKept() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration><mapperDir>/elsewhere/mappers</mapperDir>"
                        + "</configuration>"),
                BASEDIR);

        assertEquals(Path.of("/elsewhere/mappers"), settings.mapperDir());
    }

    @Test
    void blankMapperDirFallsBackToDefault() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration><mapperDir>  </mapperDir></configuration>"),
                BASEDIR);

        assertEquals(Path.of("/project/src/main/resources"), settings.mapperDir());
    }

    @Test
    void addProcessorPathFalseParsed() {
        PluginSettings settings = PluginSettings.from(
                declaration("<configuration><addProcessorPath>false</addProcessorPath>"
                        + "</configuration>"),
                BASEDIR);

        assertFalse(settings.addProcessorPath());
    }

    private static Plugin declaration(String configurationXml) {
        Plugin plugin = new Plugin();
        plugin.setConfiguration(TestXml.dom(configurationXml));
        return plugin;
    }
}
