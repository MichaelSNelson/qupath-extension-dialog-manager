package qupath.ext.dialogmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import javafx.beans.property.ObjectProperty;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.prefs.PathPrefs;

import java.lang.reflect.Type;
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

    private static final Type STATE_MAP_TYPE = new TypeToken<Map<String, SerializedState>>() {}.getType();

    // Property for the raw JSON string storage
    private static ObjectProperty<String> positionsJsonProperty;

    private DialogPositionPreferences() {
        // Utility class - no instantiation
    }

    /**
     * Initialize the preference property. Must be called during extension installation.
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
        }
    }

    /**
     * Load all saved dialog states from preferences.
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

            Map<String, SerializedState> serialized = GSON.fromJson(json, STATE_MAP_TYPE);
            if (serialized == null) {
                return new HashMap<>();
            }

            Map<String, DialogState> result = new HashMap<>();
            for (var entry : serialized.entrySet()) {
                DialogState state = entry.getValue().toDialogState(entry.getKey());
                if (state != null) {
                    result.put(entry.getKey(), state);
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
     * Save all dialog states to preferences.
     *
     * @param states Map of windowId to DialogState
     */
    public static void saveAll(Map<String, DialogState> states) {
        initialize();
        try {
            Map<String, SerializedState> serialized = new HashMap<>();
            for (var entry : states.entrySet()) {
                serialized.put(entry.getKey(), SerializedState.fromDialogState(entry.getValue()));
            }

            String json = GSON.toJson(serialized, STATE_MAP_TYPE);
            positionsJsonProperty.set(json);

            logger.debug("Saved {} dialog positions to preferences", states.size());

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
     * Get an unmodifiable view of all saved states.
     */
    public static Map<String, DialogState> getAll() {
        return Collections.unmodifiableMap(loadAll());
    }

    /**
     * Internal class for JSON serialization.
     * Uses simple types that Gson can handle without custom adapters.
     */
    private static class SerializedState {
        String title;
        double x;
        double y;
        double width;
        double height;
        String modality;
        int screenIndex;
        double scaleX = 1.0;  // Default for backward compatibility
        double scaleY = 1.0;  // Default for backward compatibility

        static SerializedState fromDialogState(DialogState state) {
            SerializedState s = new SerializedState();
            s.title = state.title();
            s.x = state.x();
            s.y = state.y();
            s.width = state.width();
            s.height = state.height();
            s.modality = state.modality().name();
            s.screenIndex = state.screenIndex();
            s.scaleX = state.savedScaleX();
            s.scaleY = state.savedScaleY();
            return s;
        }

        DialogState toDialogState(String windowId) {
            Modality mod = Modality.NONE;
            try {
                if (modality != null) {
                    mod = Modality.valueOf(modality);
                }
            } catch (IllegalArgumentException e) {
                // Keep default
            }
            // Handle missing scale factors from older saved data
            double sx = (scaleX > 0) ? scaleX : 1.0;
            double sy = (scaleY > 0) ? scaleY : 1.0;
            return new DialogState(windowId, title, x, y, width, height, mod, false, screenIndex, sx, sy);
        }
    }
}
