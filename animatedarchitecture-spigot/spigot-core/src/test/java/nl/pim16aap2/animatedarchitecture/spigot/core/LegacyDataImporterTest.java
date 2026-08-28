package nl.pim16aap2.animatedarchitecture.spigot.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class LegacyDataImporterTest
{
    @TempDir
    private Path pluginsDirectory;

    @Test
    void importsLegacyDataDirectory()
        throws IOException
    {
        final Path legacyDirectory = Files.createDirectory(pluginsDirectory.resolve("AnimatedArchitecture"));
        final Path databaseDirectory = Files.createDirectory(legacyDirectory.resolve("database"));
        Files.writeString(legacyDirectory.resolve("config.yml"), "locale: en_US");
        Files.writeString(databaseDirectory.resolve("animatedarchitecture.db"), "database-content");

        final Path targetDirectory = pluginsDirectory.resolve("RCDoors");
        final LegacyDataImporter.ImportResult result = LegacyDataImporter.importFromSibling(targetDirectory);

        Assertions.assertEquals(LegacyDataImporter.ImportResult.IMPORTED, result);
        Assertions.assertEquals("locale: en_US", Files.readString(targetDirectory.resolve("config.yml")));
        Assertions.assertEquals(
            "database-content",
            Files.readString(targetDirectory.resolve("database/animatedarchitecture.db")));
        Assertions.assertTrue(Files.exists(legacyDirectory.resolve("config.yml")));
        Assertions.assertTrue(Files.exists(targetDirectory.resolve(LegacyDataImporter.IMPORT_MARKER_FILE_NAME)));
    }

    @Test
    void mergesMissingDataWithoutOverwritingExistingFiles()
        throws IOException
    {
        final Path legacyDirectory = Files.createDirectory(pluginsDirectory.resolve("AnimatedArchitecture"));
        Files.writeString(legacyDirectory.resolve("config.yml"), "legacy-config");
        Files.writeString(legacyDirectory.resolve("legacy-only.txt"), "legacy-data");
        final Path legacyDatabaseDirectory = Files.createDirectory(legacyDirectory.resolve("database"));
        Files.writeString(legacyDatabaseDirectory.resolve("structures.db"), "legacy-database");

        final Path targetDirectory = Files.createDirectory(pluginsDirectory.resolve("RCDoors"));
        Files.writeString(targetDirectory.resolve("config.yml"), "current-config");
        Files.createDirectory(targetDirectory.resolve("database"));

        final LegacyDataImporter.ImportResult result =
            LegacyDataImporter.importDirectory(legacyDirectory, targetDirectory);

        Assertions.assertEquals(LegacyDataImporter.ImportResult.IMPORTED, result);
        Assertions.assertEquals("current-config", Files.readString(targetDirectory.resolve("config.yml")));
        Assertions.assertEquals("legacy-data", Files.readString(targetDirectory.resolve("legacy-only.txt")));
        Assertions.assertEquals(
            "legacy-database",
            Files.readString(targetDirectory.resolve("database/structures.db")));
        Assertions.assertTrue(Files.exists(targetDirectory.resolve(LegacyDataImporter.IMPORT_MARKER_FILE_NAME)));
    }

    @Test
    void repeatedImportIsIdempotent()
        throws IOException
    {
        final Path legacyDirectory = Files.createDirectory(pluginsDirectory.resolve("AnimatedArchitecture"));
        Files.writeString(legacyDirectory.resolve("config.yml"), "legacy-config");
        final Path targetDirectory = pluginsDirectory.resolve("RCDoors");

        final LegacyDataImporter.ImportResult firstResult =
            LegacyDataImporter.importDirectory(legacyDirectory, targetDirectory);
        Files.writeString(targetDirectory.resolve("config.yml"), "current-config");
        Files.writeString(legacyDirectory.resolve("config.yml"), "changed-legacy-config");
        Files.writeString(legacyDirectory.resolve("late-legacy-file.txt"), "late-data");
        final LegacyDataImporter.ImportResult secondResult =
            LegacyDataImporter.importDirectory(legacyDirectory, targetDirectory);

        Assertions.assertEquals(LegacyDataImporter.ImportResult.IMPORTED, firstResult);
        Assertions.assertEquals(LegacyDataImporter.ImportResult.ALREADY_IMPORTED, secondResult);
        Assertions.assertEquals("current-config", Files.readString(targetDirectory.resolve("config.yml")));
        Assertions.assertFalse(Files.exists(targetDirectory.resolve("late-legacy-file.txt")));
    }
}
