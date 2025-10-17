# Background File Watcher

An IntelliJ IDEA plugin that watches files in the background and triggers "Synchronize All From Disk" and build when changes are detected.

## Features

- **Watches files in the background**: Monitors your project directory for file changes using Java's WatchService API
- **Smart Filtering**: Uses IntelliJ's ProjectFileIndex to intelligently filter files
  - Ignores build output directories (target, build, out, etc.)
  - Focuses on actual source code and resource files
  - Respects IntelliJ's project structure settings
- **Configurable Filters**: Fine-tune which files trigger reloads
  - Check if file is in project content
  - Check if file is excluded
  - Check if file is in source roots
  - Check if file is in test source roots
  - Check if file is in library
- **Regex Path Filters**: Define custom regex patterns to match specific file paths
- **Auto Reload**: Automatically triggers "Synchronize All From Disk" when changes are detected
- **Auto Rebuild**: Automatically triggers project build after synchronization
- **Debouncing**: Configurable delay to batch multiple file changes (default: 500ms)
- **Event Tracking**: Tool window showing:
  - Processed events with timestamps and matched rules
  - Ignored events with reasons
- **Project-Level Settings**: Each project has its own independent configuration stored in `.idea/workspace.xml`

## Building the Plugin

To build the plugin, you need:
- JDK 17 or later
- Gradle (or use the Gradle wrapper if configured)

Build the plugin:
```bash
./gradlew buildPlugin
```

The built plugin will be in `build/distributions/`.

## Installing the Plugin

1. Build the plugin as described above
2. In IntelliJ IDEA, go to `Settings/Preferences` → `Plugins`
3. Click the gear icon and select `Install Plugin from Disk...`
4. Select the ZIP file from `build/distributions/`
5. Restart IntelliJ IDEA

## Running the Plugin in Development

To test the plugin in a sandboxed IntelliJ IDEA instance:
```bash
./gradlew runIde
```

## How It Works

1. When a project is opened, the `ProjectOpenListener` is triggered
2. The `FileWatcherService` is initialized and starts watching the project directory
3. The service recursively registers watchers for all directories except excluded ones (using IntelliJ's ProjectFileIndex)
4. When a file change is detected, it checks against configured filters and regex patterns
5. If relevant changes are detected, it schedules a reload (with configurable debounce delay)
6. The reload triggers IntelliJ's synchronization action to refresh files from disk
7. Optionally triggers a project rebuild after synchronization

## Configuration

Navigate to **Settings/Preferences → Tools → File Watcher Auto Reload** to configure:

1. **File Filtering Options**:
   - Check is in content
   - Check is excluded
   - Check is in source
   - Check is in test source
   - Check is in library

2. **Regex Path Filters**:
   - Define custom regex patterns (one per line)
   - Patterns are validated on save

3. **Auto Actions**:
   - Enable/disable automatic reload from disk
   - Enable/disable automatic project rebuild

4. **Debounce Delay**:
   - Configure delay in milliseconds (default: 500ms)

## Tool Window

The plugin adds a "File Watcher" tool window at the bottom of the IDE with two tabs:

- **Processed Events**: Shows files that triggered reload/rebuild with matched rules and timestamps
- **Ignored Events**: Shows files that were ignored with reasons and timestamps

## Use Cases

This plugin is particularly useful when:
- Files are being modified by external tools or processes
- You're using code generators that modify source files
- You're syncing files from external sources
- You need immediate feedback when files change outside the IDE

## Troubleshooting

Check the IntelliJ IDEA log for messages from the plugin:
- `Help` → `Show Log in Finder/Explorer`

The plugin logs its activities with the prefix `FileWatcherService` and `ProjectOpenListener`.

## Support Development

If you find this plugin useful, please consider supporting its development:

[![Donate](https://img.shields.io/badge/Donate-PayPal-blue.svg)](https://www.paypal.com/donate/?hosted_button_id=C9U54KULFG48C)

**[Donate via PayPal](https://www.paypal.com/donate/?hosted_button_id=C9U54KULFG48C)**

Your support helps maintain and improve this plugin!

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
