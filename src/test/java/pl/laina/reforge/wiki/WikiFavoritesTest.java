package pl.laina.reforge.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WikiFavoritesTest {
    @TempDir Path folder;

    @Test
    void favoritesSurviveRestartAndStayPrivateToEachPlayer() throws Exception {
        UUID player = UUID.randomUUID();
        try (var store = new WikiFavorites(folder)) {
            assertEquals(WikiFavorites.Change.ADDED, store.toggle(player, "Kryształ_Empirii").get(3, TimeUnit.SECONDS));
            assertTrue(store.list(UUID.randomUUID()).get(3, TimeUnit.SECONDS).isEmpty());
        }
        try (var restored = new WikiFavorites(folder)) {
            assertEquals(List.of("Kryształ Empirii"), restored.list(player).get(3, TimeUnit.SECONDS));
            assertEquals(WikiFavorites.Change.REMOVED, restored.toggle(player, "kryształ empirii").get(3, TimeUnit.SECONDS));
            assertTrue(restored.list(player).get(3, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test
    void queuedChangesCannotOverwriteEachOther() throws Exception {
        UUID player = UUID.randomUUID();
        try (var store = new WikiFavorites(folder)) {
            List<CompletableFuture<WikiFavorites.Change>> pending = new ArrayList<>();
            for (int i = 0; i < 30; i++) pending.add(store.toggle(player, "Przedmiot " + i));
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertEquals(30, store.list(player).get(3, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void fullListStillAllowsRemovalAndDoesNotAddExtraItems() throws Exception {
        UUID player = UUID.randomUUID();
        try (var store = new WikiFavorites(folder)) {
            for (int i = 0; i < WikiFavorites.LIMIT; i++) store.toggle(player, "Karta " + i).get(3, TimeUnit.SECONDS);
            assertEquals(WikiFavorites.Change.LIMIT_REACHED, store.toggle(player, "Nadmiar").get(3, TimeUnit.SECONDS));
            assertEquals(WikiFavorites.Change.REMOVED, store.toggle(player, "Karta 0").get(3, TimeUnit.SECONDS));
            assertEquals(WikiFavorites.Change.ADDED, store.toggle(player, "Nadmiar").get(3, TimeUnit.SECONDS));
            assertEquals(WikiFavorites.LIMIT, store.list(player).get(3, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void unreadableExistingFileIsNeverSilentlyReplaced() throws Exception {
        UUID player = UUID.randomUUID();
        Path file = folder.resolve(player + ".txt");
        Files.writeString(file, "!damaged-data!");
        try (var store = new WikiFavorites(folder)) {
            assertThrows(ExecutionException.class, () -> store.toggle(player, "Miecz").get(3, TimeUnit.SECONDS));
            assertEquals("!damaged-data!", Files.readString(file));
        }
    }
}
