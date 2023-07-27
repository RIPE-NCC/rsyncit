package net.ripe.rpki.rsyncit.rsync;

import net.ripe.rpki.rsyncit.config.Config;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    @Test
    public void testRemoveOldDirectoriesWhenTheyAreOld() {
        final Function<RsyncWriter, Path> writeSomeObjects = rsyncWriter ->
            rsyncWriter.writeObjects(IntStream.range(0, 10).mapToObj(i ->
                new RpkiObject(URI.create("rsync://bla.net/path1/" + i + ".cer"), someBytes(), Instant.now())
            ).collect(Collectors.toList()));

        withRsyncWriter(
            // make it ridiculous so that we clean up everything except for the last directory
            config -> config.withTargetDirectoryRetentionPeriodMs(100).withTargetDirectoryRetentionCopiesCount(0),
            rsyncWriter -> {
                var path1 = writeSomeObjects.apply(rsyncWriter);
                sleep(200);
                var path2 = writeSomeObjects.apply(rsyncWriter);
                // path1 should be deleted as old
                assertThat(Files.exists(path1)).isFalse();
                assertThat(Files.exists(path2)).isTrue();
                sleep(200);
                var path3 = writeSomeObjects.apply(rsyncWriter);
                // path2 should also be deleted as old
                assertThat(Files.exists(path2)).isFalse();
                assertThat(Files.exists(path3)).isTrue();
            });
    }

    @Test
    public void testRemoveOldDirectoriesButKeepSomeNumberOfThem() {
        final Function<RsyncWriter, Path> writeSomeObjects = rsyncWriter ->
            rsyncWriter.writeObjects(IntStream.range(0, 10).mapToObj(i ->
                new RpkiObject(URI.create("rsync://bla.net/path1/" + i + ".cer"), someBytes(), Instant.now())
            ).collect(Collectors.toList()));

        withRsyncWriter(
            // make it ridiculous so that we clean up everything except for the last directory
            config -> config.withTargetDirectoryRetentionPeriodMs(100).withTargetDirectoryRetentionCopiesCount(2),
            rsyncWriter -> {
                var path1 = writeSomeObjects.apply(rsyncWriter);
                sleep(200);
                var path2 = writeSomeObjects.apply(rsyncWriter);
                // Nothing should be deleted, because we want to keep more older copies
                assertThat(Files.exists(path1)).isTrue();
                assertThat(Files.exists(path2)).isTrue();
            });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
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

    static void withRsyncWriter(Function<Config, Config> transformConfig, Consumer<RsyncWriter> actualTest) {
        Path tmp = null;
        try {
            final Config config = defaultConfig();
            final Path rsyncitDir = Files.createTempDirectory("rsyncit");
            final Config config1 = config.withRsyncPath(rsyncitDir.toAbsolutePath().toString());
            tmp = Files.createTempDirectory(Path.of(config1.rsyncPath()), "rsync-writer-test-");
            final Config config2 = config.withRsyncPath(tmp.toAbsolutePath().toString());
            actualTest.accept(new RsyncWriter(transformConfig.apply(config2)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (tmp != null) {
                tmp.toFile().delete();
            }
        }
    }

    static void withRsyncWriter(Consumer<RsyncWriter> actualTest) {
        withRsyncWriter(Function.identity(), actualTest);
    }

    private static final Random random = new Random();

    private static byte[] someBytes() {
        var length = random.nextInt(1000, 2000);
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

}