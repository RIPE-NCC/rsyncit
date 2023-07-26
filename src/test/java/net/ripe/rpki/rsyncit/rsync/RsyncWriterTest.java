package net.ripe.rpki.rsyncit.rsync;

import net.ripe.rpki.rsyncit.rrdp.RpkiObject;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.function.Consumer;

import static net.ripe.rpki.TestDefaults.defaultConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RsyncWriterTest {

    @Test
    public void testDateFormat() {
        Instant now = Instant.now();
        final String formattedNow = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("UTC")).format(now);
        assertNotNull(formattedNow);
    }

    @Test
    public void testRegexPublished() {
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("published-2023-04-17T15:40:14.577267").matches());
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("tmp-2023-04-17T15:40:14.577267").matches());
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("tmp-2023-04-17T15:40:14.764528-98762876").matches());
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("tmp-2023-04-18T15:18:14.543335-14322749955653697403").matches());
    }

    @Test
    public void testWriteNoObjects() {
        withRsyncWriter(rsyncWriter -> rsyncWriter.writeObjects(Collections.emptyList()));
    }

    @Test
    public void testWriteMultipleObjects() {
        withRsyncWriter(rsyncWriter -> {
            var o1 = new RpkiObject(URI.create("rsync://bla.net/path1/a.cer"), someBytes(), Instant.now());
            var o2 = new RpkiObject(URI.create("rsync://bla.net/path1/b.cer"), someBytes(), Instant.now());
            var o3 = new RpkiObject(URI.create("rsync://bla.net/path1/nested/c.cer"), someBytes(), Instant.now());
            var o4 = new RpkiObject(URI.create("rsync://bla.net/path2/d.cer"), someBytes(), Instant.now());
            var o5 = new RpkiObject(URI.create("rsync://bla.net/path2/nested1/nested2/e.cer"), someBytes(), Instant.now());
            var o6 = new RpkiObject(URI.create("rsync://different.net/path2/nested1/nested2/e.cer"), someBytes(), Instant.now());
            rsyncWriter.writeObjects(Arrays.asList(o1, o2, o3, o4, o5, o6));
            final String root = rsyncWriter.getConfig().rsyncPath();
            checkFile(Paths.get(root, "published", "bla.net", "path1", "a.cer"), o1.bytes());
            checkFile(Paths.get(root, "published", "bla.net", "path1", "b.cer"), o2.bytes());
            checkFile(Paths.get(root, "published", "bla.net", "path1", "nested", "c.cer"), o3.bytes());
            checkFile(Paths.get(root, "published", "bla.net", "path2", "d.cer"), o4.bytes());
            checkFile(Paths.get(root, "published", "bla.net", "path2", "nested1", "nested2", "e.cer"), o5.bytes());
            checkFile(Paths.get(root, "published", "different.net", "path2", "nested1", "nested2", "e.cer"), o6.bytes());
        });
    }

    private static void checkFile(Path path, byte[] bytes) {
        try {
            final File file = path.toFile();
            final byte[] readBackBytes = Files.readAllBytes(path);
            assertThat(file.exists()).isTrue();
            assertThat(Arrays.equals(bytes, readBackBytes)).isTrue();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void withRsyncWriter(Consumer<RsyncWriter> actualTest) {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory(Path.of(defaultConfig().rsyncPath()), "rsync-writer-test-");
            var config = defaultConfig(tmp.toAbsolutePath().toString());
            actualTest.accept(new RsyncWriter(config));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            assert tmp != null;
            tmp.toFile().delete();
        }
    }

    private static final Random r = new Random();

    private static byte[] someBytes() {
        var length = r.nextInt(1000, 2000);
        var bytes = new byte[length];
        r.nextBytes(bytes);
        return bytes;
    }

}