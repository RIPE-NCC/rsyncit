package net.ripe.rpki.rsyncit.rsync;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.rsyncit.config.Config;
import net.ripe.rpki.rsyncit.rrdp.RpkiObject;
import org.apache.tomcat.util.http.fileupload.FileUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
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

    // Internal directories (used to store all the RPKI objects per CA, etc) are set to this modification time so
    // that rsync does not see the directories as modified every time we fully write the repository. The RPKI objects
    // have their creation time as last modified time, so rsync will copy these as needed.
    public static final FileTime INTERNAL_DIRECTORY_LAST_MODIFIED_TIME = FileTime.fromMillis(0);
    public static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-r--r--");
    public static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwxr-xr-x");


    private final ForkJoinPool fileWriterPool = new ForkJoinPool(2 * Runtime.getRuntime().availableProcessors());

    @Getter
    private final Config config;

    public RsyncWriter(Config config) {
        this.config = config;
    }

    public Path writeObjects(List<RpkiObject> objects) {
        try {
            final Instant now = Instant.now();
            final Path targetDirectory = writeObjectToNewDirectory(objects, now);
            atomicallyReplacePublishedSymlink(config.rsyncPath(), targetDirectory);
            cleanupOldTargetDirectories(now, config.rsyncPath());
            return targetDirectory;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    record ObjectTarget(Path targetPath, byte[] content, FileTime modificationTime){}

    private Path writeObjectToNewDirectory(List<RpkiObject> objects, Instant now) throws IOException {
        // Since we don't know anything about URLs of the objects
        // they are grouped by the host name of the URL
        final Map<String, List<RpkiObject>> groupedByHost =
            objects.stream().collect(Collectors.groupingBy(o -> o.url().getHost()));

        final Path temporaryDirectory = Files.createTempDirectory(config.rsyncPath(), "rsync-writer-tmp");
        try {
            groupedByHost.forEach((hostName, os) -> {
                // create a directory per hostname (in realistic cases there will be just one)
                var hostDirectory = temporaryDirectory.resolve(hostName);
                var hostUrl = URI.create("rsync://" + hostName);

                // Gather the relative paths of files with legal names
                var writableContent = filterOutBadUrls(hostDirectory, os).stream()
                        .map(rpkiObject -> {
                            var relativeUriPath = hostUrl.relativize(rpkiObject.url()).getPath();
                            var targetPath = hostDirectory.resolve(relativeUriPath).normalize();

                            assert targetPath.normalize().startsWith(hostDirectory.normalize());

                            return new ObjectTarget(targetPath, rpkiObject.bytes(), FileTime.from(rpkiObject.modificationTime()));
                        }).toList();

                // Create directories
                // Since createDirectories is idempotent, we do not worry about the order in which it is actually
                // executed. However, we do want a stable sort for .distinct()
                var targetDirectories = writableContent.stream().map(o -> o.targetPath.getParent())
                        .sorted(Comparator.comparing(Path::getNameCount).thenComparing(Path::toString))
                        .distinct().toList();

                var t0 = System.currentTimeMillis();
                fileWriterPool.submit(() -> targetDirectories.parallelStream()
                        .forEach(dir -> {
                            try {
                                Files.createDirectories(dir);
                                Files.setPosixFilePermissions(dir, DIRECTORY_PERMISSIONS);
                                Files.setLastModifiedTime(dir, INTERNAL_DIRECTORY_LAST_MODIFIED_TIME);
                            } catch (IOException e) {
                                log.error("Could not create directory {}", dir, e);
                                throw new UncheckedIOException(e);
                            }
                        })
                ).join();

                var t1 = System.currentTimeMillis();
                fileWriterPool.submit(() -> writableContent.parallelStream().forEach(content -> {
                    try {
                        Files.write(content.targetPath, content.content);
                        Files.setPosixFilePermissions(content.targetPath, FILE_PERMISSIONS);
                        Files.setLastModifiedTime(content.targetPath, content.modificationTime);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })).join();

                log.info("Wrote {} directories ({} ms) and {} files ({} ms) for host {}",
                        targetDirectories.size(), t1 - t0,
                        writableContent.size(), System.currentTimeMillis() - t1,
                        hostName);
            });

            // Calculate target directory after writing phase, to be sure it is not used beforehand.
            final Path targetDirectory = generatePublicationDirectoryPath(config.rsyncPath(), now);

            // Directory write is fully complete, rename temporary to target directory name
            Files.setLastModifiedTime(temporaryDirectory, FileTime.from(now));
            Files.setPosixFilePermissions(temporaryDirectory, DIRECTORY_PERMISSIONS);
            Files.move(temporaryDirectory, targetDirectory, ATOMIC_MOVE);

            return targetDirectory;
        } finally {
            try {
                FileUtils.deleteDirectory(temporaryDirectory.toFile());
            } catch (IOException ignored) {
            }
        }
    }

    static Path generatePublicationDirectoryPath(Path baseDir, Instant now) {
        var timeSegment = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("UTC")).format(now);

        return baseDir.resolve("published-" + timeSegment);
    }

    static List<RpkiObject> filterOutBadUrls(Path hostBasedPath, Collection<RpkiObject> objects) {
        final String normalizedHostPath = hostBasedPath.normalize().toString();
        return objects.stream().flatMap(object -> {
            var objectRelativePath = Paths.get(relativePath(object.url().getPath()));
            // Check that the resulting path of the object stays within `hostBasedPath`
            // to prevent URLs like rsync://bla.net/path/../../../../../PATH_INJECTION.txt
            // writing data outside the controlled path.
            final String normalizedPath = hostBasedPath.resolve(objectRelativePath).normalize().toString();
            if (normalizedPath.startsWith(normalizedHostPath)) {
                return Stream.of(object);
            } else {
                log.error("The object with url {} was skipped.", object.url());
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

        // resolve the published symlink - because we definitely want to keep that copy.
        // TODO: published dir should be built without string concat, but this is where we are ¯\_(ツ)_/¯
        var actualPublishedDir = config.rsyncPath().resolve("published").toRealPath();

        try (
            Stream<Path> oldDirectories = Files.list(baseDirectory)
                .filter(path -> PUBLICATION_DIRECTORY_PATTERN.matcher(path.getFileName().toString()).matches())
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(this::getLastModifiedTime).reversed())
                .skip(config.targetDirectoryRetentionCopiesCount())
                .filter((directory) -> getLastModifiedTime(directory).toMillis() < cutoff)
                .filter(dir -> {
                    try {
                        return !dir.toRealPath().equals(actualPublishedDir);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
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
