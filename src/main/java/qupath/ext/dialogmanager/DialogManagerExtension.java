package qupath.ext.dialogmanager;

import java.nio.file.Path;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.dialogmanager.ui.DialogManagerUI;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath extension for managing dialog window positions.
 * <p>
 * This extension provides:
 * <ul>
 *   <li>Automatic tracking of dialog window positions</li>
 *   <li>Persistence of positions across QuPath sessions</li>
 *   <li>Recovery of dialogs that have moved off-screen (e.g., monitor disconnected)</li>
 *   <li>Manual position management through a dedicated UI</li>
 * </ul>
 * <p>
 * <b>Testing Mode:</b> By default, only specific targeted dialogs are tracked.
 * Use the UI to enable tracking of all dialogs, or call
 * {@code DialogPositionManager.getInstance().addTargetedTitle("Dialog Title")}
 * to track specific dialogs.
 */
public class DialogManagerExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(DialogManagerExtension.class);

    private static final String EXTENSION_NAME = "Dialog Position Manager";
    private static final String EXTENSION_DESCRIPTION =
            "Manage and persist dialog window positions with off-screen recovery.";
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");

    private boolean isInstalled = false;

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled) {
            logger.warn("{} is already installed", EXTENSION_NAME);
            return;
        }

        logger.info("Installing extension: {}", EXTENSION_NAME);

        // Initialize preferences
        DialogPositionPreferences.initialize();

        // Initialize the manager with the main QuPath stage
        DialogPositionManager manager = DialogPositionManager.getInstance();
        manager.initialize(qupath.getStage());

        // Add some default targeted dialogs for testing
        // These are common QuPath dialogs we want to track
        addDefaultTargetedDialogs(manager);

        // Attempt to restore main window position.
        // Use Platform.runLater so QuPath's own layout/positioning completes first.
        Stage mainStage = qupath.getStage();
        if (mainStage != null) {
            if (mainStage.isShowing()) {
                // Stage is already visible (common in QuPath 0.7+, which loads
                // extensions after the main window is shown).
                Platform.runLater(() -> {
                    boolean restored = manager.restoreMainWindowPosition();
                    if (restored) {
                        logger.info("Main QuPath window position restored from saved state");
                    }
                });
            } else {
                // Stage not yet visible -- wait for it
                mainStage.showingProperty().addListener((obs, wasShowing, isShowing) -> {
                    if (isShowing) {
                        Platform.runLater(() -> {
                            boolean restored = manager.restoreMainWindowPosition();
                            if (restored) {
                                logger.info("Main QuPath window position restored from saved state");
                            }
                        });
                    }
                });
            }
        }

        // Add menu items
        Platform.runLater(() -> addMenuItems(qupath));

        // If preferences just migrated from the old PathPrefs entry into a JSON
        // file, surface that to the user once -- they need to know the new
        // location both because it explains a one-time JSON-too-large warning
        // disappearing and because they may want to point it at a shared file.
        Path pendingNotice = DialogPositionPreferences.getPendingMigrationNotice();
        if (pendingNotice != null) {
            Stage owner = qupath.getStage();
            Platform.runLater(() -> showMigrationNotice(owner, pendingNotice));
        }

        isInstalled = true;
        logger.info("{} installation complete", EXTENSION_NAME);
    }

    /**
     * One-time notification: dialog positions moved from PathPrefs into a JSON
     * file. Marks the notice shown so it doesn't fire on every startup.
     */
    private void showMigrationNotice(Stage owner, Path location) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Dialog Position Manager: storage moved");
        alert.setHeaderText("Saved dialog positions moved to a file");
        alert.setContentText(
                "To remove a length limit that was silently pruning saved positions, the "
                        + "Dialog Position Manager now stores its data in a JSON file:\n\n"
                        + location
                        + "\n\nYou can change this location (for example, to share one file across "
                        + "workstations in a core facility) from Window -> Dialog Position Manager.");
        alert.getButtonTypes().setAll(ButtonType.OK);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
        DialogPositionPreferences.markMigrationNoticeShown();
    }

    /**
     * Add commonly used QuPath dialogs to the targeted list.
     * These will be tracked even when "Track all" is disabled.
     */
    private void addDefaultTargetedDialogs(DialogPositionManager manager) {
        // Common QuPath dialogs for testing
        manager.addTargetedTitle("Brightness & Contrast");
        manager.addTargetedTitle("Script editor");
        manager.addTargetedTitle("Log");
        manager.addTargetedTitle("Command list");
        manager.addTargetedTitle("Measurement table");
        manager.addTargetedTitle("Preferences");
        manager.addTargetedTitle("Objects");
        manager.addTargetedTitle("Annotations");
        manager.addTargetedTitle("Detections");
        manager.addTargetedTitle("Measurement maps");

        // This extension's own dialog (meta!)
        manager.addTargetedTitle("Dialog Position Manager");

        // Dialogs whose title encodes mutable state (modality, objective, ...)
        // are registered here so every variant collapses onto a single saved
        // position. The QPSC Live Viewer rewrites its title to e.g.
        // "Live Viewer (Brightfield) (10x)" / "Live Viewer (PPM) (20x)" as the
        // user changes hardware; without this every combination would create
        // its own entry and the saved position would not survive a modality
        // switch.
        manager.addTitlePrefixAlias("Live Viewer");

        logger.debug("Added {} default targeted dialogs", 11);
    }

    private void addMenuItems(QuPathGUI qupath) {
        // Get or create the Window menu (since our feature is window-related)
        Menu windowMenu = qupath.getMenu("Window", true);

        // Find where to insert our items (after existing items, before separator if any)
        int insertIndex = windowMenu.getItems().size();

        // Add separator before our items
        windowMenu.getItems().add(insertIndex++, new SeparatorMenuItem());

        // Dialog Position Manager menu item
        MenuItem managerItem = new MenuItem("Dialog Position Manager...");
        managerItem.setOnAction(e -> DialogManagerUI.show(qupath.getStage()));
        windowMenu.getItems().add(insertIndex++, managerItem);

        // Quick action: Center all off-screen dialogs
        MenuItem centerOffscreenItem = new MenuItem("Recover Off-Screen Dialogs");
        centerOffscreenItem.setOnAction(e -> recoverOffScreenDialogs());
        windowMenu.getItems().add(insertIndex, centerOffscreenItem);

        logger.debug("Added menu items to Window menu");
    }

    /**
     * Center any tracked dialogs that are currently off-screen.
     */
    private void recoverOffScreenDialogs() {
        DialogPositionManager manager = DialogPositionManager.getInstance();
        int recovered = 0;

        for (DialogState state : manager.getDialogStates()) {
            if (state.isCurrentlyOpen() && !manager.isPositionOnScreen(state)) {
                if (manager.centerDialog(state.windowId())) {
                    recovered++;
                    logger.info("Recovered off-screen dialog: {}", state.windowId());
                }
            }
        }

        if (recovered == 0) {
            logger.info("No off-screen dialogs found");
        } else {
            logger.info("Recovered {} off-screen dialog(s)", recovered);
        }
    }
}
