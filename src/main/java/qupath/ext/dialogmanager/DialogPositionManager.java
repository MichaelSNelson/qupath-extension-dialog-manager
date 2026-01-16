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

import java.util.HashSet;
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

    // Whether to track all windows or only targeted ones (default: true for maximum usefulness)
    private boolean trackAllWindows = true;

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
        for (var entry : trackedWindows.entrySet()) {
            if (getWindowId(entry.getKey()).equals(windowId)) {
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
        for (var entry : trackedWindows.entrySet()) {
            if (getWindowId(entry.getKey()).equals(windowId)) {
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
     *
     * @param windowId The window ID to reset
     */
    public void resetDialogPosition(String windowId) {
        DialogPositionPreferences.remove(windowId);

        // Update our state list
        dialogStates.removeIf(s -> s.windowId().equals(windowId));

        logger.info("Reset dialog position to default: {}", windowId);
    }

    /**
     * Bring a dialog to the front if it's currently open.
     *
     * @param windowId The window ID to bring to front
     * @return true if the window was found and brought to front
     */
    public boolean bringToFront(String windowId) {
        for (var entry : trackedWindows.entrySet()) {
            if (getWindowId(entry.getKey()).equals(windowId)) {
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
            logger.info("Tracking window: '{}'", title);
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
                            logger.info("Now tracking window: '{}'", newTitle);
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
            logger.info("Found saved position for '{}': ({}, {})", windowId, savedState.x(), savedState.y());
        } else {
            logger.debug("No saved position for '{}'", windowId);
        }

        if (window.isShowing()) {
            // Window already showing - restore position now (may cause brief jump)
            if (savedState != null) {
                logger.info("Restoring position for '{}' (window already showing)", windowId);
                restoreWindowPositionWithValidation(window, savedState);
            }
            startTracking(window);
        } else {
            // Window not yet showing - this is ideal!
            // Set position BEFORE show to prevent default centering
            if (savedState != null) {
                // Pre-set the position before the window shows
                logger.info("Pre-setting position for '{}' BEFORE show", windowId);
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
            DialogPositionPreferences.save(finalState);

            // Update our observable list
            updateDialogState(finalState);

            logger.debug("Window removed and state saved: {}", finalState.windowId());
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
            logger.info("Restored {} with scale change (position still valid)", savedState.windowId());

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
        String title = (window instanceof Stage stage) ? stage.getTitle() : windowId;
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
                // Normalize title: remove dynamic parts like file paths or image names
                // For now, just use the title as-is
                return title.trim();
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
            DialogPositionPreferences.save(state);
        }
    }
}
