package io.github.lightbatis.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class MapperXmlStalenessGuardTest {

    /** The standard mapper prolog: the DOCTYPE's system id must never be fetched. */
    private static final String MAPPER_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="com.example.UserMapper">
              <select id="findById" resultType="com.example.User">
                SELECT id, name FROM users WHERE id = #{id}
              </select>
            </mapper>
            """;

    @TempDir
    Path projectDir;

    private Path mapperDir;
    private Path sourceRoot;
    private Path outputDirectory;
    private Path stateFile;
    private Path interfaceSource;
    private Path interfaceClass;
    private Path mapperXml;

    @BeforeEach
    void layout() throws IOException {
        mapperDir = Files.createDirectories(projectDir.resolve("src/main/resources"));
        sourceRoot = projectDir.resolve("src/main/java");
        outputDirectory = projectDir.resolve("target/classes");
        stateFile = projectDir.resolve("target/lightbatis/mapper-xml.properties");
        interfaceSource = write(sourceRoot.resolve("com/example/UserMapper.java"),
                "package com.example; public interface UserMapper {}");
        interfaceClass = write(outputDirectory.resolve("com/example/UserMapper.class"),
                "not real bytecode");
        mapperXml = write(mapperDir.resolve("com/example/UserMapper.xml"), MAPPER_XML);
    }

    @Test
    void firstRunTouchesThenSettles() throws IOException {
        // No state yet but classes exist: assume changed, touch once.
        assertEquals(List.of(interfaceSource), refresh().touched());
        assertTrue(Files.isRegularFile(stateFile));

        assertEquals(List.of(), refresh().touched(), "second run must find nothing to do");
    }

    @Test
    void changedContentTouchesTheInterfaceSource() throws IOException {
        refresh();

        Files.writeString(mapperXml, MAPPER_XML.replace("id, name", "id, name, email"));

        assertEquals(List.of(interfaceSource), refresh().touched());
    }

    /**
     * The regression that timestamp comparison could not express: an XML dated
     * in the future (archive extraction, container clock skew) must not force
     * a recompile on every build forever.
     */
    @Test
    void futureDatedXmlDoesNotLoop() throws IOException {
        refresh();
        Files.setLastModifiedTime(mapperXml,
                FileTime.from(Instant.now().plusSeconds(86_400)));

        assertEquals(List.of(), refresh().touched());
        assertEquals(List.of(), refresh().touched());
    }

    /** …and the mirror image: a same-second edit must not be missed. */
    @Test
    void sameTimestampEditIsStillDetected() throws IOException {
        refresh();
        FileTime frozen = Files.getLastModifiedTime(mapperXml);

        Files.writeString(mapperXml, MAPPER_XML.replace("users", "app_users"));
        Files.setLastModifiedTime(mapperXml, frozen);
        Files.setLastModifiedTime(interfaceClass, frozen);

        assertEquals(List.of(interfaceSource), refresh().touched());
    }

    /**
     * A deleted mapper XML must recompile its interface too: the processor is
     * what reports a mapper method whose statement is gone, and it never runs
     * unless something makes the source stale.
     */
    @Test
    void deletedXmlTouchesTheInterfaceSource() throws IOException {
        refresh();
        Files.delete(mapperXml);

        assertEquals(List.of(interfaceSource), refresh().touched());
        assertEquals(List.of(), refresh().touched(), "and only once");
    }

    @Test
    void missingClassNeedsNoTouch() throws IOException {
        Files.delete(interfaceClass);

        MapperXmlStalenessGuard.Result result = refresh();

        assertEquals(List.of(), result.touched());
        // The hash is still recorded, so the next build starts from a known state.
        assertTrue(Files.readString(stateFile).contains("UserMapper.xml"));
    }

    @Test
    void missingSourceSkipped() throws IOException {
        Files.delete(interfaceSource);

        assertEquals(List.of(), refresh().touched());
    }

    @Test
    void nonMapperXmlIgnored() throws IOException {
        Files.writeString(mapperXml, "<beans><bean class=\"com.example.UserMapper\"/></beans>");

        assertEquals(List.of(), refresh().touched());
    }

    /** The processor's DOM is not namespace-aware either: {@code <x:mapper>} is not a mapper. */
    @Test
    void prefixedRootIgnoredLikeTheProcessorDoes() throws IOException {
        Files.writeString(mapperXml,
                "<x:mapper xmlns:x=\"urn:x\" namespace=\"com.example.UserMapper\"/>");

        assertEquals(List.of(), refresh().touched());
    }

    @Test
    void malformedXmlIgnored() throws IOException {
        Files.writeString(mapperXml, "<mapper namespace=\"com.example.UserMapper\"");

        assertEquals(List.of(), refresh().touched());
    }

    @Test
    void missingMapperDirIsFine() {
        MapperXmlStalenessGuard.Result result = MapperXmlStalenessGuard.refresh(
                projectDir.resolve("does/not/exist"),
                List.of(sourceRoot.toString()), outputDirectory, stateFile);

        assertEquals(List.of(), result.touched());
        assertEquals(List.of(), result.warnings());
    }

    /**
     * A read-only source tree (CI checkout, foreign ownership) must degrade to
     * a warning: this guard runs in every compiling build and must never be
     * the reason one fails.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
    void unwritableSourceWarnsInsteadOfFailing() throws IOException {
        Files.setPosixFilePermissions(interfaceSource, java.util.Set.of());
        Files.setPosixFilePermissions(sourceRoot.resolve("com/example"), java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        try {
            MapperXmlStalenessGuard.Result result = refresh();

            assertEquals(List.of(), result.touched());
            assertEquals(1, result.warnings().size(), result.warnings().toString());
            assertTrue(result.warnings().get(0).contains("could not be touched"),
                    result.warnings().toString());

            // The hash must NOT be recorded as done: otherwise the next build
            // sees no change, warns nothing, and ships a stale $$Impl forever.
            assertEquals(1, refresh().warnings().size(),
                    "a failed touch must be retried and re-reported on the next build");
        } finally {
            Files.setPosixFilePermissions(sourceRoot.resolve("com/example"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(interfaceSource,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }

    private MapperXmlStalenessGuard.Result refresh() {
        return MapperXmlStalenessGuard.refresh(
                mapperDir, List.of(sourceRoot.toString()), outputDirectory, stateFile);
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
