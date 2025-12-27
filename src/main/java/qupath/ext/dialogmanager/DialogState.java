package qupath.ext.dialogmanager;

import javafx.stage.Modality;
import java.util.Objects;

/**
 * Immutable record representing the saved state of a dialog window.
 * <p>
 * Stores position, size, and metadata needed to restore and manage dialog windows.
 * The windowId is typically derived from the dialog title but can be customized
 * for dialogs with dynamic titles.
 * <p>
 * <b>HiDPI Considerations:</b> Coordinates are stored in JavaFX virtual (scaled) units,
 * not physical pixels. The scale factors at save time are recorded to help detect
 * potential issues when display configuration changes between sessions.
 *
 * @param windowId Unique identifier for this window (usually the title)
 * @param title Display title of the window
 * @param x Virtual X coordinate of window's top-left corner
 * @param y Virtual Y coordinate of window's top-left corner
 * @param width Window width in virtual units
 * @param height Window height in virtual units
 * @param modality Window modality type (NONE, WINDOW_MODAL, APPLICATION_MODAL)
 * @param isCurrentlyOpen Whether the window is currently visible
 * @param screenIndex Index of the screen this window was last on
 * @param savedScaleX Output scale factor (X) when position was saved
 * @param savedScaleY Output scale factor (Y) when position was saved
 */
public record DialogState(
        String windowId,
        String title,
        double x,
        double y,
        double width,
        double height,
        Modality modality,
        boolean isCurrentlyOpen,
        int screenIndex,
        double savedScaleX,
        double savedScaleY
) {

    /**
     * Create a DialogState with default values for optional fields.
     */
    public DialogState(String windowId, String title, double x, double y, double width, double height) {
        this(windowId, title, x, y, width, height, Modality.NONE, false, 0, 1.0, 1.0);
    }

    /**
     * Create a DialogState without scale factors (for backward compatibility).
     */
    public DialogState(String windowId, String title, double x, double y, double width, double height,
                       Modality modality, boolean isCurrentlyOpen, int screenIndex) {
        this(windowId, title, x, y, width, height, modality, isCurrentlyOpen, screenIndex, 1.0, 1.0);
    }

    /**
     * Create an updated copy with new position values.
     */
    public DialogState withPosition(double newX, double newY) {
        return new DialogState(windowId, title, newX, newY, width, height, modality, isCurrentlyOpen,
                screenIndex, savedScaleX, savedScaleY);
    }

    /**
     * Create an updated copy with new size values.
     */
    public DialogState withSize(double newWidth, double newHeight) {
        return new DialogState(windowId, title, x, y, newWidth, newHeight, modality, isCurrentlyOpen,
                screenIndex, savedScaleX, savedScaleY);
    }

    /**
     * Create an updated copy with updated open status.
     */
    public DialogState withOpenStatus(boolean open) {
        return new DialogState(windowId, title, x, y, width, height, modality, open,
                screenIndex, savedScaleX, savedScaleY);
    }

    /**
     * Create an updated copy with the screen index where this dialog was last seen.
     */
    public DialogState withScreenIndex(int index) {
        return new DialogState(windowId, title, x, y, width, height, modality, isCurrentlyOpen,
                index, savedScaleX, savedScaleY);
    }

    /**
     * Create an updated copy with scale factors.
     */
    public DialogState withScaleFactors(double scaleX, double scaleY) {
        return new DialogState(windowId, title, x, y, width, height, modality, isCurrentlyOpen,
                screenIndex, scaleX, scaleY);
    }

    /**
     * Check if this dialog state represents a valid position (not default/unset).
     */
    public boolean hasValidPosition() {
        return Double.isFinite(x) && Double.isFinite(y) && x >= -10000 && y >= -10000;
    }

    /**
     * Check if this dialog state represents a valid size.
     */
    public boolean hasValidSize() {
        return width > 0 && height > 0 && Double.isFinite(width) && Double.isFinite(height);
    }

    /**
     * Returns true if this is a modal dialog that blocks other windows.
     */
    public boolean isModal() {
        return modality != Modality.NONE;
    }

    /**
     * Check if the saved scale factors differ significantly from the given current scales.
     * A difference of more than 1% is considered significant.
     */
    public boolean hasScaleChanged(double currentScaleX, double currentScaleY) {
        return Math.abs(savedScaleX - currentScaleX) > 0.01 ||
               Math.abs(savedScaleY - currentScaleY) > 0.01;
    }

    /**
     * Check if saved scale factors are valid (greater than 0).
     */
    public boolean hasValidScaleFactors() {
        return savedScaleX > 0 && savedScaleY > 0 &&
               Double.isFinite(savedScaleX) && Double.isFinite(savedScaleY);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DialogState that)) return false;
        return Objects.equals(windowId, that.windowId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(windowId);
    }

    @Override
    public String toString() {
        return String.format("DialogState[%s at (%.0f, %.0f) size %.0fx%.0f scale=%.2fx%.2f%s]",
                windowId, x, y, width, height, savedScaleX, savedScaleY,
                isCurrentlyOpen ? " OPEN" : "");
    }
}
