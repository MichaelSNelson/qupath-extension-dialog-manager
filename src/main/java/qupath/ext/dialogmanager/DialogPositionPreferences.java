package qupath.ext.dialogmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.beans.property.ObjectProperty;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.prefs.PathPrefs;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles persistence of dialog positions using QuPath's preference system.
 * <p>
 * Dialog states are serialized to JSON and stored in a single preference entry.
 * This class handles serialization/deserialization and provides methods to
 * load, save, and clear stored positions.
 */
public final class DialogPositionPreferences {

    private static final Logger logger = LoggerFactory.getLogger(DialogPositionPreferences.class);

    private static final String PREF_KEY = "dialogManager.positions";

    // Note: No pretty printing to stay within Java Preferences 8192 char limit
    private static final Gson GSON = new GsonBuilder().create();

    // Property for the raw JSON string storage
    private static ObjectProperty<String> positionsJsonProperty;

    private DialogPositionPreferences() {
        // Utility class - no instantiation
    }

    /**
     * Initialize the preference property. Must be called during extension installation.
     * <p>
     * This also performs automatic cleanup of any garbage fallback entries that may
     * have accumulated from previous versions.
     */
    public static void initialize() {
        if (positionsJsonProperty == null) {
            positionsJsonProperty = PathPrefs.createPersistentPreference(
                    PREF_KEY,
                    "{}",
                    s -> s,
                    s -> s
            );
            logger.debug("DialogPositionPreferences initialized");

            // Automatically clean up any garbage fallback entries from previous sessions
            // This fixes the "Value too long" error caused by accumulated hash-code IDs
            try {
                int removed = cleanupFallbackEntries();
                if (removed > 0) {
                    logger.info("Automatically cleaned up {} garbage dialog position entries", removed);
                }
            } catch (Exception e) {
                // If cleanup fails, just log and continue - it's not critical
                logger.warn("Failed to cleanup fallback entries: {}", e.getMessage());
            }
        }
    }

    /**
     * Load all saved dialog states from preferences.
     * Handles both the current compact format and the legacy verbose format.
     *
     * @return Map of windowId to DialogState, never null
     */
    public static Map<String, DialogState> loadAll() {
        initialize();
        try {
            String json = positionsJsonProperty.get();
            if (json == null || json.isBlank() || json.equals("{}")) {
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

            logger.debug("Loaded {} dialog positions from preferences", result.size());
            return result;

        } catch (Exception e) {
            logger.warn("Failed to load dialog positions, returning empty map: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Maximum length for the JSON string to stay under Java Preferences limit (8192 bytes).
     * Using a conservative limit to allow for encoding overhead.
     */
    private static final int MAX_JSON_LENGTH = 7500;

    /**
     * Save all dialog states to preferences using compact JSON format.
     * <p>
     * Compact format optimizations:
     * <ul>
     *     <li>Short key names: w/h/m/si/sx/sy instead of width/height/modality/etc.</li>
     *     <li>Integer coordinates: no ".0" suffix on position/size values</li>
     *     <li>Default omission: modality=NONE, screenIndex=0, scale=1.0 are not written</li>
     *     <li>No redundant title: map key serves as both windowId and title</li>
     * </ul>
     * Filters out fallback hash-code based IDs (starting with "@") since these are
     * not useful for persistence - they change every time a window is created.
     *
     * @param states Map of windowId to DialogState
     */
    public static void saveAll(Map<String, DialogState> states) {
        initialize();
        try {
            JsonObject root = new JsonObject();
            for (var entry : states.entrySet()) {
                String key = entry.getKey();
                // Skip hash-code fallback IDs (e.g., "@926214965") - they are not reusable
                // and cause the preferences to grow unboundedly
                if (key != null && !key.startsWith("@")) {
                    root.add(key, stateToJson(entry.getValue()));
                }
            }

            String json = GSON.toJson(root);

            // Safety check: if JSON is still too long, prune entries
            if (json.length() > MAX_JSON_LENGTH) {
                logger.warn("Dialog positions JSON too large ({} chars), pruning entries", json.length());
                while (json.length() > MAX_JSON_LENGTH && root.size() > 0) {
                    String firstKey = root.keySet().iterator().next();
                    root.remove(firstKey);
                    json = GSON.toJson(root);
                    logger.debug("Removed dialog position for '{}' to reduce size", firstKey);
                }
            }

            positionsJsonProperty.set(json);
            logger.debug("Saved {} dialog positions to preferences", root.size());

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
        positionsJsonProperty.set("{}");
        logger.info("Cleared all saved dialog positions");
    }

    /**
     * Remove fallback hash-code based entries from saved preferences.
     * <p>
     * This cleans up garbage entries that were created for windows without titles.
     * These entries use IDs like "@926214965" which are not reusable across sessions.
     *
     * @return The number of entries removed
     */
    public static int cleanupFallbackEntries() {
        Map<String, DialogState> all = loadAll();
        int originalSize = all.size();

        // Remove entries with hash-code fallback IDs
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
        if (state.modality() != Modality.NONE) {
            obj.addProperty("m", state.modality().name());
        }
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

        return new DialogState(windowId, title, x, y, w, h, mod, false, si,
                sx > 0 ? sx : 1.0, sy > 0 ? sy : 1.0);
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
}
