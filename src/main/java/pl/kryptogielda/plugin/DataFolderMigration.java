package pl.kryptogielda.plugin;

import java.io.IOException;
import java.nio.file.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Versions up to 1.0.1 stored their data in plugins/KryptoGielda/. Since 1.0.2 the plugin is called WebMarket
 * and uses plugins/WebMarket/. On first start the old folder is copied over (config, messages, database,
 * custom web files) and renamed so that nothing is lost and the migration runs only once.
 */
final class DataFolderMigration {
    static final String OLD_NAME = "KryptoGielda";
    static final String DONE_SUFFIX = ".migrated-to-WebMarket";

    private DataFolderMigration() {}

    /** @return true when files were migrated. */
    static boolean migrate(Path oldDir, Path newDir, Logger log) {
        try {
            if (!Files.isDirectory(oldDir)) return false;
            if (Files.exists(newDir.resolve("config.yml"))) return false; // new folder already in use
            Files.createDirectories(newDir);
            try (Stream<Path> s = Files.walk(oldDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    Path target = newDir.resolve(oldDir.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Path done = oldDir.resolveSibling(OLD_NAME + DONE_SUFFIX);
            int i = 1;
            while (Files.exists(done)) done = oldDir.resolveSibling(OLD_NAME + DONE_SUFFIX + "-" + (i++));
            Files.move(oldDir, done);
            log.info("Migrated data from plugins/" + OLD_NAME + " to plugins/" + newDir.getFileName()
                    + ". The old folder was renamed to " + done.getFileName() + " (you can delete it).");
            return true;
        } catch (IOException e) {
            log.warning("Could not migrate the old plugins/" + OLD_NAME + " folder: " + e.getMessage()
                    + ". Copy its files to plugins/" + newDir.getFileName() + " manually if you need your old settings.");
            return false;
        }
    }
}
