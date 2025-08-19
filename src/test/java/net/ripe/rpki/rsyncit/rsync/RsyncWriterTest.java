package net.ripe.rpki.rsyncit.rsync;

import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.rsyncit.config.Config;
import net.ripe.rpki.rsyncit.rrdp.RpkiObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static net.ripe.rpki.TestDefaults.defaultConfig;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class RsyncWriterTest {

    @Test
    public void testPublicationDirectoryNaming(@TempDir Path tempDir) {
        Instant now = Instant.now();
        Function<Instant, String> format = then -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("UTC")).format(then);

        var d1 = RsyncWriter.generatePublicationDirectoryPath(tempDir, now);
        var d2 = RsyncWriter.generatePublicationDirectoryPath(tempDir, now.plus(25, ChronoUnit.HOURS));

        assertThat(d1.startsWith(tempDir));
        assertThat(d1).isNotEqualTo(d2);
        // sibling dirs
        assertThat(d1.getParent()).isEqualTo(d2.getParent());

        assertThat(d1.toString())
                .contains("published-")
                .contains(format.apply(now));

        assertThat(d2.toString())
                .contains(format.apply(now.plus(25, ChronoUnit.HOURS)));
    }

    @Test
    public void testRegexPublished() {
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("published-2023-04-17T15:40:14.577267").matches());
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("tmp-2023-04-17T15:40:14.577267").matches());
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("tmp-2023-04-17T15:40:14.764528-98762876").matches());
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("tmp-2023-04-18T15:18:14.543335-14322749955653697403").matches());
    }

    @Test
    public void testWriteNoObjects(@TempDir Path tmpPath) throws Exception {
        withRsyncWriter(tmpPath, rsyncWriter -> rsyncWriter.writeObjects(Collections.emptyList(), Instant.now()));
    }

    @Test
    public void testWriteMultipleObjects(@TempDir Path tmpPath) throws Exception {
        withRsyncWriter(tmpPath, rsyncWriter -> {
            var o1 = new RpkiObject(URI.create("rsync://bla.net/path1/a.cer"), someBytes(), Instant.now());
            var o2 = new RpkiObject(URI.create("rsync://bla.net/path1/b.cer"), someBytes(), Instant.now());
            var o3 = new RpkiObject(URI.create("rsync://bla.net/path1/nested/c.cer"), someBytes(), Instant.now());
            var o4 = new RpkiObject(URI.create("rsync://bla.net/path2/d.cer"), someBytes(), Instant.now());
            var o5 = new RpkiObject(URI.create("rsync://bla.net/path2/nested1/nested2/e.cer"), someBytes(), Instant.now());
            var o6 = new RpkiObject(URI.create("rsync://different.net/path2/nested1/nested2/e.cer"), someBytes(), Instant.now());
            rsyncWriter.writeObjects(Arrays.asList(o1, o2, o3, o4, o5, o6), Instant.now());
            var root = rsyncWriter.getConfig().rsyncPath();
            checkFile(root.resolve("published/bla.net/path1/a.cer"), o1.bytes());
            checkFile(root.resolve("published/bla.net/path1/b.cer"), o2.bytes());
            checkFile(root.resolve("published/bla.net/path1/nested/c.cer"), o3.bytes());
            checkFile(root.resolve("published/bla.net/path2/d.cer"), o4.bytes());
            checkFile(root.resolve("published/bla.net/path2/nested1/nested2/e.cer"), o5.bytes());
            checkFile(root.resolve("published/different.net/path2/nested1/nested2/e.cer"), o6.bytes());
        });
    }

    @Test
    public void testWrite_set_time_and_permissions_on_empty_intermediate_paths(@TempDir Path tmpPath) throws Exception {
        withRsyncWriter(tmpPath, rsyncWriter -> {
            var o1 = new RpkiObject(URI.create("rsync://bla.net/path1/a.cer"), someBytes(), Instant.now());
            var o2 = new RpkiObject(URI.create("rsync://bla.net/path1/nested/empty/dir/tree/c.cer"), someBytes(), Instant.now());
            var targetDir = rsyncWriter.writeObjects(Arrays.asList(o1, o2), Instant.now());

            var dirCount = new AtomicInteger();

            Files.walk(targetDir).filter(path -> !path.equals(targetDir) && path.toFile().isDirectory()).forEach(path -> {
                dirCount.incrementAndGet();
                assertThatCode(() -> {
                    assertThat(Files.getLastModifiedTime(path)).isEqualTo(RsyncWriter.INTERNAL_DIRECTORY_LAST_MODIFIED_TIME);
                    assertThat(Files.getPosixFilePermissions(path)).isEqualTo(RsyncWriter.DIRECTORY_PERMISSIONS);
                })
                        .doesNotThrowAnyException();
            });

            assertThat(dirCount.get()).isGreaterThan(5);
        });
    }

    @Test
    public void testIgnoreBadUrls(@TempDir Path tmpPath) throws Exception {
        withRsyncWriter(tmpPath, rsyncWriter -> {
            var o1 = new RpkiObject(URI.create("rsync://bla.net/path1/a.cer"), someBytes(), Instant.now());
            var o2 = new RpkiObject(URI.create("rsync://bla.net/path1/../../PATH_INJECTION.txt"), someBytes(), Instant.now());
            var o3 = new RpkiObject(URI.create("rsync://bla.net/path1/path2/../NOT_REALLY_PATH_INJECTION.txt"), someBytes(), Instant.now());
            rsyncWriter.writeObjects(Arrays.asList(o1, o2, o3), Instant.now());
            var root = rsyncWriter.getConfig().rsyncPath();
            checkFile(root.resolve("published/bla.net/path1/a.cer"), o1.bytes());
            assertThat(root.resolve( "published/PATH_INJECTION.txt").toFile().exists()).isFalse();
            checkFile(root.resolve( "published/bla.net/path1/NOT_REALLY_PATH_INJECTION.txt"), o3.bytes());
        });
    }

    static Path writeSomeObjects(RsyncWriter writer, Instant then) throws IOException {
        return writer.writeObjects(IntStream.range(0, 10).mapToObj(i ->
            new RpkiObject(URI.create("rsync://bla.net/path1/" + i + ".cer"), someBytes(), Instant.now())
        ).toList(), then);
    }

    @Test
    public void testDoNotDeleteLinkedDirAndLinksMostRecentlyWritten(@TempDir Path tmpPath) throws Exception {
        withRsyncWriter(
                tmpPath,
                config -> config.withTargetDirectoryRetentionPeriodMs(100).withTargetDirectoryRetentionCopiesCount(0),
                rsyncWriter -> {
                    Instant t0 = Instant.now();

                    var path1 = writeSomeObjects(rsyncWriter, t0);
                    var path2 = writeSomeObjects(rsyncWriter, t0.plusMillis(50));
                    Files.setLastModifiedTime(path1, FileTime.from(Instant.now().plus(1, ChronoUnit.DAYS)));
                    var path3 = writeSomeObjects(rsyncWriter, t0.plusSeconds(60));

                    // path 1 has the mangled timestamp
                    assertThat(path1.toFile()).exists();
                    // path 2 is too old
                    assertThat(path2.toFile()).doesNotExist();
                    assertThat(path3.toFile()).exists();

                    // published path points MUST exist and MUST link to the last write (not last timestamp).
                    //
                    // A race condition that we check for can not be triggered easily.
                    // idea: time of check - time of use gap: continuously update the timestamp of path3 to be before cutoff
                    assertThat(tmpPath.resolve("published").toRealPath().toFile()).exists();
                });
    }

    @Test
    public void testRemoveOldDirectoriesWhenTheyAreOld(@TempDir Path tmpPath) throws Exception {
        withRsyncWriter(
            tmpPath,
            // make it ridiculous so that we clean up everything except for the last directory
            config -> config.withTargetDirectoryRetentionPeriodMs(601000).withTargetDirectoryRetentionCopiesCount(0),
            rsyncWriter -> {
                var t0 = Instant.now();

                var path1 = writeSomeObjects(rsyncWriter, t0);
                var path2 = writeSomeObjects(rsyncWriter, t0.plusSeconds(600));
                // base case: Both are present because they are not too old, one is right at the edge of cutoff.
                assertThat(path1.toFile()).exists();
                assertThat(path2.toFile()).exists();
                assertThat(tmpPath.resolve("published").toRealPath()).isEqualTo(path2.toRealPath());

                var path3 = writeSomeObjects(rsyncWriter, t0.plusSeconds(1800));
                // inductive case: it cleans the older dirs
                assertThat(path1.toFile()).doesNotExist();
                assertThat(path2.toFile()).doesNotExist();
                assertThat(path3.toFile()).exists();

                // published path points to most recent copy
                assertThat(tmpPath.resolve("published").toRealPath()).isEqualTo(path3.toRealPath());
            });
    }

    @Test
    public void testRemoveOldDirectoriesButKeepSomeNumberOfThem(@TempDir Path tmpDir) throws Exception {
        withRsyncWriter(
            tmpDir,
            // make it ridiculous so that we clean up everything except for the last directory
            config -> config.withTargetDirectoryRetentionPeriodMs(300000).withTargetDirectoryRetentionCopiesCount(2),
            rsyncWriter -> {
                var t0 = Instant.now();

                var path1 = writeSomeObjects(rsyncWriter, t0);
                var path2 = writeSomeObjects(rsyncWriter, t0.plusSeconds(600));
                // Copies are too old, but kept because two are kept.
                assertThat(path1.toFile()).exists();
                assertThat(path2.toFile()).exists();

                var path3 = writeSomeObjects(rsyncWriter, t0.plusSeconds(900));
                // It should delete the oldest directory
                assertThat(path1.toFile()).doesNotExist();
                assertThat(path2.toFile()).exists();
                assertThat(path3.toFile()).exists();

                // published path points to most recent copy
                assertThat(tmpDir.resolve("published").toRealPath()).isEqualTo(path3.toRealPath());
            });
    }

    private static void checkFile(Path path, byte[] bytes) throws IOException {
        final byte[] readBackBytes = Files.readAllBytes(path);
        assertThat(path.toFile().exists()).isTrue();
        assertThat(readBackBytes).isEqualTo(bytes);
    }

    static void withRsyncWriter(Path tmpPath, Function<Config, Config> transformConfig, ThrowingConsumer<RsyncWriter> actualTest) throws Exception {
        final Config config = defaultConfig().withRsyncPath(tmpPath);
        actualTest.accept(new RsyncWriter(transformConfig.apply(config)));
    }

    static void withRsyncWriter(Path tmpPath, ThrowingConsumer<RsyncWriter> actualTest) throws Exception {
        withRsyncWriter(tmpPath, Function.identity(), actualTest);
    }

    private static final Random random = new Random();

    private static byte[] someBytes() {
        var length = random.nextInt(1000, 2000);
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }
}