package net.ripe.rpki.rsyncit.rsync;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.rsyncit.config.Config;
import net.ripe.rpki.rsyncit.rrdp.RpkiObject;
import org.apache.tomcat.util.http.fileupload.FileUtils;

import java.io.File;
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
import java.util.Collection;
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

    @Getter
    private final Config config;

    public RsyncWriter(Config config) {
        this.config = config;
    }

    public Path writeObjects(List<RpkiObject> objects) {
        try {
            final Instant now = Instant.now();
            var baseDirectory = Paths.get(config.rsyncPath());
            final Path targetDirectory = writeObjectToNewDirectory(objects, now);
            atomicallyReplacePublishedSymlink(Paths.get(config.rsyncPath()), targetDirectory);
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
            objects.stream().collect(Collectors.groupingBy(o -> o.url().getHost()));

        final String formattedNow = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("UTC")).format(now);

        final Path targetDirectory = Paths.get(config.rsyncPath()).resolve("published-" + formattedNow);
        final Path temporaryDirectory = Files.createTempDirectory(Paths.get(config.rsyncPath()), "tmp-" + formattedNow + "-");
        try {
            groupedByHost.forEach((hostName, os) -> {
                // create a directory per hostname (in realistic cases there will be just one)
                var hostBasedPath = temporaryDirectory.resolve(hostName);
                try {
                    Files.createDirectories(hostBasedPath);
                } catch (IOException e) {
                    log.error("Could not create {}", hostBasedPath);
                }

                // Filter out objects with potentially insecure URLs
                var wellBehavingObjects = filterOutBadUrls(hostBasedPath, os);

                // Create directories in "shortest first" order.
                // Use canonical path to avoid potential troubles with relative ".." paths
                wellBehavingObjects
                    .stream()
                    .flatMap(o -> {
                        // remove the filename, i.e. /foo/bar/object.cer -> /foo/bar
                        var objectParentDir = Paths.get(relativePath(o.url().getPath())).getParent();
                        try {
                            return Stream.of(hostBasedPath.resolve(objectParentDir).toFile().getCanonicalFile().toPath());
                        } catch (IOException e) {
                            log.error("Could not find a parent directory for the object {}", o.url(), e);
                            return Stream.empty();
                        }
                    })
                    .sorted()
                    .distinct()
                    .forEach(dir -> {
                        try {
                            Files.createDirectories(dir);
                        } catch (IOException ex) {
                            log.error("Could not create directory {}", dir, ex);
                        }
                    });

                fileWriterPool.submit(() -> wellBehavingObjects.stream()
                    .parallel()
                    .forEach(o -> {
                        var path = Paths.get(relativePath(o.url().getPath()));
                        try {
                            var canonicalFullPath = hostBasedPath.resolve(path).toFile().getCanonicalFile().toPath();
                            Files.write(canonicalFullPath, o.bytes());
                            // rsync relies on the correct timestamp for fast synchronization
                            Files.setLastModifiedTime(canonicalFullPath, FileTime.from(o.modificationTime()));
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

    static List<RpkiObject> filterOutBadUrls(Path hostBasedPath, Collection<RpkiObject> objects) {
        final String canonicalHostPath;
        try {
            canonicalHostPath = hostBasedPath.toFile().getCanonicalPath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return objects.stream().flatMap(object -> {
            var objectRelativePath = Paths.get(relativePath(object.url().getPath()));
            try {
                // Check that the resulting path of the object stays within `hostBasedPath`
                // to prevent URLs like rsync://bla.net/path/../../../../../PATH_INJECTION.txt
                // writing data outside of the controlled path.
                final String canonicalPath = hostBasedPath.resolve(objectRelativePath).toFile().getCanonicalPath();
                if (canonicalPath.startsWith(canonicalHostPath)) {
                    return Stream.of(object);
                } else {
                    log.error("The object with url {} was skipped.", object.url());
                }
            } catch (IOException e) {
                log.error("The object with url {} was skipped due to the error.", object.url(), e);
            }
            return Stream.empty();
        }).collect(Collectors.toList());
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

    void cleanupOldTargetDirectories(Instant now, Path baseDirectory) throws IOException {
        long cutoff = now.toEpochMilli() - config.targetDirectoryRetentionPeriodMs();

        try (
            Stream<Path> oldDirectories = Files.list(baseDirectory)
                .filter(path -> PUBLICATION_DIRECTORY_PATTERN.matcher(path.getFileName().toString()).matches())
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(this::getLastModifiedTime).reversed())
                .skip(config.targetDirectoryRetentionCopiesCount())
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

    private static String relativePath(final String path) {
        if (path.startsWith("/")) {
            return path.substring(1);
        }
        return path;
    }
}
