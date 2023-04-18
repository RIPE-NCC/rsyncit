package net.ripe.rpki.rsyncit.rsync;

import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.rsyncit.config.Config;
import net.ripe.rpki.rsyncit.rrdp.RpkiObject;
import org.apache.tomcat.util.http.fileupload.FileUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@Slf4j
public class RsyncWriter {

    // This pattern needs to match both published directory names (`published-2021-04-26T09:57:59.034Z`) and temporary
    // directory names (`tmp-2021-04-26T10:09:06.023Z-4352054854289820810`).
    public static final Pattern PUBLICATION_DIRECTORY_PATTERN = Pattern.compile("^(tmp|published)-\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+(-\\d+)?$");

    private final ForkJoinPool fileWriterPool = new ForkJoinPool(2 * Runtime.getRuntime().availableProcessors());

    private final Config config;

    public RsyncWriter(Config config) {
        this.config = config;
    }

    public Path writeObjects(List<RpkiObject> objects) {
        try {
            final Instant now = Instant.now();
            var baseDirectory = Paths.get(config.getRsyncPath());
            final Path targetDirectory = writeObjectToNewDirectory(objects, now);
            atomicallyReplacePublishedSymlink(Paths.get(config.getRsyncPath()), targetDirectory);
            cleanupOldTargetDirectories(now, baseDirectory);
            return targetDirectory;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Path writeObjectToNewDirectory(List<RpkiObject> objects, Instant now) throws IOException {
        // Since we don't know anything about URLs of the objects
        // they are grouped by the host name of the URL
        final Map<String, List<RpkiObject>> groupedByHost =
            objects.stream().collect(Collectors.groupingBy(o -> o.getUrl().getHost()));

        final String formattedNow = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("UTC")).format(now);

        final Path targetDirectory = Paths.get(config.getRsyncPath()).resolve("published-" + formattedNow);
        final Path temporaryDirectory = Files.createTempDirectory(Paths.get(config.getRsyncPath()), "tmp-" + formattedNow + "-");
        try {
            groupedByHost.forEach((hostName, os) -> {
                // creeate a directory per hostname (in realistic cases there will be just one)
                var hostDir = temporaryDirectory.resolve(hostName);
                try {
                    Files.createDirectories(hostDir);
                } catch (IOException e) {
                    log.error("Could not create {}", hostDir);
                }

                // create directories in "shortest first" order
                os.stream()
                    .map(o ->
                        // remove the filename, i.e. /foo/bar/object.cer -> /foo/bar
                        Paths.get(relativePath(o.getUrl().getPath())).getParent()
                    )
                    .sorted()
                    .distinct()
                    .forEach(dir -> {
                        try {
                            Files.createDirectories(temporaryDirectory.resolve(hostName).resolve(dir));
                        } catch (IOException ex) {
                            log.error("Could not create directory {}", dir, ex);
                        }
                    });

                fileWriterPool.submit(() -> os.stream()
                    .parallel()
                    .forEach(o -> {
                        var path = Paths.get(relativePath(o.getUrl().getPath()));
                        var fullPath = temporaryDirectory.resolve(hostName).resolve(path);
                        try {
                            Files.write(fullPath, o.getBytes());
                            // rsync relies on the correct timestamp for fast synchronization
                            Files.setLastModifiedTime(fullPath, FileTime.from(o.getCreatedAt()));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                ).join();
            });

            // Directory write is fully complete, rename temporary to target directory name
            Files.setLastModifiedTime(temporaryDirectory, FileTime.from(now));
            Files.setPosixFilePermissions(temporaryDirectory, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.move(temporaryDirectory, targetDirectory, ATOMIC_MOVE);

            return targetDirectory;

        } finally {
            try {
                FileUtils.deleteDirectory(temporaryDirectory.toFile());
            } catch (IOException ignored) {
            }
        }
    }

    private void atomicallyReplacePublishedSymlink(Path baseDirectory, Path targetDirectory) throws IOException {
        Path targetSymlink = baseDirectory.resolve("published");

        // Atomically replace the symlink to point to the new target directory. We cannot
        // atomically replace a symlink except by first creating a temporary one and then
        // renaming that to the final symlink, which will atomically replace it.
        // See https://unix.stackexchange.com/a/6786
        Path temporarySymlink = Files.createTempFile(baseDirectory, "published-", ".tmp");

        // Deleting the temporary file is needed here, but it may result in a race condition
        // again with another process. Hopefully this is fine, since only one process will
        // succeed in creating and renaming the symlink.
        Files.deleteIfExists(temporarySymlink);

        Path symlink = Files.createSymbolicLink(temporarySymlink, targetDirectory.getFileName());
        Files.move(symlink, targetSymlink, ATOMIC_MOVE, REPLACE_EXISTING);
    }

    private void cleanupOldTargetDirectories(Instant now, Path baseDirectory) throws IOException {
        long cutoff = now.toEpochMilli() - config.getTargetDirectoryRetentionPeriodMs();

        try (
            Stream<Path> oldDirectories = Files.list(baseDirectory)
                .filter(path -> PUBLICATION_DIRECTORY_PATTERN.matcher(path.getFileName().toString()).matches())
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(this::getLastModifiedTime).reversed())
                .skip(config.getTargetDirectoryRetentionCopiesCount())
                .filter((directory) -> getLastModifiedTime(directory).toMillis() < cutoff)
        ) {
            fileWriterPool.submit(() -> oldDirectories.parallel().forEach(directory -> {
                log.info("Removing old publication directory {}", directory);
                try {
                    FileUtils.deleteDirectory(directory.toFile());
                } catch (IOException e) {
                    log.warn("Removing old publication directory {} failed", directory, e);
                }
            })).join();
        }
    }

    private FileTime getLastModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String relativePath(final String path) {
        if (path.startsWith("/")) {
            return path.substring(1);
        }
        return path;
    }
}
