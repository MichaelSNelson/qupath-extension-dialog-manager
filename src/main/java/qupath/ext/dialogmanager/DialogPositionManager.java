package qupath.ext.dialogmanager;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Rectangle2D;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton manager for tracking and restoring dialog window positions.
 * <p>
 * This manager:
 * <ul>
 *   <li>Listens to all JavaFX windows and tracks their positions</li>
 *   <li>Persists positions when windows close</li>
 *   <li>Restores positions when tracked windows reopen</li>
 *   <li>Handles missing monitors by falling back to primary screen</li>
 *   <li>Provides UI hooks for manual position management</li>
 * </ul>
 * <p>
 * <b>HiDPI Handling:</b> This manager stores scale factors along with positions
 * to detect when display configuration has changed. JavaFX coordinates are in
 * virtual (scaled) units, which generally work across DPI changes, but edge cases
 * exist with mixed-DPI multi-monitor setups.
 * <p>
 * <b>Modal Window Handling:</b> Modal windows are tracked but managed carefully
 * to avoid interfering with their blocking behavior. Position restoration happens
 * before the window is shown to prevent visual jumping.
 */
public final class DialogPositionManager {

    private static final Logger logger = LoggerFactory.getLogger(DialogPositionManager.class);

    private static DialogPositionManager instance;

    /**
     * Minimum pixels that must be visible on screen for a position to be valid.
     * This ensures at least the title bar and some content are accessible.
     */
    private static final double MIN_VISIBLE_PIXELS = 100;

    // Windows we're actively tracking (have attached listeners to)
    private final Map<Window, WindowTracker> trackedWindows = new ConcurrentHashMap<>();

    // Observable list of current dialog states for UI binding
    private final ObservableList<DialogState> dialogStates = FXCollections.observableArrayList();

    // Set of window IDs we should NOT track (user has explicitly excluded them)
    private final Set<String> excludedWindowIds = new HashSet<>();

    // Reference to the main QuPath stage (we don't track this one)
    private Stage mainStage;

    // Titles of windows to explicitly track (for testing specific dialogs)
    private final Set<String> targetedTitles = new HashSet<>();

    // Title prefixes that collapse a family of dynamic titles ("Live Viewer
    // (Brightfield) (10x)", "Live Viewer (PPM) (20x)", ...) onto a single
    // canonical windowId. Insertion-ordered, but match-time we prefer the
    // longest prefix so overlapping families pick the most specific one.
    private final Set<String> titlePrefixAliases = new LinkedHashSet<>();

    // Whether to track all windows or only targeted ones (default: true for maximum usefulness)
    private boolean trackAllWindows = true;

    // Whether to log routine tracking/restore messages at INFO (true) or DEBUG (false)
    private boolean verboseLogging = false;

    private DialogPositionManager() {
        // Load saved states on initialization
        loadSavedStates();
    }

    /**
     * Get the singleton instance.
     */
    public static synchronized DialogPositionManager getInstance() {
        if (instance == null) {
            instance = new DialogPositionManager();
        }
        return instance;
    }

    /**
     * Initialize the manager with the main QuPath stage.
     * Call this during extension installation.
     *
     * @param quPathStage The main QuPath window (will not be tracked)
     */
    public void initialize(Stage quPathStage) {
        this.mainStage = quPathStage;

        // Log current screen configuration for debugging
        logScreenConfiguration();

        // Start listening to window list changes
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (Window window : change.getAddedSubList()) {
                        onWindowAdded(window);
                    }
                }
                if (change.wasRemoved()) {
                    for (Window window : change.getRemoved()) {
                        onWindowRemoved(window);
                    }
                }
            }
        });

        // Process any windows that already exist
        for (Window window : Window.getWindows()) {
            onWindowAdded(window);
        }

        logger.info("DialogPositionManager initialized, tracking {} windows", trackedWindows.size());
    }

    /**
     * Add a window title to the list of targeted windows.
     * Only these windows will be tracked if trackAllWindows is false.
     */
    public void addTargetedTitle(String title) {
        targetedTitles.add(title);
        logger.debug("Added targeted title: {}", title);

        // Check if any existing windows match
        for (Window window : Window.getWindows()) {
            if (shouldTrackWindow(window) && !trackedWindows.containsKey(window)) {
                startTracking(window);
            }
        }
    }

    /**
     * Remove a window title from the targeted list.
     */
    public void removeTargetedTitle(String title) {
        targetedTitles.remove(title);
    }

    /**
     * Register a title prefix that should collapse a family of dynamic titles
     * onto a single canonical windowId.
     * <p>
     * Example: registering {@code "Live Viewer"} causes titles like
     * {@code "Live Viewer"}, {@code "Live Viewer (Brightfield)"} and
     * {@code "Live Viewer (PPM) (10x)"} to all share one saved position under
     * the canonical id {@code "Live Viewer"}. Without this, each unique title
     * variant becomes its own entry in the position file, which defeats
     * position persistence for dialogs whose title reflects mutable state.
     * <p>
     * Matching uses a word boundary: a title matches the prefix only if it
     * either equals the prefix exactly or begins with the prefix followed by a
     * space. {@code "Live Viewers"} (no space, plural) would NOT match
     * {@code "Live Viewer"}.
     * <p>
     * Any already-saved entries that match the prefix are collapsed into the
     * canonical id at registration time so users do not lose their existing
     * positions across the upgrade.
     *
     * @param prefix non-empty title prefix; leading and trailing whitespace are ignored
     */
    public void addTitlePrefixAlias(String prefix) {
        if (prefix == null) return;
        String trimmed = prefix.trim();
        if (trimmed.isEmpty()) return;
        if (titlePrefixAliases.add(trimmed)) {
            logger.debug("Added title prefix alias: '{}'", trimmed);
            collapseExistingEntriesForPrefix(trimmed);
        }
    }

    /**
     * Stop collapsing titles under the given prefix. Existing saved entries
     * that were already collapsed are not undone.
     */
    public void removeTitlePrefixAlias(String prefix) {
        if (prefix == null) return;
        titlePrefixAliases.remove(prefix.trim());
    }

    /**
     * Collapse the given title to its canonical form if it matches any
     * registered prefix alias; otherwise return the input unchanged. When
     * several prefixes match (overlapping families) the longest one wins.
     */
    private String canonicalizeTitle(String title) {
        if (title == null || titlePrefixAliases.isEmpty()) {
            return title;
        }
        String best = null;
        for (String prefix : titlePrefixAliases) {
            if (matchesPrefix(title, prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best != null ? best : title;
    }

    private static boolean matchesPrefix(String title, String prefix) {
        if (title.equals(prefix)) return true;
        return title.length() > prefix.length()
                && title.startsWith(prefix)
                && title.charAt(prefix.length()) == ' ';
    }

    /**
     * One-time sweep when an alias is registered: fold any pre-existing
     * dynamic-title entries ({@code "Live Viewer (Brightfield) (10x)"}, ...)
     * into the canonical key so the user's last position survives the upgrade.
     * If the canonical key is already saved we keep it and discard the
     * suffix variants; otherwise we promote a deterministic (lexicographically
     * first) match.
     */
    private void collapseExistingEntriesForPrefix(String prefix) {
        Map<String, DialogState> all = DialogPositionPreferences.loadAll();
        List<String> matches = new ArrayList<>();
        for (String key : all.keySet()) {
            if (matchesPrefix(key, prefix)) {
                matches.add(key);
            }
        }
        if (matches.isEmpty()) {
            return;
        }
        if (matches.size() == 1 && matches.get(0).equals(prefix)) {
            return; // Only the canonical entry exists; nothing to do.
        }

        Collections.sort(matches);
        DialogState source = all.containsKey(prefix) ? all.get(prefix) : all.get(matches.get(0));

        DialogState canonical = new DialogState(
                prefix, prefix,
                source.x(), source.y(), source.width(), source.height(),
                source.modality(), false,
                source.screenIndex(), source.savedScaleX(), source.savedScaleY());

        for (String key : matches) {
            all.remove(key);
        }
        all.put(prefix, canonical);
        DialogPositionPreferences.saveAll(all);

        Platform.runLater(() -> {
            dialogStates.removeIf(s -> s.windowId() != null && matchesPrefix(s.windowId(), prefix));
            dialogStates.add(canonical);
        });

        int collapsed = matches.size() - (matches.contains(prefix) ? 1 : 0);
        if (collapsed > 0) {
            logger.info("Collapsed {} saved dialog entr{} under canonical '{}'",
                    collapsed, collapsed == 1 ? "y" : "ies", prefix);
        }
    }

    /**
     * Check whether verbose logging is enabled.
     */
    public boolean isVerboseLogging() {
        return verboseLogging;
    }

    /**
     * Set whether routine tracking/restore messages are logged at INFO level.
     * When false (default), these messages are logged at DEBUG level to reduce noise.
     */
    public void setVerboseLogging(boolean verbose) {
        this.verboseLogging = verbose;
    }

    /**
     * Set whether to track all windows or only targeted ones.
     */
    public void setTrackAllWindows(boolean trackAll) {
        this.trackAllWindows = trackAll;

        // Re-evaluate all windows if switching to track all
        if (trackAll) {
            for (Window window : Window.getWindows()) {
                if (shouldTrackWindow(window) && !trackedWindows.containsKey(window)) {
                    startTracking(window);
                }
            }
        }
    }

    /**
     * Get the observable list of dialog states for UI binding.
     */
    public ObservableList<DialogState> getDialogStates() {
        return dialogStates;
    }

    /**
     * Center a specific dialog on the primary screen.
     *
     * @param windowId The window ID to center
     * @return true if the window was found and centered
     */
    public boolean centerDialog(String windowId) {
        if (windowId == null) return false;
        for (var entry : trackedWindows.entrySet()) {
            if (windowId.equals(getWindowId(entry.getKey()))) {
                Window window = entry.getKey();
                Platform.runLater(() -> {
                    centerWindowOnScreen(window, Screen.getPrimary());
                    logger.info("Centered dialog: {}", windowId);
                });
                return true;
            }
        }
        logger.warn("Cannot center dialog - not currently open: {}", windowId);
        return false;
    }

    /**
     * Close a specific dialog window.
     *
     * @param windowId The window ID to close
     * @return true if the window was found and close was requested
     */
    public boolean closeDialog(String windowId) {
        if (windowId == null) return false;
        for (var entry : trackedWindows.entrySet()) {
            if (windowId.equals(getWindowId(entry.getKey()))) {
                Window window = entry.getKey();
                Platform.runLater(() -> {
                    if (window instanceof Stage stage) {
                        stage.close();
                        logger.info("Closed dialog: {}", windowId);
                    }
                });
                return true;
            }
        }
        return false;
    }

    /**
     * Reset a dialog to default position (remove saved position).
     * If the dialog is currently open, it remains in the list with updated state.
     * If it is closed, the entry is removed since there is no saved position to display.
     *
     * @param windowId The window ID to reset
     */
    public void resetDialogPosition(String windowId) {
        if (windowId == null) return;
        DialogPositionPreferences.remove(windowId);

        // If the window is currently open, refresh its state from the live window
        // rather than removing it from the list entirely
        Window openWindow = findOpenWindow(windowId);
        if (openWindow != null) {
            DialogState freshState = createStateFromWindow(openWindow).withOpenStatus(true);
            updateDialogState(freshState);
            logger.info("Reset saved position for open dialog: {}", windowId);
        } else {
            // Closed dialog with no saved position -- remove from list
            Platform.runLater(() -> dialogStates.removeIf(s -> windowId.equals(s.windowId())));
            logger.info("Reset dialog position to default: {}", windowId);
        }
    }

    /**
     * Find a currently open (tracked) window by its ID.
     *
     * @param windowId The window ID to find
     * @return The window if found and showing, null otherwise
     */
    private Window findOpenWindow(String windowId) {
        for (var entry : trackedWindows.entrySet()) {
            if (windowId.equals(getWindowId(entry.getKey())) && entry.getKey().isShowing()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Bring a dialog to the front if it's currently open.
     *
     * @param windowId The window ID to bring to front
     * @return true if the window was found and brought to front
     */
    public boolean bringToFront(String windowId) {
        if (windowId == null) return false;
        for (var entry : trackedWindows.entrySet()) {
            if (windowId.equals(getWindowId(entry.getKey()))) {
                Window window = entry.getKey();
                Platform.runLater(() -> {
                    if (window instanceof Stage stage) {
                        stage.toFront();
                        stage.requestFocus();
                    }
                });
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a saved position is on a currently available screen with sufficient visibility.
     * Validates that at least MIN_VISIBLE_PIXELS are within a screen's visual bounds.
     */
    public boolean isPositionOnScreen(DialogState state) {
        if (!state.hasValidPosition()) {
            return false;
        }

        return isPositionSufficientlyVisible(state.x(), state.y(), state.width(), state.height());
    }

    /**
     * Get a list of all currently available screens.
     */
    public List<Screen> getAvailableScreens() {
        return Screen.getScreens();
    }

    /**
     * Get diagnostic information about the current screen configuration.
     */
    public String getScreenDiagnostics() {
        StringBuilder sb = new StringBuilder();
        List<Screen> screens = Screen.getScreens();
        sb.append(String.format("Detected %d screen(s):%n", screens.size()));

        for (int i = 0; i < screens.size(); i++) {
            Screen screen = screens.get(i);
            Rectangle2D bounds = screen.getVisualBounds();
            sb.append(String.format(
                    "  Screen %d: %.0fx%.0f at (%.0f,%.0f) scale=%.2fx%.2f%s%n",
                    i,
                    bounds.getWidth(), bounds.getHeight(),
                    bounds.getMinX(), bounds.getMinY(),
                    screen.getOutputScaleX(), screen.getOutputScaleY(),
                    screen == Screen.getPrimary() ? " [PRIMARY]" : ""
            ));
        }
        return sb.toString();
    }

    // ========== Private Methods ==========

    /**
     * Log a message at INFO when verbose logging is on, DEBUG otherwise.
     * Used for routine tracking/restore messages that are useful for debugging
     * but create noise during normal use.
     */
    private void logVerbose(String msg, Object... args) {
        if (verboseLogging) {
            logger.info(msg, args);
        } else {
            logger.debug(msg, args);
        }
    }

    private void logScreenConfiguration() {
        logger.info("Screen configuration at startup:\n{}", getScreenDiagnostics());
    }

    private void loadSavedStates() {
        DialogPositionPreferences.initialize();
        Map<String, DialogState> saved = DialogPositionPreferences.loadAll();

        // Add all saved states to our observable list (marked as not open)
        for (DialogState state : saved.values()) {
            dialogStates.add(state.withOpenStatus(false));
        }

        logger.debug("Loaded {} saved dialog states", saved.size());
    }

    private void onWindowAdded(Window window) {
        // Only track Stage instances
        if (!(window instanceof Stage stage)) {
            logger.trace("Ignoring non-Stage window: {}", window.getClass().getSimpleName());
            return;
        }

        // Don't track the main QuPath window
        if (window == mainStage) {
            logger.trace("Ignoring main QuPath window");
            return;
        }

        String title = stage.getTitle();
        logger.debug("Window added: '{}' (showing={})", title, window.isShowing());

        // If the window already has a title and should be tracked, process immediately
        if (shouldTrackWindow(window)) {
            logVerbose("Tracking window: '{}'", title);
            processNewWindow(window);
        } else if (title == null || title.isBlank()) {
            // Title not set yet - listen for title changes
            logger.debug("Window has no title yet, adding title listener");
            stage.titleProperty().addListener(new ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends String> obs,
                                    String oldTitle, String newTitle) {
                    if (newTitle != null && !newTitle.isBlank()) {
                        logger.debug("Window title set to: '{}'", newTitle);
                        if (shouldTrackWindow(window)) {
                            stage.titleProperty().removeListener(this);
                            logVerbose("Now tracking window: '{}'", newTitle);
                            processNewWindow(window);
                        }
                    }
                }
            });
        } else {
            logger.debug("Window '{}' not tracked (excluded or trackAllWindows=false)", title);
        }
    }

    /**
     * Process a new window that should be tracked.
     * Restores position BEFORE the window shows to prevent visual jumping.
     */
    private void processNewWindow(Window window) {
        if (trackedWindows.containsKey(window)) {
            logger.trace("Window already being tracked, skipping");
            return; // Already tracking
        }

        String windowId = getWindowId(window);
        logger.debug("Processing new window: '{}' (showing={})", windowId, window.isShowing());

        // Check if we have a saved position BEFORE the window shows
        Map<String, DialogState> saved = DialogPositionPreferences.loadAll();
        DialogState savedState = saved.get(windowId);

        if (savedState != null) {
            logVerbose("Found saved position for '{}': ({}, {})", windowId, savedState.x(), savedState.y());
        } else {
            logger.debug("No saved position for '{}'", windowId);
        }

        if (window.isShowing()) {
            // Window already showing - restore position now (may cause brief jump)
            if (savedState != null) {
                logVerbose("Restoring position for '{}' (window already showing)", windowId);
                restoreWindowPositionWithValidation(window, savedState);
            }
            startTracking(window);
        } else {
            // Window not yet showing - this is ideal!
            // Set position BEFORE show to prevent default centering
            if (savedState != null) {
                // Pre-set the position before the window shows
                logVerbose("Pre-setting position for '{}' BEFORE show", windowId);
                restoreWindowPositionWithValidation(window, savedState);
            }

            // Wait for showing to complete before starting full tracking
            window.showingProperty().addListener(new ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends Boolean> obs,
                                    Boolean wasShowing, Boolean isShowing) {
                    if (isShowing) {
                        window.showingProperty().removeListener(this);
                        // Re-apply position after show in case QuPath overrode it
                        if (savedState != null) {
                            logger.debug("Re-applying position for '{}' after show", windowId);
                            Platform.runLater(() -> restoreWindowPositionWithValidation(window, savedState));
                        }
                        startTracking(window);
                    }
                }
            });
        }
    }

    private void onWindowRemoved(Window window) {
        WindowTracker tracker = trackedWindows.remove(window);
        if (tracker != null) {
            tracker.detach();

            // Save final position with current scale factors
            DialogState finalState = createStateFromWindow(window).withOpenStatus(false);
            String windowId = finalState.windowId();

            // Only save state for windows with proper titles, not hash-code fallbacks
            // Hash-code fallbacks (like "@926214965") are not useful for persistence
            // since they change every time a window is created
            if (windowId != null && !windowId.startsWith("@")) {
                DialogPositionPreferences.save(finalState);
                logger.debug("Window removed and state saved: {}", windowId);
            } else {
                logger.trace("Window removed but not saved (fallback ID): {}", windowId);
            }

            // Update our observable list
            updateDialogState(finalState);
        }
    }

    private boolean shouldTrackWindow(Window window) {
        // Don't track null windows
        if (window == null) {
            return false;
        }

        // Don't track the main QuPath window
        if (window == mainStage) {
            return false;
        }

        // Only track Stage instances (not popups, tooltips, etc.)
        if (!(window instanceof Stage stage)) {
            return false;
        }

        String title = stage.getTitle();
        String windowId = getWindowId(window);

        // Don't track excluded windows
        if (excludedWindowIds.contains(windowId)) {
            return false;
        }

        // Don't track windows in the global ignore list
        if (DialogPositionPreferences.isIgnoredWindow(windowId)) {
            return false;
        }

        // If we're tracking all windows, include this one
        if (trackAllWindows) {
            return true;
        }

        // Otherwise, only track targeted titles
        return title != null && targetedTitles.contains(title);
    }

    private void startTracking(Window window) {
        if (trackedWindows.containsKey(window)) {
            return; // Already tracking
        }

        String windowId = getWindowId(window);
        logger.debug("Starting to track window: {}", windowId);

        // Check if we have a saved position for this window
        Map<String, DialogState> saved = DialogPositionPreferences.loadAll();
        DialogState savedState = saved.get(windowId);

        if (savedState != null) {
            restoreWindowPositionWithValidation(window, savedState);
        }

        // Create and attach tracker
        WindowTracker tracker = new WindowTracker(window);
        trackedWindows.put(window, tracker);

        // Update our state list
        DialogState currentState = createStateFromWindow(window).withOpenStatus(true);
        updateDialogState(currentState);
    }

    /**
     * Restore window position with validation for HiDPI and multi-monitor scenarios.
     */
    private void restoreWindowPositionWithValidation(Window window, DialogState savedState) {
        // Find the best screen to restore to
        Screen targetScreen = findBestScreenForState(savedState);
        double currentScaleX = targetScreen.getOutputScaleX();
        double currentScaleY = targetScreen.getOutputScaleY();

        // Check if the saved position is still valid on current screen configuration
        boolean positionValid = isPositionSufficientlyVisible(
                savedState.x(), savedState.y(), savedState.width(), savedState.height());

        // Check if scale factors have changed significantly
        boolean scaleChanged = savedState.hasValidScaleFactors() &&
                savedState.hasScaleChanged(currentScaleX, currentScaleY);

        if (scaleChanged) {
            logger.debug("Scale factor changed for {}: saved={}x{}, current={}x{}",
                    savedState.windowId(),
                    savedState.savedScaleX(), savedState.savedScaleY(),
                    currentScaleX, currentScaleY);
        }

        if (positionValid && !scaleChanged) {
            // Position is valid and scale hasn't changed - restore directly
            restoreWindowPosition(window, savedState);
            logger.debug("Restored position for: {}", savedState.windowId());

        } else if (positionValid && scaleChanged) {
            // Scale changed but position is still visible - restore but log warning
            restoreWindowPosition(window, savedState);
            logVerbose("Restored {} with scale change (position still valid)", savedState.windowId());

        } else {
            // Position is off-screen or invalid - center on best available screen
            logger.info("Saved position for {} is invalid or off-screen, centering on {}",
                    savedState.windowId(),
                    targetScreen == Screen.getPrimary() ? "primary screen" : "available screen");
            centerWindowOnScreen(window, targetScreen);
        }
    }

    /**
     * Find the best screen to restore a window to based on saved state.
     */
    private Screen findBestScreenForState(DialogState state) {
        List<Screen> screens = Screen.getScreens();

        // Try to find the same screen index
        if (state.screenIndex() >= 0 && state.screenIndex() < screens.size()) {
            Screen savedScreen = screens.get(state.screenIndex());
            // Verify the screen has similar characteristics (rough position match)
            Rectangle2D bounds = savedScreen.getVisualBounds();
            if (bounds.contains(state.x(), state.y()) ||
                bounds.intersects(state.x(), state.y(), state.width(), state.height())) {
                return savedScreen;
            }
        }

        // Try to find a screen that contains the saved position
        for (Screen screen : screens) {
            if (screen.getVisualBounds().contains(state.x(), state.y())) {
                return screen;
            }
        }

        // Try to find a screen that intersects with the saved bounds
        List<Screen> intersecting = Screen.getScreensForRectangle(
                state.x(), state.y(), state.width(), state.height());
        if (!intersecting.isEmpty()) {
            return intersecting.get(0);
        }

        // Fall back to primary
        return Screen.getPrimary();
    }

    /**
     * Check if a position has sufficient visibility on any screen.
     * Requires at least MIN_VISIBLE_PIXELS to be within screen bounds.
     */
    private boolean isPositionSufficientlyVisible(double x, double y, double width, double height) {
        for (Screen screen : Screen.getScreens()) {
            Rectangle2D bounds = screen.getVisualBounds();

            // Calculate how much of the window overlaps with this screen
            double overlapLeft = Math.max(x, bounds.getMinX());
            double overlapRight = Math.min(x + width, bounds.getMaxX());
            double overlapTop = Math.max(y, bounds.getMinY());
            double overlapBottom = Math.min(y + height, bounds.getMaxY());

            double overlapWidth = overlapRight - overlapLeft;
            double overlapHeight = overlapBottom - overlapTop;

            // Check if there's sufficient overlap
            if (overlapWidth >= MIN_VISIBLE_PIXELS && overlapHeight >= MIN_VISIBLE_PIXELS) {
                return true;
            }
        }
        return false;
    }

    private void restoreWindowPosition(Window window, DialogState state) {
        if (state.hasValidPosition()) {
            window.setX(state.x());
            window.setY(state.y());
        }
        if (state.hasValidSize() && window instanceof Stage stage && stage.isResizable()) {
            window.setWidth(state.width());
            window.setHeight(state.height());
        }
    }

    private void centerWindowOnScreen(Window window, Screen screen) {
        Rectangle2D bounds = screen.getVisualBounds();
        double windowWidth = window.getWidth();
        double windowHeight = window.getHeight();

        // Handle case where window size isn't set yet
        if (windowWidth <= 0) windowWidth = 400;
        if (windowHeight <= 0) windowHeight = 300;

        double centerX = bounds.getMinX() + (bounds.getWidth() - windowWidth) / 2;
        double centerY = bounds.getMinY() + (bounds.getHeight() - windowHeight) / 2;

        window.setX(centerX);
        window.setY(centerY);
    }

    private DialogState createStateFromWindow(Window window) {
        String windowId = getWindowId(window);
        String rawTitle = (window instanceof Stage stage && stage.getTitle() != null)
                ? stage.getTitle() : windowId;
        // Keep the displayed title in lockstep with windowId so an alias family
        // shows as one stable entry instead of flickering through every dynamic
        // suffix the underlying stage cycles through.
        String title = canonicalizeTitle(rawTitle);
        Modality modality = (window instanceof Stage stage) ? stage.getModality() : Modality.NONE;

        // Determine which screen the window is primarily on and get its scale factors
        int screenIndex = 0;
        double scaleX = 1.0;
        double scaleY = 1.0;

        List<Screen> screens = Screen.getScreensForRectangle(
                window.getX(), window.getY(), window.getWidth(), window.getHeight());
        if (!screens.isEmpty()) {
            Screen primaryScreen = screens.get(0);
            screenIndex = Screen.getScreens().indexOf(primaryScreen);
            if (screenIndex < 0) screenIndex = 0;

            // Capture the output scale factors for this screen
            scaleX = primaryScreen.getOutputScaleX();
            scaleY = primaryScreen.getOutputScaleY();
        } else {
            // Window not on any screen - use primary screen's scale
            Screen primary = Screen.getPrimary();
            scaleX = primary.getOutputScaleX();
            scaleY = primary.getOutputScaleY();
        }

        return new DialogState(
                windowId,
                title,
                window.getX(),
                window.getY(),
                window.getWidth(),
                window.getHeight(),
                modality,
                window.isShowing(),
                screenIndex,
                scaleX,
                scaleY
        );
    }

    private String getWindowId(Window window) {
        if (window instanceof Stage stage) {
            String title = stage.getTitle();
            if (title != null && !title.isBlank()) {
                // Collapse families of dynamic titles (e.g. modality- and
                // objective-suffixed "Live Viewer" variants) onto a stable
                // canonical id; see addTitlePrefixAlias.
                return canonicalizeTitle(title.trim());
            }
        }
        // Fallback to class name + hash
        return window.getClass().getSimpleName() + "@" + System.identityHashCode(window);
    }

    private void updateDialogState(DialogState newState) {
        Platform.runLater(() -> {
            // Remove existing entry for this window ID
            dialogStates.removeIf(s -> s.windowId().equals(newState.windowId()));
            // Add the new state
            dialogStates.add(newState);
        });
    }

    /**
     * Inner class that attaches listeners to a window to track position changes.
     */
    private class WindowTracker {
        private final Window window;
        private final InvalidationListener positionListener;
        private final ChangeListener<Boolean> showingListener;

        WindowTracker(Window window) {
            this.window = window;

            // Create position/size change listener
            this.positionListener = obs -> onPositionChanged();

            // Attach listeners
            window.xProperty().addListener(positionListener);
            window.yProperty().addListener(positionListener);
            window.widthProperty().addListener(positionListener);
            window.heightProperty().addListener(positionListener);

            // Track showing state
            this.showingListener = (obs, wasShowing, isShowing) -> {
                if (!isShowing) {
                    // Window is hiding - save state
                    saveCurrentState();
                }
            };
            window.showingProperty().addListener(showingListener);
        }

        void detach() {
            window.xProperty().removeListener(positionListener);
            window.yProperty().removeListener(positionListener);
            window.widthProperty().removeListener(positionListener);
            window.heightProperty().removeListener(positionListener);
            window.showingProperty().removeListener(showingListener);
        }

        private void onPositionChanged() {
            // Update our observable list (debouncing would be nice here, but keep it simple)
            DialogState state = createStateFromWindow(window);
            updateDialogState(state);
        }

        private void saveCurrentState() {
            DialogState state = createStateFromWindow(window).withOpenStatus(false);
            // Only save state for windows with proper titles, not hash-code fallbacks
            if (state.windowId() != null && !state.windowId().startsWith("@")) {
                DialogPositionPreferences.save(state);
            }
        }
    }

    // ==================== Main Window Position ====================

    /**
     * Compute a fingerprint of the current screen configuration.
     * Includes count, bounds, and scale for every monitor. Any change
     * (monitor added/removed, resolution changed, rearranged) produces
     * a different fingerprint.
     */
    public static String computeScreenFingerprint() {
        List<Screen> screens = Screen.getScreens();
        StringBuilder sb = new StringBuilder();
        sb.append(screens.size()).append(":");
        for (Screen s : screens) {
            Rectangle2D b = s.getBounds();
            sb.append(String.format("%.0fx%.0f@%.0f,%.0f/%.2fx%.2f;",
                    b.getWidth(), b.getHeight(), b.getMinX(), b.getMinY(),
                    s.getOutputScaleX(), s.getOutputScaleY()));
        }
        return sb.toString();
    }

    /**
     * Save the current main QuPath window position and screen fingerprint.
     * Called explicitly by the user from the Dialog Manager UI.
     */
    public void saveMainWindowPosition() {
        if (mainStage == null) {
            logger.warn("Cannot save main window position: no main stage reference");
            return;
        }
        String fingerprint = computeScreenFingerprint();
        DialogPositionPreferences.saveMainWindowState(
                mainStage.getX(), mainStage.getY(),
                mainStage.getWidth(), mainStage.getHeight(),
                fingerprint);
    }

    /**
     * Attempt to restore the main QuPath window position from saved state.
     * <p>
     * Safety checks (any failure skips restore and disables the preference):
     * <ol>
     *   <li>Preference must be explicitly enabled</li>
     *   <li>Saved state must exist with valid position and size</li>
     *   <li>Screen fingerprint must match exactly</li>
     *   <li>Saved position must be sufficiently visible on current screens</li>
     * </ol>
     *
     * @return true if position was restored, false if skipped for any reason
     */
    public boolean restoreMainWindowPosition() {
        if (mainStage == null) {
            logger.info("Cannot restore main window: no main stage reference");
            return false;
        }

        // Check 1: opt-in preference
        if (!DialogPositionPreferences.isMainWindowRestoreEnabled()) {
            logger.info("Main window restore not enabled (opt-in preference is off)");
            return false;
        }

        // Check 2: saved state exists
        com.google.gson.JsonObject saved = DialogPositionPreferences.loadMainWindowState();
        if (saved == null) {
            logger.info("Main window restore enabled but no saved state found -- disabling");
            DialogPositionPreferences.setMainWindowRestoreEnabled(false);
            return false;
        }

        int x = saved.has("x") ? saved.get("x").getAsInt() : 0;
        int y = saved.has("y") ? saved.get("y").getAsInt() : 0;
        int w = saved.has("w") ? saved.get("w").getAsInt() : 0;
        int h = saved.has("h") ? saved.get("h").getAsInt() : 0;
        String savedFingerprint = saved.has("fp") ? saved.get("fp").getAsString() : "";

        if (w <= 0 || h <= 0) {
            logger.info("Main window saved state has invalid size ({}x{}) -- disabling", w, h);
            DialogPositionPreferences.setMainWindowRestoreEnabled(false);
            return false;
        }

        // Check 3: screen fingerprint must match exactly
        String currentFingerprint = computeScreenFingerprint();
        if (!currentFingerprint.equals(savedFingerprint)) {
            logger.info("Screen configuration changed since main window position was saved -- disabling restore");
            logger.info("  Saved:   {}", savedFingerprint);
            logger.info("  Current: {}", currentFingerprint);
            DialogPositionPreferences.setMainWindowRestoreEnabled(false);
            return false;
        }

        // Check 4: position must be visible on current screens
        if (!isPositionSufficientlyVisible(x, y, w, h)) {
            logger.info("Main window saved position ({},{} {}x{}) is not sufficiently visible -- disabling",
                    x, y, w, h);
            DialogPositionPreferences.setMainWindowRestoreEnabled(false);
            return false;
        }

        // All checks passed -- apply
        mainStage.setX(x);
        mainStage.setY(y);
        mainStage.setWidth(w);
        mainStage.setHeight(h);
        logger.info("Restored main window position: ({}, {}) {}x{}", x, y, w, h);
        return true;
    }

}
