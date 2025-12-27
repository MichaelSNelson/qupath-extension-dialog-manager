package qupath.ext.dialogmanager;

import javafx.application.Platform;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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

        // Add menu items
        Platform.runLater(() -> addMenuItems(qupath));

        isInstalled = true;
        logger.info("{} installation complete", EXTENSION_NAME);
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
