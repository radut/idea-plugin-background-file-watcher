package com.intellij.plugin.forcereload;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.plugin.forcereload.settings.FileWatcherSettings;
import com.intellij.plugin.forcereload.toolwindow.FileWatcherToolWindowContent;
import com.intellij.plugin.forcereload.toolwindow.FileWatcherToolWindowFactory;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FileWatcherService implements Disposable {
    private static final Logger LOG = Logger.getInstance(FileWatcherService.class);

    private final Project project;
    private WatchService watchService;
    private final Map<WatchKey, Path> watchKeys = new ConcurrentHashMap<>();
    private Thread watchThread;
    private volatile boolean running = false;
    private final ScheduledExecutorService debounceExecutor;
    private volatile boolean reloadScheduled = false;

    public FileWatcherService(@NotNull Project project) {
        this.project = project;
        this.debounceExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    public void startWatching() {
        if (running) {
            LOG.info("File watcher already running");
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            String basePath = project.getBasePath();
            if (basePath == null) {
                LOG.warn("Project base path is null, cannot start watching");
                return;
            }

            Path projectPath = Paths.get(basePath);
            registerDirectories(projectPath);

            running = true;
            watchThread = new Thread(this::watchForChanges, "FileWatcher-" + project.getName());
            watchThread.setDaemon(true);
            watchThread.start();

            LOG.info("Started watching files in project: " + project.getName());
        } catch (IOException e) {
            LOG.error("Failed to start file watching", e);
        }
    }

    private void registerDirectories(Path root) throws IOException {
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Use read action to access VFS safely
                return ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<FileVisitResult>) () -> {
                    // Use IntelliJ's VFS to check if this directory should be excluded
                    VirtualFile vFile = VirtualFileManager.getInstance().findFileByNioPath(dir);

                    if (vFile != null) {
                        // Check if this is an excluded directory (build output, etc.)
                        if (fileIndex.isExcluded(vFile)) {
                            LOG.debug("Skipping excluded directory: " + dir);
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        // Skip .git and .idea directories explicitly
                        String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                        if (dirName.equals(".git") || dirName.equals(".idea")) {
                            LOG.debug("Skipping special directory: " + dir);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }

                    try {
                        WatchKey key = dir.register(
                                watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE
                        );
                        watchKeys.put(key, dir);
                        LOG.debug("Registered watch for directory: " + dir);
                    } catch (IOException e) {
                        LOG.warn("Failed to register watch for directory: " + dir, e);
                    }

                    return FileVisitResult.CONTINUE;
                });
            }
        });
    }

    private void watchForChanges() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.poll(200, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            Path dir = watchKeys.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            boolean hasRelevantChanges = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path fileName = pathEvent.context();
                Path fullPath = dir.resolve(fileName);

                // Check if this file should trigger a reload
                FileCheckResult checkResult = checkFile(fullPath);
                if (checkResult.shouldProcess) {
                    // Format event type: CREATE/MODIFY/DELETE
                    String changeType = kind.name().replace("ENTRY_", "");
                    LOG.info("Detected " + changeType + " in: " + fullPath + " [" + checkResult.matchedRule + "]");
                    logToToolWindow(changeType, checkResult.matchedRule, fullPath.toString());
                    hasRelevantChanges = true;
                } else {
                    // Log ignored event
                    logIgnoredToToolWindow(checkResult.ignoreReason, "N/A", fullPath.toString());
                }

                // If a new directory was created, register it for watching
                if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
                    try {
                        registerDirectories(fullPath);
                    } catch (IOException e) {
                        LOG.warn("Failed to register new directory: " + fullPath, e);
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                watchKeys.remove(key);
            }

            if (hasRelevantChanges) {
                scheduleReload();
            }
        }
    }

    private static class FileCheckResult {
        boolean shouldProcess;
        String matchedRule;
        String ignoreReason;

        FileCheckResult(boolean shouldProcess, String matchedRule, String ignoreReason) {
            this.shouldProcess = shouldProcess;
            this.matchedRule = matchedRule;
            this.ignoreReason = ignoreReason;
        }
    }

    private FileCheckResult checkFile(Path path) {
        // This method needs read access to query VFS
        return ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<FileCheckResult>) () -> {
            // Use IntelliJ's ProjectFileIndex to determine if file is part of the project
            VirtualFile vFile = VirtualFileManager.getInstance().findFileByNioPath(path);

            FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
            String pathStr = path.toString();

            // First, check ignored regex filters - if matches any, ignore the file
            String ignoredRegexFilters = settings.getIgnoredRegexFilters();
            if (ignoredRegexFilters != null && !ignoredRegexFilters.trim().isEmpty()) {
                String[] patterns = ignoredRegexFilters.split("\n");
                for (String patternStr : patterns) {
                    patternStr = patternStr.trim();
                    if (patternStr.isEmpty()) {
                        continue;
                    }

                    try {
                        Pattern pattern = Pattern.compile(patternStr);
                        if (pattern.matcher(pathStr).find()) {
                            return new FileCheckResult(false, "Ignore Regex", "Matched ignored regex: " + patternStr);
                        }
                    } catch (PatternSyntaxException e) {
                        LOG.warn("Invalid ignored regex pattern: " + patternStr, e);
                    }
                }
            }


            // Check if included regex is configured
            String regexFilters = settings.getPathRegexFilters();
            boolean hasIncludedRegex = regexFilters != null && !regexFilters.trim().isEmpty();

            // Apply checkbox filters - if enabled, check if they match and return immediately
            if (settings.isInContent()) {
                if (vFile != null && fileIndex.isInContent(vFile)) {
                    return new FileCheckResult(true, "InContent", null);
                }
            }

            if (settings.isInSource()) {
                if (vFile != null && fileIndex.isInSource(vFile)) {
                    return new FileCheckResult(true, "InSource", null);
                }
            }

            if (settings.isInTestSource()) {
                if (vFile != null && fileIndex.isInTestSourceContent(vFile)) {
                    return new FileCheckResult(true, "InTestSource", null);
                }
            }

            if (settings.isInGeneratedSource()) {
                if (vFile != null && fileIndex.isInGeneratedSources(vFile)) {
                    return new FileCheckResult(true, "InGeneratedSource", null);
                }
            }

            // Apply included regex filters
            if (hasIncludedRegex) {
                String[] patterns = regexFilters.split("\n");
                for (String patternStr : patterns) {
                    patternStr = patternStr.trim();
                    if (patternStr.isEmpty()) {
                        continue;
                    }

                    try {
                        Pattern pattern = Pattern.compile(patternStr);
                        if (pattern.matcher(pathStr).find()) {
                            return new FileCheckResult(true, "Regex: " + patternStr, null);
                        }
                    } catch (PatternSyntaxException e) {
                        LOG.warn("Invalid included regex pattern: " + patternStr, e);
                    }
                }
            }

            // No filters matched - reject the file
            return new FileCheckResult(false, "None", "No filters matched");
        });
    }

    private boolean shouldProcessFile(Path path) {
        return checkFile(path).shouldProcess;
    }

    private void logToToolWindow(String event, String triggeredBy, String filePath) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                ToolWindow toolWindow = toolWindowManager.getToolWindow("File Watcher");

                if (toolWindow != null) {
                    Content content = toolWindow.getContentManager().getContent(0);
                    if (content != null) {
                        FileWatcherToolWindowContent toolWindowContent = content.getUserData(FileWatcherToolWindowFactory.TOOL_WINDOW_CONTENT_KEY);

                        if (toolWindowContent != null) {
                            toolWindowContent.addEvent(event, triggeredBy, filePath);
                        } else {
                            LOG.warn("Tool window content not found in user data - tool window may not be initialized yet");
                        }
                    } else {
                        LOG.warn("Tool window content is null");
                    }
                } else {
                    LOG.warn("Tool window 'File Watcher' not found");
                }
            } catch (Exception e) {
                LOG.warn("Error logging to tool window", e);
            }
        });
    }

    private void logIgnoredToToolWindow(String reason, String triggeredBy, String filePath) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                ToolWindow toolWindow = toolWindowManager.getToolWindow("File Watcher");

                if (toolWindow != null) {
                    Content content = toolWindow.getContentManager().getContent(0);
                    if (content != null) {
                        FileWatcherToolWindowContent toolWindowContent = content.getUserData(FileWatcherToolWindowFactory.TOOL_WINDOW_CONTENT_KEY);

                        if (toolWindowContent != null) {
                            toolWindowContent.addIgnoredEvent(reason, triggeredBy, filePath);
                        } else {
                            LOG.warn("Tool window content not found in user data - tool window may not be initialized yet");
                        }
                    } else {
                        LOG.warn("Tool window content is null");
                    }
                } else {
                    LOG.warn("Tool window 'File Watcher' not found");
                }
            } catch (Exception e) {
                LOG.warn("Error logging ignored event to tool window", e);
            }
        });
    }

    private void scheduleReload() {
        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);

        if (!settings.isAutoReloadEnabled()) {
            LOG.info("Auto-reload is disabled in settings, skipping reload");
            return;
        }

        synchronized (this) {
            if (reloadScheduled) {
                return; // Already scheduled
            }
            reloadScheduled = true;
        }

        int debounceDelay = settings.getDebounceDelayMs();
        // Debounce: wait configured milliseconds before triggering reload in case more changes come
        debounceExecutor.schedule(() -> {
            triggerReloadFromDisk();
            synchronized (this) {
                reloadScheduled = false;
            }
        }, debounceDelay, TimeUnit.MILLISECONDS);
    }

    private void triggerReloadFromDisk() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ActionManager actionManager = ActionManager.getInstance();

                // First, trigger synchronize to reload files from disk
                AnAction syncAction = actionManager.getAction("Synchronize");
                if (syncAction != null) {
                    LOG.warn("==> SYNCHRONIZE ACTION TRIGGERED - Reloading files from disk for project: " + project.getName());

                    DataContext dataContext = dataId -> {
                        if ("project".equals(dataId)) {
                            return project;
                        }
                        return null;
                    };

                    AnActionEvent syncEvent = AnActionEvent.createFromDataContext(
                            "ForceReloadFromDisk",
                            null,
                            dataContext
                    );

                    syncAction.actionPerformed(syncEvent);
                    LOG.warn("==> SYNCHRONIZE ACTION COMPLETED for project: " + project.getName());

                    // Then trigger a project rebuild after sync completes if enabled in settings
                    FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
                    if (settings.isAutoRebuildEnabled()) {
                        AnAction rebuildAction = actionManager.getAction("CompileDirty");
                        if (rebuildAction != null) {
                            DataContext rebuildContext = dataId -> {
                                if ("project".equals(dataId)) {
                                    return project;
                                }
                                return null;
                            };

                            AnActionEvent rebuildEvent = AnActionEvent.createFromDataContext(
                                    "ForceReloadRebuild",
                                    null,
                                    rebuildContext
                            );

                            // Schedule rebuild slightly after sync completes
                            debounceExecutor.schedule(() -> {
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    LOG.warn("==> REBUILD ACTION TRIGGERED - Starting project rebuild for: " + project.getName());
                                    rebuildAction.actionPerformed(rebuildEvent);
                                    LOG.warn("==> REBUILD ACTION COMPLETED for project: " + project.getName());
                                });
                            }, 500, TimeUnit.MILLISECONDS);
                        } else {
                            LOG.error("Could not find CompileDirty action for rebuild");
                        }
                    } else {
                        LOG.info("Auto-rebuild is disabled in settings, skipping rebuild");
                    }
                } else {
                    LOG.error("Could not find Synchronize action");
                }
            } catch (Exception e) {
                LOG.error("Error triggering reload from disk", e);
            }
        });
    }

    @Override
    public void dispose() {
        running = false;

        if (watchThread != null) {
            watchThread.interrupt();
            try {
                watchThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        debounceExecutor.shutdown();
        try {
            if (!debounceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                debounceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            debounceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                LOG.error("Error closing watch service", e);
            }
        }

        LOG.info("File watcher service disposed for project: " + project.getName());
    }
}
