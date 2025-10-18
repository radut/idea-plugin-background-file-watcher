package com.radut.plugin.bfw;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.radut.plugin.bfw.settings.FileWatcherSettings;
import com.radut.plugin.bfw.toolwindow.FileWatcherToolWindowContent;
import com.radut.plugin.bfw.toolwindow.FileWatcherToolWindowFactory;
import com.intellij.ui.content.Content;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaSourceRootType;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FileWatcherService implements Disposable {
    private static final Logger LOG = Logger.getInstance(FileWatcherService.class);
    private static final String SYNC_ACTION_ID = "Synchronize";
    private static final String BUILD_ACTION_ID = "CompileDirty";

    private static final String TOOL_WINDOW_ID = "File Watcher";

    private static final int REBUILD_DELAY_MS = 500;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final int WATCH_POLL_TIMEOUT_MS = 200;

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
                key = watchService.poll(WATCH_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
                String changeType = kind.name().replace("ENTRY_", "");
                if (checkResult.shouldProcess) {
                    // Format event type: CREATE/MODIFY/DELETE
                    String relativePath = getRelativePath(fullPath);
                    LOG.info("Detected " + changeType + " in: " + relativePath + " [" + checkResult.matchedRule + "]");
                    logToToolWindow(changeType, checkResult.matchedRule, checkResult.details, relativePath);
                    hasRelevantChanges = true;
                } else {
                    // Log ignored event only if there's a valid ignore reason
                    if (checkResult.details != null && !checkResult.details.isEmpty()) {
                        String relativePath = getRelativePath(fullPath);
                        logIgnoredToToolWindow(changeType, checkResult.matchedRule != null ? checkResult.matchedRule : "N/A",
                                checkResult.details,
                                relativePath);
                    }
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
        String details;

        FileCheckResult(boolean shouldProcess, String matchedRule, String details) {
            this.shouldProcess = shouldProcess;
            this.matchedRule = matchedRule;
            this.details = details;
        }
    }

    private List<VirtualFile> getSourceRoots() {
        List<VirtualFile> roots = new ArrayList<>();
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        for (Module module : moduleManager.getModules()) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            for (var contentEntry : rootManager.getContentEntries()) {
                for (SourceFolder sourceFolder : contentEntry.getSourceFolders()) {
                    if (sourceFolder.getRootType() == JavaSourceRootType.SOURCE) {
                        VirtualFile file = sourceFolder.getFile();
                        if (file != null) {
                            roots.add(file);
                        }
                    }
                }
            }
        }
        return roots;
    }

    private List<VirtualFile> getTestSourceRoots() {
        List<VirtualFile> roots = new ArrayList<>();
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        for (Module module : moduleManager.getModules()) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            for (var contentEntry : rootManager.getContentEntries()) {
                for (SourceFolder sourceFolder : contentEntry.getSourceFolders()) {
                    if (sourceFolder.getRootType() == JavaSourceRootType.TEST_SOURCE) {
                        VirtualFile file = sourceFolder.getFile();
                        if (file != null) {
                            roots.add(file);
                        }
                    }
                }
            }
        }
        return roots;
    }

    private List<VirtualFile> getGeneratedSourceRoots() {
        List<VirtualFile> roots = new ArrayList<>();
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        for (Module module : moduleManager.getModules()) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            for (VirtualFile root : rootManager.getSourceRoots(false)) {
                if (root.getPath().contains("generated")) {
                    roots.add(root);
                }
            }
        }
        return roots;
    }

    private List<VirtualFile> getContentRoots() {
        List<VirtualFile> roots = new ArrayList<>();
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        for (Module module : moduleManager.getModules()) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            for (VirtualFile contentRoot : rootManager.getContentRoots()) {
                roots.add(contentRoot);
            }
        }
        return roots;
    }

    private boolean isPathUnderRoot(Path path, VirtualFile root) {
        String pathStr = path.toString();
        String rootPath = root.getPath();
        return pathStr.startsWith(rootPath);
    }

    private FileCheckResult checkFile(Path path) {
        // This method needs read action to query VFS
        return ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<FileCheckResult>) () -> {
            FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
            String pathStr = path.toString();
            if (pathStr.startsWith(project.getBasePath())) {
                pathStr = pathStr.substring(project.getBasePath().length() + 1);
            }

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
                            return new FileCheckResult(false, "Ignore Regex", patternStr);
                        }
                    } catch (PatternSyntaxException e) {
                        LOG.warn("Invalid ignored regex pattern: " + patternStr, e);
                    }
                }
            }

            // Check if included regex is configured
            String regexFilters = settings.getPathRegexFilters();
            boolean hasIncludedRegex = regexFilters != null && !regexFilters.trim().isEmpty();


            if (settings.isInGeneratedSource()) {
                for (VirtualFile root : getGeneratedSourceRoots()) {
                    if (isPathUnderRoot(path, root)) {
                        return new FileCheckResult(true, "InGeneratedSource", null);
                    }
                }
            }

            if (settings.isInSource()) {
                for (VirtualFile root : getSourceRoots()) {
                    if (isPathUnderRoot(path, root)) {
                        return new FileCheckResult(true, "InSource", null);
                    }
                }
            }

            if (settings.isInTestSource()) {
                for (VirtualFile root : getTestSourceRoots()) {
                    if (isPathUnderRoot(path, root)) {
                        return new FileCheckResult(true, "InTestSource", null);
                    }
                }
            }


            if (settings.isInContent()) {
                for (VirtualFile root : getContentRoots()) {
                    if (isPathUnderRoot(path, root)) {
                        return new FileCheckResult(true, "InProjectContent", null);
                    }
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

    private String getRelativePath(Path fullPath) {
        String basePath = project.getBasePath();
        if (basePath != null) {
            Path projectPath = Paths.get(basePath);
            try {
                Path relativePath = projectPath.relativize(fullPath);
                return relativePath.toString();
            } catch (IllegalArgumentException e) {
                // Paths are not on the same file system, return full path
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
                    content.addEvent(true, event, matchedRule + (StringUtils.isNotBlank(details) ? ": " + details : ""), filePath);
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
                    content.addEvent(false, event, matchedRule + (StringUtils.isNotBlank(details) ? ": " + details : ""), filePath);
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
                AnAction syncAction = actionManager.getAction(SYNC_ACTION_ID);
                if (syncAction != null) {
                    LOG.warn("==> SYNCHRONIZE ACTION TRIGGERED - Reloading files from disk for project: " + project.getName());

                    actionManager.tryToExecute(syncAction, null, null, "Background Action", true);
                    LOG.warn("==> SYNCHRONIZE ACTION COMPLETED for project: " + project.getName());

                    // Then trigger a project rebuild after sync completes if enabled in settings
                    FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
                    if (settings.isAutoRebuildEnabled()) {
                        AnAction rebuildAction = actionManager.getAction(BUILD_ACTION_ID);
                        if (rebuildAction != null) {
                            // Schedule rebuild slightly after sync completes
                            debounceExecutor.schedule(() -> {
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    LOG.warn("==> REBUILD ACTION TRIGGERED - Starting project rebuild for: " + project.getName());
                                    ActionManager.getInstance().tryToExecute(rebuildAction, null, null, "Background Action", true);
                                    LOG.warn("==> REBUILD ACTION COMPLETED for project: " + project.getName());
                                });
                            }, REBUILD_DELAY_MS, TimeUnit.MILLISECONDS);
                        } else {
                            LOG.error("Could not find " + BUILD_ACTION_ID + " action for rebuild");
                        }
                    } else {
                        LOG.info("Auto-rebuild is disabled in settings, skipping rebuild");
                    }
                } else {
                    LOG.error("Could not find " + SYNC_ACTION_ID + " action");
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
            if (!debounceExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
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
