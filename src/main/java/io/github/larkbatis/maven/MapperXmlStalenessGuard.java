package io.github.larkbatis.maven;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Maven's counterpart to the Gradle plugin registering mapper XML as
 * {@code compileJava} inputs: {@code maven-compiler-plugin} recompiles only
 * when a {@code .java} file is stale, so editing just a mapper XML would leave
 * the generated {@code $$Impl} silently outdated. The guard reads each mapper
 * XML's {@code namespace} (= mapper interface FQN) and, when the file's
 * content changed since the last build, bumps that interface source's
 * last-modified time — the next compile then reruns the processor, which
 * regenerates from the fresh XML. Content is never modified.
 *
 * <p>Change detection is by <b>content hash</b>, recorded in a state file
 * under the build directory, not by comparing timestamps against the compiled
 * class. Timestamps are the wrong instrument here in both directions: a mapper
 * XML with a future mtime (archive extraction, container clock skew, a
 * restored cache) would look stale on every single build and force a full
 * recompile forever, while an edit landing inside the same coarse mtime tick
 * as the previous compile (1–2 s granularity on some network filesystems)
 * would be missed entirely — the exact failure this guard exists to prevent.
 * Hashing also matches what the Gradle plugin gets for free from
 * {@code @InputFiles}.
 *
 * <p>Best-effort by construction: every IO failure becomes a warning, never an
 * exception. A guard that cannot touch a file must not take the build down
 * with it — worst case the user runs a clean build.
 */
final class MapperXmlStalenessGuard {

    /**
     * @param touched  interface sources whose timestamp was bumped
     * @param warnings human-readable problems; the caller logs them as warnings
     */
    record Result(List<Path> touched, List<String> warnings) {
    }

    private final Path mapperDir;
    private final List<String> compileSourceRoots;
    private final Path outputDirectory;
    private final Path stateFile;
    private final List<Path> touched = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private MapperXmlStalenessGuard(Path mapperDir, List<String> compileSourceRoots,
            Path outputDirectory, Path stateFile) {
        this.mapperDir = mapperDir;
        this.compileSourceRoots = compileSourceRoots;
        this.outputDirectory = outputDirectory;
        this.stateFile = stateFile;
    }

    /**
     * Touches the mapper interface source of every mapper XML whose content
     * changed since the last run, and records the new hashes.
     *
     * @param stateFile where the content hashes live, typically under
     *                  {@code target/}: removed by {@code mvn clean}, which is
     *                  harmless because a clean build recompiles anyway
     */
    static Result refresh(Path mapperDir, List<String> compileSourceRoots,
            Path outputDirectory, Path stateFile) {
        MapperXmlStalenessGuard guard = new MapperXmlStalenessGuard(
                mapperDir, compileSourceRoots, outputDirectory, stateFile);
        guard.run();
        return new Result(List.copyOf(guard.touched), List.copyOf(guard.warnings));
    }

    private void run() {
        if (!Files.isDirectory(mapperDir)) {
            return;
        }
        List<Path> xmlFiles;
        try (Stream<Path> walk = Files.walk(mapperDir)) {
            xmlFiles = walk.filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            warnings.add("could not scan " + mapperDir + " (" + e + ")");
            return;
        }

        Properties previous = loadState();
        Properties current = new Properties();
        for (Path xml : xmlFiles) {
            byte[] content;
            try {
                content = Files.readAllBytes(xml);
            } catch (IOException e) {
                warnings.add("could not read " + xml + " (" + e + ")");
                continue;
            }
            String namespace = mapperNamespace(content);
            if (namespace == null) {
                continue; // not a mapper file; the processor owns those diagnostics
            }
            String key = mapperDir.relativize(xml).toString().replace('\\', '/');
            String entry = namespace + ' ' + sha256(content);
            String previousEntry = previous.remove(key) instanceof String s ? s : null;
            if (entry.equals(previousEntry)) {
                current.setProperty(key, entry);
                continue;
            }
            // Record the new hash only once the interface has actually been
            // touched. Recording it after a failed touch would silence the
            // warning from the next build onwards and ship a stale $$Impl
            // forever, looking healthy the whole time.
            if (touchInterfaceSource(namespace)) {
                current.setProperty(key, entry);
            } else if (previousEntry != null) {
                current.setProperty(key, previousEntry);
            }
        }
        // Whatever is left in `previous` was deleted since the last build.
        // Its interface must recompile too: the processor is what reports a
        // mapper method whose XML statement is gone, and it never runs unless
        // something makes the source stale.
        previous.forEach((key, entry) -> {
            String namespace = namespaceOf((String) entry);
            if (namespace != null && !touchInterfaceSource(namespace)) {
                current.setProperty((String) key, (String) entry); // retry next build
            }
        });
        saveState(current);
    }

    /**
     * A missing {@code .class} needs no touch — that interface compiles
     * anyway; a missing source is not ours to explain (the processor reports
     * an XML statement with no mapper interface). Both count as handled.
     *
     * @return false only when a touch was needed and failed
     */
    private boolean touchInterfaceSource(String namespace) {
        String relative = namespace.replace('.', '/');
        if (!Files.isRegularFile(outputDirectory.resolve(relative + ".class"))) {
            return true;
        }
        for (String sourceRoot : compileSourceRoots) {
            Path source = Path.of(sourceRoot).resolve(relative + ".java");
            if (!Files.isRegularFile(source)) {
                continue;
            }
            try {
                Files.setLastModifiedTime(source, FileTime.fromMillis(System.currentTimeMillis()));
                touched.add(source);
                return true;
            } catch (IOException e) {
                warnings.add("mapper XML for " + namespace + " changed but " + source
                        + " could not be touched (" + e + ") — run a clean build so the"
                        + " generated mapper picks the change up");
                return false;
            }
        }
        return true;
    }

    /** State entries are {@code <namespace> <hash>}; older files may be hash-only. */
    private static String namespaceOf(String entry) {
        int space = entry.indexOf(' ');
        return space <= 0 ? null : entry.substring(0, space);
    }

    private Properties loadState() {
        Properties state = new Properties();
        if (!Files.isRegularFile(stateFile)) {
            return state;
        }
        try (InputStream in = Files.newInputStream(stateFile)) {
            state.load(in);
        } catch (IOException | IllegalArgumentException e) {
            warnings.add("could not read " + stateFile + " (" + e + ") — treating every"
                    + " mapper XML as changed");
            return new Properties();
        }
        return state;
    }

    private void saveState(Properties state) {
        try {
            Files.createDirectories(stateFile.getParent());
            try (var out = Files.newOutputStream(stateFile)) {
                state.store(out, "LarkBatis: mapper XML content hashes, for change detection");
            }
        } catch (IOException e) {
            warnings.add("could not write " + stateFile + " (" + e + ") — mapper interfaces"
                    + " will be touched on every build");
        }
    }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }

    /**
     * Root element and {@code namespace} attribute of a mapper XML, or null
     * for anything that is not a well-formed {@code <mapper>} document. StAX,
     * offline: the DOCTYPE's system id is never fetched. Namespace awareness
     * is off so the root name is compared exactly as the processor's
     * (non-namespace-aware) DOM sees it — {@code <x:mapper>} is not a mapper
     * to either of them.
     */
    private static String mapperNamespace(byte[] content) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
        factory.setXMLResolver((publicId, systemId, base, ns) ->
                new ByteArrayInputStream(new byte[0]));
        try {
            XMLStreamReader reader =
                    factory.createXMLStreamReader(new ByteArrayInputStream(content));
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                        if (!"mapper".equals(reader.getLocalName())) {
                            return null;
                        }
                        return reader.getAttributeValue(null, "namespace");
                    }
                }
                return null;
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            return null;
        }
    }
}
