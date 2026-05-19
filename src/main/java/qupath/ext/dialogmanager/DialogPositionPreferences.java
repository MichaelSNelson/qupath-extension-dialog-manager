package qupath.ext.dialogmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Handles persistence of dialog positions.
 * <p>
 * Dialog positions are stored as JSON in a regular file under the QuPath user
 * directory by default ({@code <user-dir>/dialog-manager/positions.json}). The
 * file location is configurable so a core facility can point multiple machines
 * at one shared file. The main-window position and a few small flags continue
 * to live in {@link PathPrefs} because they are tiny and well under the Java
 * Preferences 8 KB per-key limit.
 * <p>
 * Earlier versions stored everything inside a single PathPrefs entry; that
 * entry hit the 8 KB cap once enough dialogs accumulated, the saveAll() path
 * silently pruned the oldest entries on every write, and frequently-opened
 * dialogs (e.g. Live Viewer) would lose their saved position one workflow at a
 * time. On the first run after the refactor, any data still in the legacy
 * PathPrefs entry is migrated into the file and the entry is cleared; the
 * extension shows a one-time notification afterward.
 */
public final class DialogPositionPreferences {

    private static final Logger logger = LoggerFactory.getLogger(DialogPositionPreferences.class);

    // Legacy PathPrefs key -- read once for migration, then cleared.
    private static final String LEGACY_PREF_KEY = "dialogManager.positions";
    private static final String MAIN_WINDOW_KEY = "dialogManager.mainWindow";
    private static final String MAIN_WINDOW_ENABLED_KEY = "dialogManager.mainWindow.enabled";
    private static final String STORAGE_FILE_KEY = "dialogManager.storageFile";
    private static final String MIGRATION_SHOWN_KEY = "dialogManager.migrationShown";

    private static final String DEFAULT_FILE_SUBDIR = "dialog-manager";
    private static final String DEFAULT_FILE_NAME = "positions.json";

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Window titles that should never be persisted.
     * These are internal QuPath dialogs that don't benefit from position management.
     */
    private static final Set<String> IGNORED_WINDOWS = Set.of("Quit QuPath");

    // Legacy PathPrefs property -- only read at migration time.
    private static ObjectProperty<String> legacyPositionsJsonProperty;

    // Main window position + screen fingerprint (separate from dialog positions)
    private static ObjectProperty<String> mainWindowJsonProperty;
    private static BooleanProperty mainWindowEnabledProperty;

    // Optional user override for the storage file path. Empty = default location.
    private static StringProperty storageFileProperty;

    // True once the one-time migration notification has been displayed.
    private static BooleanProperty migrationShownProperty;

    // Set to the migrated file path during initialize() if data was actually
    // moved out of legacy PathPrefs storage. Consumed by the extension to
    // decide whether to show the one-time migration notification.
    private static volatile Path pendingMigrationNotice;

    // Guards all file reads and writes so concurrent saves from FX + worker
    // threads can't interleave half-written JSON. The file is small enough
    // that a single lock is fine; we're not on the hot path.
    private static final Object FILE_LOCK = new Object();

    private DialogPositionPreferences() {
        // Utility class - no instantiation
    }

    /**
     * Initialize the preference layer. Must be called during extension installation.
     * <p>
     * On first call this also:
     * <ul>
     *   <li>Triggers a one-time migration from the legacy PathPrefs entry into
     *       the JSON file, if the file is missing and legacy data exists.</li>
     *   <li>Runs the existing fallback-entry cleanup so accumulated hash-code IDs
     *       don't bloat the new file.</li>
     * </ul>
     */
    public static void initialize() {
        if (legacyPositionsJsonProperty != null) {
            return;
        }
        legacyPositionsJsonProperty =
                PathPrefs.createPersistentPreference(LEGACY_PREF_KEY, "{}", s -> s, s -> s);
        mainWindowJsonProperty =
                PathPrefs.createPersistentPreference(MAIN_WINDOW_KEY, "", s -> s, s -> s);
        mainWindowEnabledProperty = PathPrefs.createPersistentPreference(MAIN_WINDOW_ENABLED_KEY, false);
        storageFileProperty = PathPrefs.createPersistentPreference(STORAGE_FILE_KEY, "");
        migrationShownProperty = PathPrefs.createPersistentPreference(MIGRATION_SHOWN_KEY, false);
        logger.debug("DialogPositionPreferences initialized");

        try {
            migrateLegacyEntryIfNeeded();
        } catch (Exception e) {
            logger.warn("Failed to migrate legacy dialog positions: {}", e.getMessage());
        }

        try {
            int removed = cleanupFallbackEntries();
            if (removed > 0) {
                logger.info("Automatically cleaned up {} garbage dialog position entries", removed);
            }
        } catch (Exception e) {
            logger.warn("Failed to cleanup fallback entries: {}", e.getMessage());
        }
    }

    // --- Storage location ---

    /**
     * Default storage file: {@code <QuPath user dir>/dialog-manager/positions.json}.
     */
    public static Path getDefaultStorageFile() {
        Path userDir = resolveQuPathUserDirectory();
        return userDir.resolve(DEFAULT_FILE_SUBDIR).resolve(DEFAULT_FILE_NAME);
    }

    /**
     * Resolved storage file: user-configured override if set, else the default.
     */
    public static Path getStorageFile() {
        initialize();
        String override = storageFileProperty.get();
        if (override != null && !override.isBlank()) {
            try {
                return Paths.get(override);
            } catch (Exception e) {
                logger.warn(
                        "Configured storage file path '{}' is invalid; falling back to default. ({})",
                        override,
                        e.getMessage());
            }
        }
        return getDefaultStorageFile();
    }

    /**
     * Change the storage file location.
     *
     * <p>If the target file does not yet exist, the current in-memory positions
     * are written there so the user keeps their data. If it does exist, the
     * contents at the new location are kept as-is -- this is the path that
     * lets a core facility point several workstations at one shared file.
     *
     * @param newPath new storage file, or {@code null} / empty to reset to default
     * @throws IOException if the new file's parent directory can't be created
     */
    public static void setStorageFile(Path newPath) throws IOException {
        initialize();
        Path current = getStorageFile();
        Map<String, DialogState> currentData = loadAll();

        Path resolved;
        if (newPath == null || newPath.toString().isBlank()) {
            storageFileProperty.set("");
            resolved = getDefaultStorageFile();
        } else {
            storageFileProperty.set(newPath.toAbsolutePath().toString());
            resolved = newPath;
        }

        if (resolved.equals(current)) {
            return;
        }

        if (!Files.exists(resolved)) {
            Files.createDirectories(resolved.getParent());
            writeJsonToFile(resolved, mapToJson(currentData));
            logger.info("Initialized new storage file with {} existing entries: {}", currentData.size(), resolved);
        } else {
            logger.info("Switched to existing storage file (kept current contents): {}", resolved);
        }
    }

    /**
     * Where QuPath stores user data. Tries {@code PathPrefs.userPathProperty()}
     * first so any user-set override applies, then falls back to
     * {@code PathPrefs.getDefaultQuPathUserDirectory()}, and finally to
     * {@code user.home} so we never throw out of here.
     */
    private static Path resolveQuPathUserDirectory() {
        try {
            String userPath = PathPrefs.userPathProperty().get();
            if (userPath != null && !userPath.isBlank()) {
                return Paths.get(userPath);
            }
        } catch (Exception e) {
            logger.debug("PathPrefs.userPathProperty() unavailable: {}", e.getMessage());
        }
        try {
            Path def = PathPrefs.getDefaultQuPathUserDirectory();
            if (def != null) {
                return def;
            }
        } catch (Exception e) {
            logger.debug("PathPrefs.getDefaultQuPathUserDirectory() unavailable: {}", e.getMessage());
        }
        return Paths.get(System.getProperty("user.home", "."));
    }

    // --- Migration ---

    /**
     * One-time migration from the legacy PathPrefs entry.
     * <p>
     * If the storage file is missing AND legacy data exists, write it to the
     * file, clear the legacy entry, and record that a notification is due so
     * the extension can show the user a one-time dialog explaining where the
     * data moved.
     */
    private static void migrateLegacyEntryIfNeeded() throws IOException {
        Path target = getStorageFile();
        if (Files.exists(target)) {
            return;
        }

        String legacyJson = legacyPositionsJsonProperty.get();
        boolean legacyHasData = legacyJson != null
                && !legacyJson.isBlank()
                && !legacyJson.equals("{}");
        if (!legacyHasData) {
            return;
        }

        Files.createDirectories(target.getParent());
        writeJsonToFile(target, legacyJson);
        logger.info("Migrated legacy dialog positions PathPrefs entry to file: {}", target);

        // Clear the legacy entry so it doesn't keep tripping the 8 KB cap
        // and so a future user moving the file doesn't pick up stale data.
        legacyPositionsJsonProperty.set("{}");
        // Force the extension to show its one-time notification.
        migrationShownProperty.set(false);
        pendingMigrationNotice = target;
    }

    /**
     * If migration just ran and the user has not yet been notified, returns
     * the path data was migrated to. Otherwise returns {@code null}. Callers
     * must invoke {@link #markMigrationNoticeShown()} after displaying the
     * notification to avoid showing it on every QuPath startup.
     */
    public static Path getPendingMigrationNotice() {
        initialize();
        if (pendingMigrationNotice != null) {
            return pendingMigrationNotice;
        }
        if (!migrationShownProperty.get() && Files.exists(getStorageFile())) {
            return getStorageFile();
        }
        return null;
    }

    /**
     * Suppress the migration notification on future startups.
     */
    public static void markMigrationNoticeShown() {
        initialize();
        migrationShownProperty.set(true);
        pendingMigrationNotice = null;
    }

    // --- Load / save ---

    /**
     * Load all saved dialog states from the storage file.
     *
     * @return Map of windowId to DialogState, never null
     */
    public static Map<String, DialogState> loadAll() {
        initialize();
        try {
            String json = readJsonFromFile(getStorageFile());
            if (json.isBlank() || json.equals("{}")) {
                return new HashMap<>();
            }

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Map<String, DialogState> result = new HashMap<>();
            for (var entry : root.entrySet()) {
                try {
                    DialogState state = jsonToState(entry.getKey(), entry.getValue().getAsJsonObject());
                    result.put(entry.getKey(), state);
                } catch (Exception e) {
                    logger.debug("Skipping invalid entry '{}': {}", entry.getKey(), e.getMessage());
                }
            }

            logger.debug("Loaded {} dialog positions from {}", result.size(), getStorageFile());
            return result;

        } catch (Exception e) {
            logger.warn("Failed to load dialog positions, returning empty map: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Save all dialog states to the storage file using compact JSON.
     * <p>
     * Filters out fallback hash-code based IDs (starting with "@") and any
     * window titles in the ignore list. There is no per-write size cap any
     * more -- the JSON file can grow as needed.
     *
     * @param states Map of windowId to DialogState
     */
    public static void saveAll(Map<String, DialogState> states) {
        initialize();
        try {
            String json = mapToJson(states);
            writeJsonToFile(getStorageFile(), json);
            logger.debug("Saved dialog positions to {}", getStorageFile());
        } catch (Exception e) {
            logger.error("Failed to save dialog positions: {}", e.getMessage(), e);
        }
    }

    /**
     * Save a single dialog state, merging with existing states.
     *
     * @param state The dialog state to save
     */
    public static void save(DialogState state) {
        if (state == null || state.windowId().isEmpty()) {
            logger.debug("Skipping save for dialog state with empty windowId");
            return;
        }
        Map<String, DialogState> all = loadAll();
        all.put(state.windowId(), state);
        saveAll(all);
    }

    /**
     * Remove a single dialog state from preferences.
     *
     * @param windowId The window ID to remove
     * @return true if the state was found and removed
     */
    public static boolean remove(String windowId) {
        Map<String, DialogState> all = loadAll();
        DialogState removed = all.remove(windowId);
        if (removed != null) {
            saveAll(all);
            logger.debug("Removed dialog position for: {}", windowId);
            return true;
        }
        return false;
    }

    /**
     * Clear all saved dialog positions.
     */
    public static void clearAll() {
        initialize();
        try {
            writeJsonToFile(getStorageFile(), "{}");
        } catch (IOException e) {
            logger.error("Failed to clear dialog positions: {}", e.getMessage(), e);
            return;
        }
        logger.info("Cleared all saved dialog positions");
    }

    /**
     * Remove fallback hash-code based entries from saved positions.
     *
     * @return The number of entries removed
     */
    public static int cleanupFallbackEntries() {
        Map<String, DialogState> all = loadAll();
        int originalSize = all.size();

        all.entrySet().removeIf(entry -> {
            String key = entry.getKey();
            return key != null && key.startsWith("@");
        });

        int removed = originalSize - all.size();

        if (removed > 0) {
            saveAll(all);
            logger.info("Cleaned up {} fallback dialog position entries", removed);
        }

        return removed;
    }

    /**
     * Get an unmodifiable view of all saved states.
     */
    public static Map<String, DialogState> getAll() {
        return Collections.unmodifiableMap(loadAll());
    }

    /**
     * Check if a window title is in the ignored list and should not be persisted.
     */
    public static boolean isIgnoredWindow(String windowId) {
        return windowId != null && IGNORED_WINDOWS.contains(windowId);
    }

    // --- File I/O helpers ---

    private static String readJsonFromFile(Path file) throws IOException {
        synchronized (FILE_LOCK) {
            if (!Files.exists(file)) {
                return "{}";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        }
    }

    private static void writeJsonToFile(Path file, String json) throws IOException {
        synchronized (FILE_LOCK) {
            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            // Write to a sibling temp file then atomic-rename, so a crash mid-write
            // can't leave the live file truncated.
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                // Some filesystems (network shares, FAT) don't support atomic move.
                // Fall back to non-atomic; the temp file model still saves us from
                // half-written content because writeString completed before move.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String mapToJson(Map<String, DialogState> states) {
        JsonObject root = new JsonObject();
        for (var entry : states.entrySet()) {
            String key = entry.getKey();
            if (key != null && !key.startsWith("@") && !isIgnoredWindow(key)) {
                root.add(key, stateToJson(entry.getValue()));
            }
        }
        return GSON.toJson(root);
    }

    // --- Compact JSON serialization / deserialization ---

    /**
     * Serialize a DialogState to a compact JsonObject.
     * Only includes non-default values to minimize JSON size.
     */
    private static JsonObject stateToJson(DialogState state) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", (int) Math.round(state.x()));
        obj.addProperty("y", (int) Math.round(state.y()));
        obj.addProperty("w", (int) Math.round(state.width()));
        obj.addProperty("h", (int) Math.round(state.height()));
        // Only include non-default values
        // Note: modality is not persisted - it's intrinsic to the window type and
        // will be set correctly when the window is recreated by its owning code
        if (state.screenIndex() != 0) {
            obj.addProperty("si", state.screenIndex());
        }
        if (state.savedScaleX() != 1.0) {
            obj.addProperty("sx", state.savedScaleX());
        }
        if (state.savedScaleY() != 1.0) {
            obj.addProperty("sy", state.savedScaleY());
        }
        return obj;
    }

    /**
     * Deserialize a JsonObject to a DialogState.
     * Handles both the current compact format and the legacy verbose format.
     */
    private static DialogState jsonToState(String windowId, JsonObject obj) {
        int x = getInt(obj, "x", null, 0);
        int y = getInt(obj, "y", null, 0);
        int w = getInt(obj, "w", "width", 0);
        int h = getInt(obj, "h", "height", 0);

        String modStr = getString(obj, "m", "modality", "NONE");
        Modality mod = Modality.NONE;
        try {
            mod = Modality.valueOf(modStr);
        } catch (IllegalArgumentException e) {
            // Keep default
        }

        int si = getInt(obj, "si", "screenIndex", 0);
        double sx = getDouble(obj, "sx", "scaleX", 1.0);
        double sy = getDouble(obj, "sy", "scaleY", 1.0);

        // Legacy format stored title redundantly; use map key as title
        String title = obj.has("title") ? obj.get("title").getAsString() : windowId;

        return new DialogState(windowId, title, x, y, w, h, mod, false, si, sx > 0 ? sx : 1.0, sy > 0 ? sy : 1.0);
    }

    /** Get an int from a JsonObject, checking primary key then alternate key. */
    private static int getInt(JsonObject obj, String key, String altKey, int defaultVal) {
        if (obj.has(key)) return obj.get(key).getAsInt();
        if (altKey != null && obj.has(altKey)) return obj.get(altKey).getAsInt();
        return defaultVal;
    }

    /** Get a double from a JsonObject, checking primary key then alternate key. */
    private static double getDouble(JsonObject obj, String key, String altKey, double defaultVal) {
        if (obj.has(key)) return obj.get(key).getAsDouble();
        if (altKey != null && obj.has(altKey)) return obj.get(altKey).getAsDouble();
        return defaultVal;
    }

    /** Get a String from a JsonObject, checking primary key then alternate key. */
    private static String getString(JsonObject obj, String key, String altKey, String defaultVal) {
        if (obj.has(key)) return obj.get(key).getAsString();
        if (altKey != null && obj.has(altKey)) return obj.get(altKey).getAsString();
        return defaultVal;
    }

    // --- Main Window Position ---

    /**
     * Whether the user has opted in to restoring the main QuPath window position.
     */
    public static boolean isMainWindowRestoreEnabled() {
        initialize();
        return mainWindowEnabledProperty.get();
    }

    /**
     * Set whether to restore the main window position on startup.
     */
    public static void setMainWindowRestoreEnabled(boolean enabled) {
        initialize();
        mainWindowEnabledProperty.set(enabled);
        logger.info("Main window restore enabled: {}", enabled);
    }

    /**
     * Save the main window position and current screen fingerprint.
     *
     * @param x window X position
     * @param y window Y position
     * @param width window width
     * @param height window height
     * @param screenFingerprint a string describing all monitors (count, bounds, scales)
     */
    public static void saveMainWindowState(
            double x, double y, double width, double height, String screenFingerprint) {
        initialize();
        JsonObject obj = new JsonObject();
        obj.addProperty("x", (int) Math.round(x));
        obj.addProperty("y", (int) Math.round(y));
        obj.addProperty("w", (int) Math.round(width));
        obj.addProperty("h", (int) Math.round(height));
        obj.addProperty("fp", screenFingerprint);
        mainWindowJsonProperty.set(GSON.toJson(obj));
        setMainWindowRestoreEnabled(true);
        logger.info(
                "Saved main window state: {}x{} at ({}, {}), fingerprint={}",
                (int) width,
                (int) height,
                (int) x,
                (int) y,
                screenFingerprint);
    }

    /**
     * Load the saved main window state.
     *
     * @return JsonObject with x, y, w, h, fp keys, or null if nothing saved
     */
    public static JsonObject loadMainWindowState() {
        initialize();
        String json = mainWindowJsonProperty.get();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            logger.warn("Failed to parse saved main window state: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Clear saved main window state and disable restore.
     */
    public static void clearMainWindowState() {
        initialize();
        mainWindowJsonProperty.set("");
        setMainWindowRestoreEnabled(false);
        logger.info("Cleared main window saved state");
    }
}
