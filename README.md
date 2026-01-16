# QuPath Dialog Position Manager

A QuPath extension that remembers and restores dialog window positions across sessions, with automatic recovery for windows that become inaccessible (e.g., when a monitor is disconnected).

## Features

- **Automatic position persistence**: Dialog positions and sizes are saved when closed and restored when reopened
- **Off-screen recovery**: Automatically detects and recovers dialogs that are positioned on disconnected monitors
- **HiDPI awareness**: Handles display scaling changes and mixed-DPI multi-monitor setups
- **Selective tracking**: Track all dialogs or only specific ones you care about

## Installation

1. Download the latest JAR from the releases
2. Drag and drop onto QuPath, or copy to your QuPath extensions folder

## Usage

### Menu Items

The extension adds two items to the **Window** menu:

- **Dialog Position Manager...** - Opens a UI to view and manage all tracked dialogs
- **Recover Off-Screen Dialogs** - Instantly centers any dialogs that are currently off-screen

### Recovering a Lost Window

If a dialog window has moved off-screen (common when disconnecting an external monitor):

1. Go to **Window > Recover Off-Screen Dialogs**
2. All off-screen dialogs will be centered on your primary monitor

Alternatively, use the Dialog Position Manager UI to:
- See which dialogs are currently open or have saved positions
- Center specific dialogs
- Reset a dialog to its default position
- Close dialogs remotely

### Resetting a Dialog Position

To reset a specific dialog to its default position:

1. Go to **Window > Dialog Position Manager...**
2. Find the dialog in the list
3. Click **Reset** to clear its saved position

The next time the dialog opens, it will use QuPath's default positioning.

## Default Tracked Dialogs

By default, the extension tracks these common QuPath dialogs:

- Brightness & Contrast
- Script editor
- Log
- Command list
- Measurement table
- Preferences
- Objects / Annotations / Detections
- Measurement maps

You can enable tracking of all dialogs through the Dialog Position Manager UI.

## How It Works

### Position Storage

Dialog positions are stored in **QuPath's preferences system**, which persists data in:

- **Windows**: `%APPDATA%\QuPath\` (in the preferences file)
- **macOS**: `~/Library/Preferences/` (QuPath preferences plist)
- **Linux**: `~/.java/.userPrefs/` or equivalent

The positions are stored as JSON under the preference key `dialogManager.positions`. Each dialog entry includes:

- Window position (x, y coordinates)
- Window size (width, height)
- Screen index (which monitor it was on)
- Display scale factors (for HiDPI handling)

### Validation on Restore

When a dialog reopens, the extension:

1. Checks if the saved position is still visible on any connected monitor
2. Verifies that at least 100 pixels of the window would be accessible
3. If the position is invalid (monitor disconnected, etc.), centers the dialog on the best available screen
4. Detects display scale changes and handles them appropriately

### Window Tracking

The extension monitors JavaFX's window list and attaches listeners to track position changes. When a tracked window closes, its final position is saved to preferences.

## Building from Source

```bash
./gradlew build
```

The extension JAR will be in `build/libs/`.

## Requirements

- QuPath 0.6.0 or later
- Java 21+

## License

This extension is provided under the same license as QuPath.
