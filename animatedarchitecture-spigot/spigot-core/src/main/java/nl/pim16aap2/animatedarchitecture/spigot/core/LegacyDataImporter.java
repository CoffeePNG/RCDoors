package nl.pim16aap2.animatedarchitecture.spigot.core;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Imports the data directory used by the legacy plugin name.
 */
final class LegacyDataImporter
{
    // This must remain the old plugin directory name so existing installations can be imported.
    private static final String LEGACY_DIRECTORY_NAME = "AnimatedArchitecture";
    static final String IMPORT_MARKER_FILE_NAME = ".legacy-data-imported";

    private LegacyDataImporter()
    {
    }

    /**
     * Imports the legacy data directory located beside the current plugin data directory.
     *
     * @param targetDirectory
     *     The current plugin data directory.
     * @return The outcome of the import attempt.
     * @throws IOException
     *     If the legacy data exists but cannot be imported safely.
     */
    static ImportResult importFromSibling(Path targetDirectory)
        throws IOException
    {
        final Path normalizedTarget = Objects.requireNonNull(targetDirectory, "targetDirectory")
            .toAbsolutePath()
            .normalize();
        final Path parent = normalizedTarget.getParent();
        if (parent == null)
            throw new IOException("The plugin data directory does not have a parent: " + normalizedTarget);

        return importDirectory(parent.resolve(LEGACY_DIRECTORY_NAME), normalizedTarget);
    }

    /**
     * Copies a legacy plugin data directory into a new data directory exactly once.
     * <p>
     * Existing files and directories in the target are preserved. Missing legacy files and directories are merged into
     * the target, and a marker is created only after the complete directory walk succeeds.
     *
     * @param legacyDirectory
     *     The legacy plugin data directory.
     * @param targetDirectory
     *     The new plugin data directory.
     * @return The outcome of the import attempt.
     * @throws IOException
     *     If the legacy data exists but cannot be imported safely.
     */
    static ImportResult importDirectory(Path legacyDirectory, Path targetDirectory)
        throws IOException
    {
        final Path source = Objects.requireNonNull(legacyDirectory, "legacyDirectory").toAbsolutePath().normalize();
        final Path target = Objects.requireNonNull(targetDirectory, "targetDirectory").toAbsolutePath().normalize();

        if (source.equals(target))
            throw new IOException("The legacy and target data directories must be different: " + source);

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The target data path is not a directory: " + target);

        final Path importMarker = target.resolve(IMPORT_MARKER_FILE_NAME);
        if (Files.exists(importMarker, LinkOption.NOFOLLOW_LINKS))
            return ImportResult.ALREADY_IMPORTED;

        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS))
            return ImportResult.LEGACY_DIRECTORY_NOT_FOUND;

        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The legacy data path is not a directory: " + source);

        Files.createDirectories(target);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The target data path is not a directory: " + target);

        copyMissing(source, target);
        try
        {
            Files.createFile(importMarker);
        }
        catch (FileAlreadyExistsException ignored)
        {
            // Another import completed after the initial marker check.
        }
        return ImportResult.IMPORTED;
    }

    private static void copyMissing(Path source, Path target)
        throws IOException
    {
        Files.walkFileTree(source, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException
            {
                if (directory.equals(source.resolve(IMPORT_MARKER_FILE_NAME)))
                    return FileVisitResult.SKIP_SUBTREE;

                final Path relativePath = source.relativize(directory);
                if (relativePath.toString().isEmpty())
                    return FileVisitResult.CONTINUE;

                final Path targetPath = target.resolve(relativePath);
                if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS))
                    return Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS) ?
                        FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;

                try
                {
                    Files.createDirectory(targetPath);
                }
                catch (FileAlreadyExistsException ignored)
                {
                    if (!Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS))
                        return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException
            {
                if (file.equals(source.resolve(IMPORT_MARKER_FILE_NAME)))
                    return FileVisitResult.CONTINUE;

                if (!attributes.isRegularFile())
                    throw new IOException("The legacy data directory contains an unsupported file: " + file);

                final Path targetPath = target.resolve(source.relativize(file));
                if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS))
                    return FileVisitResult.CONTINUE;
                try
                {
                    Files.copy(file, targetPath);
                }
                catch (FileAlreadyExistsException ignored)
                {
                    // Preserve a target file created concurrently with the import.
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    enum ImportResult
    {
        IMPORTED,
        LEGACY_DIRECTORY_NOT_FOUND,
        ALREADY_IMPORTED
    }
}
