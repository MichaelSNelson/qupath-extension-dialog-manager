package qupath.ext.dialogmanager.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.dialogmanager.DialogPositionManager;
import qupath.ext.dialogmanager.DialogPositionPreferences;
import qupath.ext.dialogmanager.DialogState;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * User interface for managing dialog positions.
 * <p>
 * Provides a list view of all tracked dialogs with actions to:
 * <ul>
 *   <li>Center a dialog on screen</li>
 *   <li>Bring a dialog to front</li>
 *   <li>Close a dialog</li>
 *   <li>Reset a dialog to default position</li>
 *   <li>Clear all saved positions</li>
 * </ul>
 */
public class DialogManagerUI {

    private static final Logger logger = LoggerFactory.getLogger(DialogManagerUI.class);

    private static Stage stage;
    private Label mainWindowStatus;
    private TextField storageFileField;
    private static DialogManagerUI instance;

    private final DialogPositionManager manager;
    private ListView<DialogState> dialogListView;

    private DialogManagerUI() {
        this.manager = DialogPositionManager.getInstance();
    }

    /**
     * Show the dialog manager UI, creating it if necessary.
     *
     * @param owner The owner stage (typically the main QuPath window)
     */
    public static void show(Stage owner) {
        if (stage == null) {
            instance = new DialogManagerUI();
            stage = instance.createStage(owner);
        }

        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
    }

    private Stage createStage(Stage owner) {
        Stage dialogStage = new Stage();
        dialogStage.initOwner(owner);
        dialogStage.initModality(Modality.NONE);
        dialogStage.setTitle("Dialog Position Manager");
        dialogStage.setMinWidth(400);
        dialogStage.setMinHeight(300);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // Create the main list view
        dialogListView = createDialogListView();
        root.setCenter(dialogListView);

        // Create top controls
        root.setTop(createTopControls());

        // Create bottom action buttons
        root.setBottom(createBottomControls());

        Scene scene = new Scene(root, 500, 400);
        dialogStage.setScene(scene);

        return dialogStage;
    }

    private VBox createTopControls() {
        VBox topBox = new VBox(8);
        topBox.setPadding(new Insets(0, 0, 10, 0));

        // Description label
        Label descLabel = new Label(
                "Manage saved dialog positions. Open dialogs are shown with a green indicator.");
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        // Track all windows checkbox
        CheckBox trackAllCheckbox = new CheckBox("Track all dialogs (not just targeted ones)");
        trackAllCheckbox.setTooltip(new Tooltip(
                "When enabled, all dialog windows will have their positions saved.\n" +
                "When disabled, only specifically targeted dialogs are tracked."));
        trackAllCheckbox.setOnAction(e -> {
            manager.setTrackAllWindows(trackAllCheckbox.isSelected());
        });

        // Verbose logging checkbox
        CheckBox verboseLogCheckbox = new CheckBox("Verbose logging");
        verboseLogCheckbox.setTooltip(new Tooltip(
                "Log detailed tracking and position restore messages.\n" +
                "Useful for debugging. Off by default to reduce log noise."));
        verboseLogCheckbox.setOnAction(e -> {
            manager.setVerboseLogging(verboseLogCheckbox.isSelected());
        });

        // --- Main Window Position ---
        Label mainWindowLabel = new Label("QuPath Main Window");
        mainWindowLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

        Button saveWindowBtn = new Button("Save Current Position");
        saveWindowBtn.setTooltip(new Tooltip(
                "Save the current QuPath window position and size.\n"
                        + "Also records the screen configuration so position\n"
                        + "is only restored if monitors haven't changed."));
        saveWindowBtn.setOnAction(e -> {
            manager.saveMainWindowPosition();
            mainWindowStatus.setText("Position saved. Will restore on next startup.");
            mainWindowStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
        });

        CheckBox restoreCheckbox = new CheckBox("Restore on startup");
        restoreCheckbox.setSelected(DialogPositionPreferences.isMainWindowRestoreEnabled());
        restoreCheckbox.setTooltip(new Tooltip(
                "When checked, QuPath will restore the saved window position\n"
                        + "on startup -- but ONLY if the screen configuration is\n"
                        + "identical (same monitors, resolution, arrangement).\n\n"
                        + "Automatically unchecked if monitors change."));
        restoreCheckbox.setOnAction(e -> {
            DialogPositionPreferences.setMainWindowRestoreEnabled(restoreCheckbox.isSelected());
            if (!restoreCheckbox.isSelected()) {
                mainWindowStatus.setText("Restore disabled.");
                mainWindowStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");
            }
        });

        mainWindowStatus = new Label();
        mainWindowStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");
        mainWindowStatus.setWrapText(true);

        // Show current status
        if (DialogPositionPreferences.isMainWindowRestoreEnabled()) {
            var saved = DialogPositionPreferences.loadMainWindowState();
            if (saved != null) {
                mainWindowStatus.setText(String.format("Saved: %dx%d at (%d, %d)",
                        saved.get("w").getAsInt(), saved.get("h").getAsInt(),
                        saved.get("x").getAsInt(), saved.get("y").getAsInt()));
            }
        }

        HBox mainWindowBtnBox = new HBox(8, saveWindowBtn, restoreCheckbox);
        mainWindowBtnBox.setAlignment(Pos.CENTER_LEFT);

        // --- Storage location ---
        Label storageLabel = new Label("Storage file");
        storageLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

        storageFileField = new TextField(DialogPositionPreferences.getStorageFile().toString());
        storageFileField.setEditable(false);
        storageFileField.setTooltip(new Tooltip(
                "Where saved dialog positions are stored. Point multiple\n"
                        + "workstations at the same path on a shared drive to\n"
                        + "share dialog layouts across a core facility."));
        HBox.setHgrow(storageFileField, Priority.ALWAYS);

        Button browseBtn = new Button("Browse...");
        browseBtn.setTooltip(new Tooltip("Choose a JSON file for dialog-position storage."));
        browseBtn.setOnAction(e -> chooseStorageFile());

        Button resetStorageBtn = new Button("Use Default");
        resetStorageBtn.setTooltip(new Tooltip(
                "Reset to the default location under the QuPath user directory."));
        resetStorageBtn.setOnAction(e -> resetStorageToDefault());

        HBox storageBtnBox = new HBox(8, storageFileField, browseBtn, resetStorageBtn);
        storageBtnBox.setAlignment(Pos.CENTER_LEFT);

        // Screen info
        Label screenInfo = createScreenInfoLabel();

        topBox.getChildren().addAll(descLabel, trackAllCheckbox, verboseLogCheckbox,
                new Separator(), mainWindowLabel, mainWindowBtnBox, mainWindowStatus,
                new Separator(), storageLabel, storageBtnBox,
                new Separator(), screenInfo);
        return topBox;
    }

    /**
     * Opens a FileChooser so the user can pick a JSON file for dialog-position
     * storage, then hands it to {@link DialogPositionPreferences#setStorageFile}.
     */
    private void chooseStorageFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose dialog position storage file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON files", "*.json"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));

        Path current = DialogPositionPreferences.getStorageFile();
        if (current.getParent() != null && current.getParent().toFile().isDirectory()) {
            chooser.setInitialDirectory(current.getParent().toFile());
        }
        chooser.setInitialFileName(current.getFileName().toString());

        File chosen = chooser.showSaveDialog(stage);
        if (chosen == null) {
            return;
        }
        try {
            DialogPositionPreferences.setStorageFile(chosen.toPath());
            storageFileField.setText(DialogPositionPreferences.getStorageFile().toString());
            logger.info("Dialog position storage file set to: {}", chosen);
        } catch (Exception ex) {
            logger.error("Failed to switch storage file to {}: {}", chosen, ex.getMessage(), ex);
            Alert err = new Alert(Alert.AlertType.ERROR,
                    "Could not use the selected file:\n" + ex.getMessage(),
                    ButtonType.OK);
            err.initOwner(stage);
            err.showAndWait();
        }
    }

    /**
     * Reset storage to the default {@code <QuPath user dir>/dialog-manager/positions.json}.
     */
    private void resetStorageToDefault() {
        try {
            DialogPositionPreferences.setStorageFile(null);
            storageFileField.setText(DialogPositionPreferences.getStorageFile().toString());
            logger.info("Dialog position storage reset to default: {}",
                    DialogPositionPreferences.getStorageFile());
        } catch (Exception ex) {
            logger.error("Failed to reset storage file: {}", ex.getMessage(), ex);
        }
    }

    private Label createScreenInfoLabel() {
        int screenCount = Screen.getScreens().size();
        String screenText = String.format("Detected %d screen%s", screenCount, screenCount == 1 ? "" : "s");

        StringBuilder sb = new StringBuilder(screenText);
        for (int i = 0; i < Screen.getScreens().size(); i++) {
            Screen screen = Screen.getScreens().get(i);
            var bounds = screen.getVisualBounds();
            sb.append(String.format("\n  Screen %d: %.0fx%.0f at (%.0f, %.0f)%s",
                    i + 1,
                    bounds.getWidth(), bounds.getHeight(),
                    bounds.getMinX(), bounds.getMinY(),
                    screen == Screen.getPrimary() ? " [Primary]" : ""));
        }

        Label label = new Label(screenText);
        label.setStyle("-fx-font-size: 11px;");
        label.setTooltip(new Tooltip(sb.toString()));
        return label;
    }

    private ListView<DialogState> createDialogListView() {
        ListView<DialogState> listView = new ListView<>();
        listView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        // Bind to the manager's observable list with sorting
        SortedList<DialogState> sortedList = new SortedList<>(
                manager.getDialogStates(),
                Comparator.comparing(DialogState::isCurrentlyOpen).reversed()
                        .thenComparing(DialogState::title, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
        );
        listView.setItems(sortedList);

        // Custom cell factory
        listView.setCellFactory(lv -> new DialogStateCell());

        // Context menu
        listView.setContextMenu(createContextMenu(listView));

        // Placeholder for empty list
        listView.setPlaceholder(new Label("No dialogs are being tracked.\n\n" +
                "Open some dialogs or enable 'Track all dialogs' above."));

        return listView;
    }

    private ContextMenu createContextMenu(ListView<DialogState> listView) {
        ContextMenu menu = new ContextMenu();

        MenuItem centerItem = new MenuItem("Center on Primary Screen");
        centerItem.setOnAction(e -> {
            DialogState selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.isCurrentlyOpen()) {
                manager.centerDialog(selected.windowId());
            }
        });

        MenuItem bringFrontItem = new MenuItem("Bring to Front");
        bringFrontItem.setOnAction(e -> {
            DialogState selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.isCurrentlyOpen()) {
                manager.bringToFront(selected.windowId());
            }
        });

        MenuItem closeItem = new MenuItem("Close Dialog");
        closeItem.setOnAction(e -> {
            DialogState selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.isCurrentlyOpen()) {
                manager.closeDialog(selected.windowId());
            }
        });

        MenuItem resetItem = new MenuItem("Reset to Default Position");
        resetItem.setOnAction(e -> {
            DialogState selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                manager.resetDialogPosition(selected.windowId());
            }
        });

        // Disable items based on selection
        menu.setOnShowing(e -> {
            DialogState selected = listView.getSelectionModel().getSelectedItem();
            boolean hasSelection = selected != null;
            boolean isOpen = hasSelection && selected.isCurrentlyOpen();

            centerItem.setDisable(!isOpen);
            bringFrontItem.setDisable(!isOpen);
            closeItem.setDisable(!isOpen);
            resetItem.setDisable(!hasSelection);
        });

        menu.getItems().addAll(centerItem, bringFrontItem, closeItem,
                new SeparatorMenuItem(), resetItem);
        return menu;
    }

    private HBox createBottomControls() {
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.setPadding(new Insets(10, 0, 0, 0));

        // Center selected button
        Button centerBtn = new Button("Center");
        centerBtn.setTooltip(new Tooltip("Center the selected dialog on the primary screen"));
        centerBtn.disableProperty().bind(Bindings.createBooleanBinding(
                () -> {
                    DialogState sel = dialogListView.getSelectionModel().getSelectedItem();
                    return sel == null || !sel.isCurrentlyOpen();
                },
                dialogListView.getSelectionModel().selectedItemProperty(),
                manager.getDialogStates()
        ));
        centerBtn.setOnAction(e -> {
            DialogState selected = dialogListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                manager.centerDialog(selected.windowId());
            }
        });

        // Bring to front button
        Button frontBtn = new Button("Bring to Front");
        frontBtn.setTooltip(new Tooltip("Bring the selected dialog to the front"));
        frontBtn.disableProperty().bind(centerBtn.disableProperty());
        frontBtn.setOnAction(e -> {
            DialogState selected = dialogListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                manager.bringToFront(selected.windowId());
            }
        });

        // Reset button
        Button resetBtn = new Button("Reset Position");
        resetBtn.setTooltip(new Tooltip("Remove saved position (dialog will use default position next time)"));
        resetBtn.disableProperty().bind(Bindings.createBooleanBinding(
                () -> dialogListView.getSelectionModel().getSelectedItem() == null,
                dialogListView.getSelectionModel().selectedItemProperty()
        ));
        resetBtn.setOnAction(e -> {
            DialogState selected = dialogListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                manager.resetDialogPosition(selected.windowId());
            }
        });

        // Clear all button
        Button clearAllBtn = new Button("Clear All");
        clearAllBtn.setTooltip(new Tooltip("Remove all saved dialog positions"));
        clearAllBtn.setStyle("-fx-text-fill: #c00;");
        clearAllBtn.setOnAction(e -> {
            DialogPositionPreferences.clearAll();
            manager.getDialogStates().clear();
            logger.info("Cleared all saved dialog positions");
        });

        // Spacer
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        buttonBox.getChildren().addAll(centerBtn, frontBtn, resetBtn, spacer, clearAllBtn);
        return buttonBox;
    }

    /**
     * Custom cell for displaying dialog state.
     */
    private static class DialogStateCell extends ListCell<DialogState> {

        @Override
        protected void updateItem(DialogState state, boolean empty) {
            super.updateItem(state, empty);

            if (empty || state == null) {
                setText(null);
                setGraphic(null);
                setStyle("");
                return;
            }

            // Build display text
            StringBuilder sb = new StringBuilder();

            // Status indicator
            if (state.isCurrentlyOpen()) {
                sb.append("[OPEN] ");
            }

            // Title (fall back to windowId if title is empty)
            String displayTitle = state.title().isEmpty() ? state.windowId() : state.title();
            sb.append(displayTitle.isEmpty() ? "(untitled)" : displayTitle);

            // Off-screen warning
            DialogPositionManager mgr = DialogPositionManager.getInstance();
            if (!mgr.isPositionOnScreen(state) && state.hasValidPosition()) {
                sb.append("  |  [OFF-SCREEN]");
            }

            setText(sb.toString());

            // Style based on state
            if (state.isCurrentlyOpen()) {
                setStyle("-fx-text-fill: #080; -fx-font-weight: bold;");
            } else if (!mgr.isPositionOnScreen(state) && state.hasValidPosition()) {
                setStyle("-fx-text-fill: #c60;");
            } else {
                setStyle("");
            }

            // Tooltip with full details
            setTooltip(createDetailTooltip(state));
        }

        private Tooltip createDetailTooltip(DialogState state) {
            StringBuilder sb = new StringBuilder();
            sb.append("Window ID: ").append(state.windowId().isEmpty() ? "(none)" : state.windowId()).append("\n");
            sb.append("Title: ").append(state.title().isEmpty() ? "(untitled)" : state.title()).append("\n");
            sb.append("Position: (").append(String.format("%.0f", state.x()))
                    .append(", ").append(String.format("%.0f", state.y())).append(")\n");
            sb.append("Size: ").append(String.format("%.0f", state.width()))
                    .append(" x ").append(String.format("%.0f", state.height())).append("\n");
            sb.append("Modality: ").append(state.modality()).append("\n");
            sb.append("Screen Index: ").append(state.screenIndex()).append("\n");
            sb.append("Saved Scale: ").append(String.format("%.2f x %.2f",
                    state.savedScaleX(), state.savedScaleY())).append("\n");
            sb.append("Status: ").append(state.isCurrentlyOpen() ? "OPEN" : "Closed");

            // Add warning if scale has changed
            if (state.hasValidScaleFactors()) {
                Screen primary = Screen.getPrimary();
                if (state.hasScaleChanged(primary.getOutputScaleX(), primary.getOutputScaleY())) {
                    sb.append("\n\n[!] Scale factor differs from current screen");
                }
            }

            Tooltip tooltip = new Tooltip(sb.toString());
            tooltip.setStyle("-fx-font-family: monospace;");
            return tooltip;
        }
    }
}
