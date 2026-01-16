# QuPath Dialog Position Manager

A QuPath extension that remembers and restores dialog window positions across sessions, with automatic recovery for windows that become inaccessible (e.g., when a monitor is disconnected).

## Features

- **Automatic position persistence**: Dialog positions and sizes are saved when closed and restored when reopened
- **Off-screen recovery**: Automatically detects and recovers dialogs positioned on disconnected monitors
- **HiDPI awareness**: Handles display scaling changes and mixed-DPI multi-monitor setups
- **Track all dialogs by default**: Works out-of-the-box with any QuPath dialog

## Installation

1. Download the latest JAR from the releases
2. Drag and drop onto QuPath, or copy to your QuPath extensions folder

## Where to Find the Settings

All Dialog Position Manager controls are located in the **Window** menu:

| Menu Item | Description |
|-----------|-------------|
| **Window > Dialog Position Manager...** | Opens the full management UI |
| **Window > Recover Off-Screen Dialogs** | Quick action to recover all lost dialogs |

## Recovering a Lost Dialog

If a dialog window has moved off-screen or become inaccessible:

### Quick Recovery (All Dialogs)

1. Go to **Window > Recover Off-Screen Dialogs**
2. All off-screen dialogs will be instantly centered on your primary monitor

### Manual Recovery (Specific Dialog)

1. Go to **Window > Dialog Position Manager...**
2. Find the dialog in the list (off-screen dialogs are marked with `[OFF-SCREEN]` in orange)
3. Select it and click **Center** to move it to your primary screen

### Resetting to Default Position

If a dialog keeps appearing in an unwanted location:

1. Go to **Window > Dialog Position Manager...**
2. Find the dialog in the list
3. Click **Reset Position** to clear its saved position
4. The next time the dialog opens, QuPath will use its default positioning

### Nuclear Option

To clear ALL saved dialog positions and start fresh:

1. Go to **Window > Dialog Position Manager...**
2. Click **Clear All** (bottom right, in red)

## Known Limitations

The extension works well with most QuPath dialogs, but there are some cases where position restoration may not work:

### Modal Dialogs

Dialogs that block the UI (like Cell Detection, other analysis dialogs) have strict timing requirements. Position restoration usually works, but occasionally QuPath may override the position. If this happens, manually reposition the dialog and it should be remembered next time.

### Dialogs with Dynamic Titles

Dialogs whose titles change based on context (e.g., titles that include the current image name or file path) won't be recognized between sessions because the title is used as the identifier. Each unique title is treated as a separate dialog.

### Dialogs That Force Centering

Some dialogs are programmed to center themselves on their parent window every time they open. The extension attempts to override this by re-applying the saved position after the dialog shows, but some dialogs may resist.

### First-Time Dialogs

A dialog must be opened, positioned, and closed at least once before its position will be remembered. The first time you open any dialog, it will appear in QuPath's default location.

## Dialog Position Manager UI

The management UI (**Window > Dialog Position Manager...**) shows:

- **Green `[OPEN]` indicator**: Dialog is currently visible
- **Orange `[OFF-SCREEN]` warning**: Saved position is not visible on any connected monitor
- **Position and size**: Current or last-known coordinates
- **`[Modal]` tag**: Dialog blocks other windows when open

### Available Actions

| Button | Description |
|--------|-------------|
| **Center** | Move selected dialog to center of primary screen |
| **Bring to Front** | Raise selected dialog above other windows |
| **Reset Position** | Clear saved position (dialog will use default next time) |
| **Clear All** | Remove all saved positions |

Right-click on any dialog for a context menu with additional options.

## How It Works

### Position Storage

Dialog positions are stored in QuPath's preferences system:

- **Windows**: `%APPDATA%\QuPath\` (preferences file)
- **macOS**: `~/Library/Preferences/` (QuPath preferences)
- **Linux**: `~/.java/.userPrefs/` or equivalent

Positions are stored as JSON under the preference key `dialogManager.positions`, including:
- Window position (x, y coordinates)
- Window size (width, height)
- Screen index (which monitor)
- Display scale factors (for HiDPI handling)

### Position Restoration Process

When a tracked dialog opens:

1. The extension checks for a saved position matching the dialog's title
2. If found, validates that the position is visible on a connected monitor
3. Sets the position BEFORE the dialog becomes visible (to prevent flicker)
4. Re-applies the position AFTER the dialog shows (in case QuPath overrides it)
5. If the saved position is off-screen, centers the dialog on the best available monitor

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
