package pl.kryptogielda.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DataFolderMigrationTest {
    private final Logger log = Logger.getLogger("test");

    @Test
    void copiesOldFolderAndRenamesIt(@TempDir Path plugins) throws Exception {
        Path old = Files.createDirectories(plugins.resolve("KryptoGielda"));
        Files.writeString(old.resolve("config.yml"), "language: en\n");
        Files.createDirectories(old.resolve("web"));
        Files.writeString(old.resolve("web/app.js"), "x");
        Files.writeString(old.resolve("gielda.db"), "db");
        Path neu = plugins.resolve("WebMarket");

        assertTrue(DataFolderMigration.migrate(old, neu, log));
        assertEquals("language: en\n", Files.readString(neu.resolve("config.yml")));
        assertEquals("x", Files.readString(neu.resolve("web/app.js")));
        assertEquals("db", Files.readString(neu.resolve("gielda.db")));
        assertFalse(Files.exists(old));
        assertTrue(Files.isDirectory(plugins.resolve("KryptoGielda" + DataFolderMigration.DONE_SUFFIX)));
        assertFalse(DataFolderMigration.migrate(old, neu, log)); // runs once
    }

    @Test
    void doesNothingWithoutOldFolderOrWhenNewIsInUse(@TempDir Path plugins) throws Exception {
        Path neu = plugins.resolve("WebMarket");
        assertFalse(DataFolderMigration.migrate(plugins.resolve("KryptoGielda"), neu, log));
        assertFalse(Files.exists(neu));

        Path old = Files.createDirectories(plugins.resolve("KryptoGielda"));
        Files.writeString(old.resolve("config.yml"), "old");
        Files.createDirectories(neu);
        Files.writeString(neu.resolve("config.yml"), "new");
        assertFalse(DataFolderMigration.migrate(old, neu, log));
        assertEquals("new", Files.readString(neu.resolve("config.yml")));
        assertTrue(Files.exists(old.resolve("config.yml")));
    }
}
