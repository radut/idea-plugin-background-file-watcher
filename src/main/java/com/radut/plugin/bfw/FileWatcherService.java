package com.radut.plugin.bfw;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.content.Content;
import com.radut.plugin.bfw.settings.FileWatcherSettings;
import com.radut.plugin.bfw.settings.FileWatcherState;
import com.radut.plugin.bfw.toolwindow.FileWatcherToolWindowContent;
import com.radut.plugin.bfw.toolwindow.FileWatcherToolWindowFactory;
import com.radut.plugin.bfw.watcher.RecursiveDirectoryWatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import javax.swing.JFrame;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FileWatcherService implements Disposable {
    private static final Logger LOG = Logger.getInstance(FileWatcherService.class);

    private static final String SYNC_ACTION_ID = "Synchronize";
    private static final String BUILD_ACTION_ID = "CompileDirty";
    private static final String SAVE_ACTION_ID = "SaveAll";
    private static final String TOOL_WINDOW_ID = "File Watcher";
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final Project project;
    private volatile boolean running = false;
    private volatile boolean scheduledActions = false;
    private final ScheduledExecutorService debounceExecutor;
    private final Object controlLock = new Object();

    // Multiple watchers for different directory sets
    private final List<RecursiveDirectoryWatcher> activeWatchers = new ArrayList<>();

    // Listeners for state changes
    private final List<StateChangeListener> stateChangeListeners = new ArrayList<>();

    /**
     * Listener interface for file watcher state changes.
     */
    public interface StateChangeListener {
        void onStateChanged();
    }

    /**
     * Register a listener for state changes.
     */
    public void addStateChangeListener(StateChangeListener listener) {
        synchronized (stateChangeListeners) {
            stateChangeListeners.add(listener);
        }
    }

    /**
     * Unregister a listener for state changes.
     */
    public void removeStateChangeListener(StateChangeListener listener) {
        synchronized (stateChangeListeners) {
            stateChangeListeners.remove(listener);
        }
    }

    /**
     * Notify all listeners of state changes.
     */
    private void notifyStateChanged() {
        ApplicationManager.getApplication().invokeLater(() -> {
            synchronized (stateChangeListeners) {
                for (StateChangeListener listener : stateChangeListeners) {
                    listener.onStateChanged();
                }
            }
        });
    }

    public FileWatcherService(@NotNull Project project) {
        this.project = project;
        this.debounceExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    public void startWatching() {
        synchronized (controlLock) {
            if (running) {
                LOG.warn("File watcher already running for project: " + project.getName());
                return;
            }

            if (project.isDisposed()) {
                LOG.warn("Cannot start file watcher - project is disposed: " + project.getName());
                return;
            }

            try {
                LOG.info("Starting file watcher for project: " + project.getName());

                running = true;

                // Start watchers based on current settings
                startWatchersBasedOnSettings();

                LOG.info("File watcher started successfully for project: " + project.getName());

                saveAndSyncStateToProjectWorkspace(true);

            } catch (Exception e) {
                LOG.error("Unexpected error starting file watcher for project: " + project.getName(), e);
                running = false;
                stopAllWatchers();
            }
        }
    }

    /**
     * Starts the appropriate watchers based on current settings.
     */
    private synchronized void startWatchersBasedOnSettings() {
        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);

        RecursiveDirectoryWatcher.WatchListener listener = (final WatchEvent.Kind<?> kind, final Path fileName) -> onFileChangesDetected(kind, fileName);

        try {
            // Watch source folders if enabled
            if (settings.isInSource()) {
                List<VirtualFile> sourceRoots = getSourceRoots();
                for (VirtualFile sourceRoot : sourceRoots) {
                    Path sourcePath = Paths.get(sourceRoot.getPath());
                    RecursiveDirectoryWatcher watcher = new RecursiveDirectoryWatcher(
                            sourcePath,
                            Collections.emptySet(),
                            listener
                    );
                    watcher.start();
                    activeWatchers.add(watcher);
                    LOG.info("Started watcher for source root: " + sourcePath);
                }
            }

            // Watch test source folders if enabled
            if (settings.isInTestSource()) {
                List<VirtualFile> testSourceRoots = getTestSourceRoots();
                for (VirtualFile testSourceRoot : testSourceRoots) {
                    Path testSourcePath = Paths.get(testSourceRoot.getPath());
                    RecursiveDirectoryWatcher watcher = new RecursiveDirectoryWatcher(
                            testSourcePath,
                            Collections.emptySet(),
                            listener
                    );
                    watcher.start();
                    activeWatchers.add(watcher);
                    LOG.info("Started watcher for test source root: " + testSourcePath);
                }
            }

            // Watch project content (excluding source roots, test source roots, and excluded folders)
            if (settings.isInContent()) {
                List<VirtualFile> contentRoots = getContentRoots();
                Set<Path> exclusions = new HashSet<>();

                // Add source roots to exclusions
                for (VirtualFile sourceRoot : getSourceRoots()) {
                    exclusions.add(Paths.get(sourceRoot.getPath()));
                }

                // Add test source roots to exclusions
                for (VirtualFile testSourceRoot : getTestSourceRoots()) {
                    exclusions.add(Paths.get(testSourceRoot.getPath()));
                }

                // Add excluded folders to exclusions
                for (VirtualFile excludedFolder : getExcludedFolders()) {
                    exclusions.add(Paths.get(excludedFolder.getPath()));
                }

                for (VirtualFile contentRoot : contentRoots) {
                    Path contentPath = Paths.get(contentRoot.getPath());
                    RecursiveDirectoryWatcher watcher = new RecursiveDirectoryWatcher(
                            contentPath,
                            exclusions,
                            listener
                    );
                    watcher.start();
                    activeWatchers.add(watcher);
                    LOG.info("Started watcher for content root: " + contentPath + " with " + exclusions.size() + " exclusions");
                }
            }

            // Notify listeners that watchers have been started
            notifyStateChanged();

        } catch (IOException e) {
            LOG.error("Failed to start watchers", e);
            stopAllWatchers();
            throw new RuntimeException("Failed to start file watchers", e);
        }
    }

    /**
     * Restarts all watchers with the current settings. Call this when preferences are changed.
     */
    public void restartWatchers() {
        synchronized (controlLock) {
            LOG.info("Restarting file watchers for project: " + project.getName());

            // Stop existing watchers
            stopAllWatchers();

            // Only restart if watching is enabled
            if (running) {
                // Start new watchers with updated settings
                startWatchersBasedOnSettings();
                LOG.info("File watchers restarted successfully");
            }
        }
    }

    /**
     * Stops all active watchers.
     */
    private synchronized void stopAllWatchers() {
        for (RecursiveDirectoryWatcher watcher : activeWatchers) {
            try {
                watcher.stop();
            } catch (Exception e) {
                LOG.warn("Error stopping watcher for: " + watcher.getRootPath(), e);
            }
        }
        activeWatchers.clear();

        // Notify listeners that watchers have been stopped
        notifyStateChanged();
    }

    /**
     * Called when file changes are detected by any watcher.
     */
    private void onFileChangesDetected(final WatchEvent.Kind<?> kind, final Path fileName) {
        LOG.info("File changes detected: " + kind + " - " + fileName);

        FileCheckResult fileCheckResult = checkFile(fileName);
        if (fileCheckResult != null) {
            if (fileCheckResult.isShouldProcess()) {
                logToToolWindow(kind.name(), fileCheckResult.getMatchedRule(), fileCheckResult.getDetails(), getRelativePath(fileName));
                scheduleActions();
            } else {
                logIgnoredToToolWindow(kind.name(), fileCheckResult.getMatchedRule(), fileCheckResult.getDetails(), getRelativePath(fileName));
            }
        }
    }

    private void saveAndSyncStateToProjectWorkspace(boolean newStateBoolean) {
        ApplicationManager.getApplication().invokeLater(() -> {
            FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
            FileWatcherState newState = settings.getState();
            if (newState != null) {
                newState.setEnabled(newStateBoolean);
                settings.loadState(newState);
                LOG.info("File watcher enabled state set to " + newStateBoolean + " for project: " + project.getName());
                triggerAction(SAVE_ACTION_ID);
            }
        });
    }

    public void toggleWatchingState() {
        synchronized (controlLock) {
            if (running) {
                stopWatching();
            } else {
                startWatching();
            }
        }
    }

    public void stopWatching() {
        synchronized (controlLock) {
            if (!running) {
                LOG.info("File watcher is not running");
                return;
            }

            LOG.info("Stopping file watcher for project: " + project.getName());
            running = false;

            stopAllWatchers();

            saveAndSyncStateToProjectWorkspace(false);

            LOG.info("Stopped watching files in project: " + project.getName());
        }
    }

    public boolean isRunning() {
        return running;
    }

    private List<VirtualFile> getSourceFoldersByType(Predicate<SourceFolder> filter) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<VirtualFile>>) () -> {
            List<VirtualFile> roots = new ArrayList<>();
            ModuleManager moduleManager = ModuleManager.getInstance(project);

            for (Module module : moduleManager.getModules()) {
                ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
                for (var contentEntry : rootManager.getContentEntries()) {
                    for (SourceFolder sourceFolder : contentEntry.getSourceFolders()) {
                        if (filter.test(sourceFolder)) {
                            VirtualFile file = sourceFolder.getFile();
                            if (file != null) {
                                roots.add(file);
                            }
                        }
                    }
                }
            }
            return roots;
        });
    }

    private List<VirtualFile> getSourceRoots() {
        return getSourceFoldersByType(sf -> sf.getRootType() == JavaSourceRootType.SOURCE);
    }

    private List<VirtualFile> getTestSourceRoots() {
        return getSourceFoldersByType(sf -> sf.getRootType() == JavaSourceRootType.TEST_SOURCE);
    }

    private List<VirtualFile> getExcludedFolders() {
        return ApplicationManager.getApplication().runReadAction((Computable<List<VirtualFile>>) () -> {
            List<VirtualFile> roots = new ArrayList<>();
            ModuleManager moduleManager = ModuleManager.getInstance(project);
            for (Module module : moduleManager.getModules()) {
                ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
                for (var contentEntry : rootManager.getContentEntries()) {
                    roots.addAll(Arrays.asList(contentEntry.getExcludeFolderFiles()));
                }
            }
            return roots;
        });
    }

    private List<VirtualFile> getContentRoots() {
        return ApplicationManager.getApplication().runReadAction((Computable<List<VirtualFile>>) () -> {
            List<VirtualFile> roots = new ArrayList<>();
            ModuleManager moduleManager = ModuleManager.getInstance(project);

            for (Module module : moduleManager.getModules()) {
                ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
                for (VirtualFile contentRoot : rootManager.getContentRoots()) {
                    roots.add(contentRoot);
                }
            }
            return roots;
        });
    }

    /**
     * Checks if path matches any pattern in the filter string.
     */
    private String matchesAnyPattern(String pathStr, String filterString) {
        if (filterString == null || filterString.trim().isEmpty()) {
            return null;
        }

        String[] patterns = filterString.split("\n");
        for (String patternStr : patterns) {
            patternStr = patternStr.trim();
            if (patternStr.isEmpty()) {
                continue;
            }

            try {
                Pattern pattern = Pattern.compile(patternStr);
                if (pattern.matcher(pathStr).find()) {
                    return patternStr;
                }
            } catch (PatternSyntaxException e) {
                LOG.warn("Invalid regex pattern: " + patternStr, e);
            }
        }
        return null;
    }

    private FileCheckResult checkFile(Path path) {
        return ApplicationManager.getApplication().runReadAction((Computable<FileCheckResult>) () -> {
            FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
            String pathStr = path.toString();
            String basePath = project.getBasePath();
            if (basePath != null && pathStr.startsWith(basePath)) {
                pathStr = pathStr.substring(basePath.length() + 1);
            }

            // Check ignored regex filters first
            String ignoredMatch = matchesAnyPattern(pathStr, settings.getIgnoredRegexFilters());
            if (ignoredMatch != null) {
                return new FileCheckResult(false, "Ignore Regex", ignoredMatch);
            }

            if (fileIsPartOf(path, getSourceRoots())) {
                return new FileCheckResult(settings.isInSource(), "InSource", null);
            }

            if (fileIsPartOf(path, getTestSourceRoots())) {
                return new FileCheckResult(settings.isInTestSource(), "InTestSource", null);
            }

            if (fileIsPartOf(path, getContentRoots())) {
                if (!fileIsPartOf(path, getSourceRoots())
                    && !fileIsPartOf(path, getTestSourceRoots())
                    && !fileIsPartOf(path, getExcludedFolders())) {

                    if (isNotBlank(settings.getPathRegexFilters())) {
                        // Apply included regex filters
                        String includedMatch = matchesAnyPattern(pathStr, settings.getPathRegexFilters());
                        if (includedMatch != null) {
                            return new FileCheckResult(true, "InProjectContent Regex: " + includedMatch, null);
                        }
                    }
                    return new FileCheckResult(settings.isInContent(), "InProjectContent", null);
                }
            }


            // No filters matched - reject the file
            return new FileCheckResult(false, "None", "No filters matched");
        });
    }

    private boolean fileIsPartOf(final Path path, final List<VirtualFile> virtualFile) {
        return virtualFile.stream()
                .anyMatch(folder -> path.startsWith(folder.getPath()));
    }

    private String getRelativePath(Path fullPath) {
        String basePath = project.getBasePath();
        if (basePath != null) {
            Path projectPath = Paths.get(basePath);
            try {
                Path relativePath = projectPath.relativize(fullPath);
                return relativePath.toString();
            } catch (IllegalArgumentException e) {
                return fullPath.toString();
            }
        }
        return fullPath.toString();
    }

    private FileWatcherToolWindowContent getToolWindowContent() {
        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

        if (toolWindow == null) {
            LOG.warn("Tool window '" + TOOL_WINDOW_ID + "' not found");
            return null;
        }

        Content content = toolWindow.getContentManager().getContent(0);
        if (content == null) {
            LOG.warn("Tool window content is null");
            return null;
        }

        FileWatcherToolWindowContent toolWindowContent = content.getUserData(FileWatcherToolWindowFactory.TOOL_WINDOW_CONTENT_KEY);
        if (toolWindowContent == null) {
            LOG.warn("Tool window content not found in user data - tool window may not be initialized yet");
        }

        return toolWindowContent;
    }

    private void logToToolWindow(String event, String matchedRule, String details, String filePath) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                FileWatcherToolWindowContent content = getToolWindowContent();
                if (content != null) {
                    content.addEvent(true, event, matchedRule + (isNotBlank(details) ? ": " + details : ""), filePath);
                }
            } catch (Exception e) {
                LOG.warn("Error logging to tool window", e);
            }
        });
    }

    private void logIgnoredToToolWindow(String event, String matchedRule, String details, String filePath) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                FileWatcherToolWindowContent content = getToolWindowContent();
                if (content != null) {
                    content.addEvent(false, event, matchedRule + (isNotBlank(details) ? ": " + details : ""), filePath);
                }
            } catch (Exception e) {
                LOG.warn("Error logging ignored event to tool window", e);
            }
        });
    }

    private void scheduleActions() {
        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
        if (settings.isAutoReloadEnabled() || settings.isAutoRebuildEnabled()) {

            synchronized (this) {
                if (scheduledActions) {
                    return; // Already scheduled
                }
                scheduledActions = true;
            }

            int debounceDelay = settings.getDebounceDelayMs();
            debounceExecutor.schedule(() -> {
                try {
                    if (settings.isAutoReloadEnabled()) {
                        triggerAction(SYNC_ACTION_ID);
                    }
                    if (settings.isAutoRebuildEnabled()) {
                        triggerAction(BUILD_ACTION_ID);
                    }
                } finally {
                    synchronized (this) {
                        scheduledActions = false;
                    }
                }
            }, debounceDelay, TimeUnit.MILLISECONDS);
        }
    }

    private void triggerAction(String actionId) {
        if (project.isDisposed()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                AnAction action = ActionManager.getInstance().getAction(actionId);
                if (action == null) {
                    LOG.error("Could not find " + actionId + " action");
                    return;
                }
                // Anchor the action's data context on the project frame rather than letting the
                // platform derive one from the focused component: the whole point of this plugin
                // is to react while the IDE window sits in the background, where nothing has
                // focus. tryToExecute() builds the context from this component when it is given
                // one, and is the only action-invoking API that is not deprecated anywhere in
                // the 233..262 range we support.
                JFrame frame = WindowManager.getInstance().getFrame(project);
                if (frame == null) {
                    LOG.warn("No frame for project " + project.getName() + ", skipping " + actionId);
                    return;
                }
                LOG.warn("==> " + actionId + " ACTION TRIGGERED - Starting for project: " + project.getName());
                ActionManager.getInstance().tryToExecute(action, null, frame, ActionPlaces.UNKNOWN, true);
                LOG.warn("==> " + actionId + " COMPLETED for project: " + project.getName());
            } catch (Exception e) {
                LOG.error("Error Triggering " + actionId, e);
            }
            // A background file change must not wait for a modal dialog to be dismissed.
        }, ModalityState.nonModal(), project.getDisposed());
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public void dispose() {
        if (running) {
            stopWatching();
        }

        debounceExecutor.shutdown();
        try {
            if (!debounceExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                debounceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            debounceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOG.info("File watcher service disposed for project: " + project.getName());
    }
}
