package com.radut.plugin.bfw;

import com.intellij.ide.SaveAndSyncHandler;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.radut.plugin.bfw.settings.FileWatcherSettings;
import com.radut.plugin.bfw.watcher.RecursiveDirectoryWatcher;
import com.radut.plugin.bfw.watcher.WatchScope;
import org.jetbrains.annotations.NotNull;

import javax.swing.JFrame;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FileWatcherService implements Disposable {
    private static final Logger LOG = Logger.getInstance(FileWatcherService.class);

    private static final String BUILD_ACTION_ID = "CompileDirty";
    private static final int MAX_RECENT_EVENTS = 1_000;

    public interface StateChangeListener {
        void onStateChanged();
    }

    public interface EventListener {
        void onEvent(FileWatcherEvent event);
    }

    private final Project project;
    private final Path basePath;
    private final RecursiveDirectoryWatcher watcher;
    private final ExecutorService watcherControl;
    private final ScheduledExecutorService actionScheduler;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private volatile ProjectRoots roots = ProjectRoots.EMPTY;
    private volatile PathFilters filters = PathFilters.NONE;

    private final Object actionLock = new Object();
    private ScheduledFuture<?> pendingActions;

    private final Deque<FileWatcherEvent> recentEvents = new ArrayDeque<>();
    private final List<StateChangeListener> stateListeners = new CopyOnWriteArrayList<>();
    private final List<EventListener> eventListeners = new CopyOnWriteArrayList<>();

    public FileWatcherService(@NotNull Project project) {
        this.project = project;
        this.basePath = project.getBasePath() == null ? null : Paths.get(project.getBasePath());
        this.watcher = new RecursiveDirectoryWatcher(project.getName(), this::onFileEvent);
        this.watcherControl = AppExecutorUtil.createBoundedApplicationPoolExecutor("Background File Watcher control", 1);
        this.actionScheduler = AppExecutorUtil.createBoundedScheduledExecutorService("Background File Watcher actions", 1);
    }

    public boolean isRunning() {
        return running.get();
    }

    public void startWatching() {
        if (project.isDisposed()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            LOG.info("File watcher already running for project " + project.getName());
            return;
        }
        LOG.info("Starting file watcher for project " + project.getName());
        persistEnabled(true);
        notifyStateChanged();
        queueRefresh("start");
    }

    public void stopWatching() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        LOG.info("Stopping file watcher for project " + project.getName());
        cancelPendingActions();
        persistEnabled(false);
        notifyStateChanged();
        submit(this::stopWatcherUnlessRestarted);
    }

    public void toggleWatchingState() {
        if (running.get()) {
            stopWatching();
        } else {
            startWatching();
        }
    }

    /** Re-reads the project roots and settings and adjusts the subscriptions without restarting the watcher. */
    public void refreshWatchedRoots() {
        queueRefresh("project roots changed");
    }

    public void onSettingsChanged() {
        queueRefresh("settings changed");
    }

    public void addStateChangeListener(StateChangeListener listener) {
        stateListeners.add(listener);
    }

    public void removeStateChangeListener(StateChangeListener listener) {
        stateListeners.remove(listener);
    }

    public void addEventListener(EventListener listener) {
        eventListeners.add(listener);
    }

    public void removeEventListener(EventListener listener) {
        eventListeners.remove(listener);
    }

    public List<FileWatcherEvent> recentEvents() {
        synchronized (recentEvents) {
            return new ArrayList<>(recentEvents);
        }
    }

    public void clearRecentEvents() {
        synchronized (recentEvents) {
            recentEvents.clear();
        }
    }

    private void queueRefresh(String reason) {
        if (!running.get() || !refreshQueued.compareAndSet(false, true)) {
            return;
        }
        submit(() -> refresh(reason));
    }

    private void refresh(String reason) {
        refreshQueued.set(false);
        if (!running.get() || project.isDisposed()) {
            return;
        }
        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
        ProjectRoots snapshot = ProjectRoots.collect(project);
        roots = snapshot;
        filters = new PathFilters(settings.getPathRegexFilters(), settings.getIgnoredRegexFilters());
        WatchScope scope = snapshot.toWatchScope(settings);
        LOG.info("Refreshing watched roots (" + reason + ") for project " + project.getName() + ": " + snapshot);
        try {
            watcher.start();
            watcher.updateScope(scope);
        } catch (IOException e) {
            LOG.error("Failed to start file watcher for project " + project.getName(), e);
            running.set(false);
            notifyStateChanged();
        }
    }

    /** Control tasks run in order, so a start queued after this stop wins and the watcher keeps its subscriptions. */
    private void stopWatcherUnlessRestarted() {
        if (running.get()) {
            LOG.info("Watcher restarted before the stop ran, keeping it for project " + project.getName());
            return;
        }
        watcher.stop();
    }

    private void submit(Runnable task) {
        try {
            watcherControl.execute(task);
        } catch (RejectedExecutionException e) {
            LOG.debug("File watcher control rejected a task, service is disposed");
        }
    }

    private void onFileEvent(WatchEvent.Kind<?> kind, Path path) {
        String relativePath = relativePath(path);
        FileCheckResult check = checkFile(kind, path, relativePath);
        if (check.shouldProcess()) {
            LOG.info("Change " + kind.name() + " " + relativePath + " (" + check.reason() + ")");
            scheduleActions();
        } else {
            LOG.debug("Ignored " + kind.name() + " " + relativePath + " (" + check.reason() + ")");
        }
        record(new FileWatcherEvent(LocalDateTime.now(), kind.name(), check.shouldProcess(), check.reason(), relativePath));
    }

    private FileCheckResult checkFile(WatchEvent.Kind<?> kind, Path path, String relativePath) {
        if (kind == StandardWatchEventKinds.OVERFLOW) {
            return new FileCheckResult(true, "Overflow: events were lost, assuming changes");
        }
        PathFilters filters = this.filters;
        String ignored = filters.firstIgnoreMatch(relativePath);
        if (ignored != null) {
            return new FileCheckResult(false, "Ignore Regex: " + ignored);
        }
        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
        return switch (roots.classify(path)) {
            case SOURCE -> new FileCheckResult(settings.isInSource(), "InSource");
            case TEST_SOURCE -> new FileCheckResult(settings.isInTestSource(), "InTestSource");
            case CONTENT -> checkContent(relativePath, settings, filters);
            case EXCLUDED -> new FileCheckResult(false, "Excluded");
            case OUTSIDE -> new FileCheckResult(false, "Outside project");
        };
    }

    private static FileCheckResult checkContent(String relativePath, FileWatcherSettings settings, PathFilters filters) {
        if (!settings.isInContent()) {
            return new FileCheckResult(false, "InProjectContent");
        }
        if (!filters.hasIncludePatterns()) {
            return new FileCheckResult(true, "InProjectContent");
        }
        String included = filters.firstIncludeMatch(relativePath);
        return included != null
               ? new FileCheckResult(true, "InProjectContent Regex: " + included)
               : new FileCheckResult(false, "InProjectContent Regex: no pattern matched");
    }

    private String relativePath(Path path) {
        return basePath != null && path.startsWith(basePath) ? basePath.relativize(path).toString() : path.toString();
    }

    private void record(FileWatcherEvent event) {
        synchronized (recentEvents) {
            recentEvents.addLast(event);
            while (recentEvents.size() > MAX_RECENT_EVENTS) {
                recentEvents.removeFirst();
            }
        }
        for (EventListener listener : eventListeners) {
            listener.onEvent(event);
        }
    }

    /** Debounces on the trailing edge: the actions run once the changes have been quiet for the configured delay. */
    private void scheduleActions() {
        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
        if (!settings.isAutoReloadEnabled() && !settings.isAutoRebuildEnabled()) {
            return;
        }
        synchronized (actionLock) {
            if (pendingActions != null) {
                pendingActions.cancel(false);
            }
            try {
                pendingActions = actionScheduler.schedule(this::runActions, settings.getDebounceDelayMs(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                LOG.debug("Action scheduler rejected a task, service is disposed");
            }
        }
    }

    private void cancelPendingActions() {
        synchronized (actionLock) {
            if (pendingActions != null) {
                pendingActions.cancel(false);
                pendingActions = null;
            }
        }
    }

    private void runActions() {
        synchronized (actionLock) {
            pendingActions = null;
        }
        if (!running.get()) {
            return;
        }
        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
        boolean reload = settings.isAutoReloadEnabled();
        boolean build = settings.isAutoRebuildEnabled();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (reload) {
                reloadFromDisk(build);
            } else if (build) {
                build();
            }
        }, ModalityState.nonModal(), project.getDisposed());
    }

    private void reloadFromDisk(boolean buildAfterwards) {
        LOG.info("Reloading files from disk for project " + project.getName());
        FileDocumentManager.getInstance().saveAllDocuments();
        VirtualFileManager.getInstance().asyncRefresh(() -> {
            if (project.isDisposed()) {
                return;
            }
            LOG.info("Reload from disk finished for project " + project.getName());
            if (buildAfterwards) {
                build();
            }
        });
    }

    /**
     * The action's data context is anchored on the project frame on purpose: this runs while the
     * IDE sits in the background, where no component has focus to derive a context from.
     */
    private void build() {
        AnAction action = ActionManager.getInstance().getAction(BUILD_ACTION_ID);
        if (action == null) {
            LOG.warn("Build skipped: action " + BUILD_ACTION_ID + " is not available in this IDE");
            return;
        }
        JFrame frame = WindowManager.getInstance().getFrame(project);
        if (frame == null) {
            LOG.warn("Build skipped: no frame for project " + project.getName());
            return;
        }
        LOG.info("Building project " + project.getName());
        ActionManager.getInstance().tryToExecute(action, null, frame, ActionPlaces.UNKNOWN, true)
                .doWhenDone(() -> LOG.info("Build action finished for project " + project.getName()))
                .doWhenRejected(() -> LOG.warn("Build action was rejected for project " + project.getName()));
    }

    private void persistEnabled(boolean enabled) {
        ApplicationManager.getApplication().invokeLater(() -> {
            FileWatcherSettings.getInstance(project).setEnabled(enabled);
            SaveAndSyncHandler.getInstance().scheduleProjectSave(project);
        }, project.getDisposed());
    }

    private void notifyStateChanged() {
        ApplicationManager.getApplication().invokeLater(
                () -> stateListeners.forEach(StateChangeListener::onStateChanged), project.getDisposed());
    }

    @Override
    public void dispose() {
        running.set(false);
        cancelPendingActions();
        watcherControl.shutdownNow();
        actionScheduler.shutdownNow();
        watcher.stop();
        LOG.info("File watcher service disposed for project " + project.getName());
    }
}
