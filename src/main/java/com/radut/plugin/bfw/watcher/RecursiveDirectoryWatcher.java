package com.radut.plugin.bfw.watcher;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Watches a {@link WatchScope} with one {@link WatchService} and one thread. Directories are
 * subscribed when they enter the scope or get created and unsubscribed when they leave it or
 * disappear, so a scope change only touches the directories whose membership changed.
 */
public final class RecursiveDirectoryWatcher {
    private static final Logger LOG = Logger.getInstance(RecursiveDirectoryWatcher.class);
    private static final long STOP_TIMEOUT_MS = 2_000;

    public interface WatchListener {
        void onFileEvent(WatchEvent.Kind<?> kind, Path path);
    }

    private record Event(WatchEvent.Kind<?> kind, Path path) {
    }

    /** A subscribed directory and the entry names it contained when last seen. */
    private static final class WatchedDirectory {
        final WatchKey key;
        final Set<String> entries = new HashSet<>();

        WatchedDirectory(WatchKey key) {
            this.key = key;
        }
    }

    private final String name;
    private final WatchListener listener;
    private final Map<Path, WatchedDirectory> directories = new HashMap<>();
    private WatchScope scope = WatchScope.EMPTY;
    private WatchService watchService;
    private Thread thread;
    private volatile boolean stopping;

    public RecursiveDirectoryWatcher(String name, WatchListener listener) {
        this.name = name;
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        if (watchService != null) {
            return;
        }
        stopping = false;
        WatchService service = FileSystems.getDefault().newWatchService();
        watchService = service;
        thread = new Thread(() -> watchLoop(service), "BackgroundFileWatcher-" + name);
        thread.setDaemon(true);
        thread.start();
        subscribeScope(WatchScope.EMPTY, scope);
        LOG.info(name + ": watcher started, " + directories.size() + " directories subscribed");
    }

    public synchronized void updateScope(WatchScope newScope) {
        WatchScope previous = scope;
        scope = newScope;
        if (watchService == null || previous.equals(newScope)) {
            return;
        }
        int before = directories.size();
        int unsubscribed = unsubscribeOutOfScope();
        subscribeScope(previous, newScope);
        int subscribed = directories.size() - before + unsubscribed;
        LOG.info(name + ": scope changed to " + newScope + ", " + subscribed + " directories subscribed, "
                 + unsubscribed + " unsubscribed, " + directories.size() + " watched");
    }

    public void stop() {
        stopping = true;
        WatchService service;
        Thread watchThread;
        synchronized (this) {
            if (watchService == null) {
                return;
            }
            service = watchService;
            watchThread = thread;
            watchService = null;
            thread = null;
            directories.clear();
        }
        try {
            service.close();
        } catch (IOException e) {
            LOG.warn(name + ": failed to close watch service", e);
        }
        try {
            watchThread.join(STOP_TIMEOUT_MS);
            if (watchThread.isAlive()) {
                LOG.warn(name + ": watch thread did not stop within " + STOP_TIMEOUT_MS + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info(name + ": watcher stopped");
    }

    public synchronized int watchedDirectoryCount() {
        return directories.size();
    }

    private int unsubscribeOutOfScope() {
        List<Path> leaving = directories.keySet().stream().filter(dir -> !scope.covers(dir)).toList();
        leaving.forEach(this::unsubscribe);
        return leaving.size();
    }

    private void subscribeScope(WatchScope previous, WatchScope current) {
        for (Path root : current.roots()) {
            subscribeTree(root, null);
        }
        for (Path lifted : previous.exclusions()) {
            if (!current.exclusions().contains(lifted) && current.covers(lifted)) {
                subscribeTree(lifted, null);
            }
        }
    }

    /**
     * Subscribes every in-scope directory below {@code start}, skipping subtrees that are already
     * subscribed. When {@code discovered} is given, every file and directory found is reported
     * as created, which is how a directory moved into the tree announces its content.
     */
    private void subscribeTree(Path start, List<Event> discovered) {
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (stopping) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (directories.containsKey(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    remember(dir);
                    if (scope.covers(dir)) {
                        subscribe(dir);
                        if (discovered != null && !dir.equals(start)) {
                            discovered.add(new Event(ENTRY_CREATE, dir));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    return scope.hasRootBelow(dir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (remember(file) && discovered != null) {
                        discovered.add(new Event(ENTRY_CREATE, file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    LOG.warn(name + ": cannot access " + file + ": " + e);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (ClosedWatchServiceException e) {
            LOG.info(name + ": watch service closed while subscribing " + start);
        } catch (IOException e) {
            LOG.warn(name + ": failed to subscribe " + start, e);
        }
    }

    private void subscribe(Path dir) throws IOException {
        WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        directories.put(dir, new WatchedDirectory(key));
        LOG.debug("Subscribed " + dir);
    }

    private void unsubscribe(Path dir) {
        WatchedDirectory watched = directories.remove(dir);
        if (watched != null) {
            watched.key.cancel();
            LOG.debug("Unsubscribed " + dir);
        }
    }

    /** Unsubscribes {@code dir} and everything below it, reporting the entries that vanished with it. */
    private void unsubscribeTree(Path dir, List<Event> events) {
        WatchedDirectory watched = directories.remove(dir);
        if (watched == null) {
            return;
        }
        watched.key.cancel();
        LOG.debug("Unsubscribed " + dir);
        for (String entry : watched.entries) {
            Path child = dir.resolve(entry);
            unsubscribeTree(child, events);
            if (scope.covers(child)) {
                events.add(new Event(ENTRY_DELETE, child));
            }
        }
    }

    /** Records {@code path} as an entry of its parent when the parent is subscribed. */
    private boolean remember(Path path) {
        WatchedDirectory parent = directories.get(path.getParent());
        return parent != null && parent.entries.add(entryName(path));
    }

    private void watchLoop(WatchService service) {
        LOG.info(name + ": watch loop started");
        try {
            while (true) {
                WatchKey key = service.take();
                List<Event> events = new ArrayList<>();
                synchronized (this) {
                    if (watchService != service) {
                        break;
                    }
                    handle(key, events);
                }
                events.forEach(this::deliver);
            }
        } catch (ClosedWatchServiceException | InterruptedException e) {
            LOG.debug(name + ": watch service closed");
        } catch (Exception e) {
            LOG.error(name + ": watch loop failed, files are no longer watched", e);
        }
        LOG.info(name + ": watch loop finished");
    }

    private void handle(WatchKey key, List<Event> events) {
        Path dir = watchedDirectory(key);
        WatchedDirectory watched = dir == null ? null : directories.get(dir);
        if (watched == null) {
            key.cancel();
            return;
        }
        try {
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == OVERFLOW) {
                    resync(dir, watched, events);
                    continue;
                }
                Path path = dir.resolve(event.context().toString());
                if (kind == ENTRY_CREATE) {
                    created(path, watched, events);
                } else if (kind == ENTRY_DELETE) {
                    deleted(path, watched, events);
                } else {
                    modified(path, watched, events);
                }
            }
        } catch (Exception e) {
            LOG.warn(name + ": failed to process events for " + dir, e);
        }
        if (!key.reset()) {
            LOG.debug("Directory gone: " + dir);
            unsubscribeTree(dir, events);
        }
    }

    private void created(Path path, WatchedDirectory parent, List<Event> events) {
        parent.entries.add(entryName(path));
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        if (scope.covers(path)) {
            events.add(new Event(ENTRY_CREATE, path));
            if (directory) {
                subscribeTree(path, events);
            }
        } else if (directory && scope.hasRootBelow(path)) {
            subscribeTree(path, events);
        }
    }

    private void deleted(Path path, WatchedDirectory parent, List<Event> events) {
        parent.entries.remove(entryName(path));
        unsubscribeTree(path, events);
        if (scope.covers(path)) {
            events.add(new Event(ENTRY_DELETE, path));
        }
    }

    private void modified(Path path, WatchedDirectory parent, List<Event> events) {
        if (directories.containsKey(path)) {
            return;
        }
        parent.entries.add(entryName(path));
        if (scope.covers(path)) {
            events.add(new Event(ENTRY_MODIFY, path));
        }
    }

    /** The OS dropped events for {@code dir}: reconcile its entries with what is on disk. */
    private void resync(Path dir, WatchedDirectory watched, List<Event> events) {
        LOG.warn(name + ": event overflow in " + dir + ", rescanning it");
        events.add(new Event(OVERFLOW, dir));
        Set<String> present = new HashSet<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.forEach(child -> present.add(entryName(child)));
        } catch (IOException e) {
            LOG.warn(name + ": cannot list " + dir + " after overflow", e);
            return;
        }
        for (String entry : new ArrayList<>(watched.entries)) {
            if (!present.contains(entry)) {
                deleted(dir.resolve(entry), watched, events);
            }
        }
        for (String entry : present) {
            if (!watched.entries.contains(entry)) {
                created(dir.resolve(entry), watched, events);
            }
        }
    }

    private void deliver(Event event) {
        try {
            listener.onFileEvent(event.kind(), event.path());
        } catch (Exception e) {
            LOG.error(name + ": listener failed for " + event.path(), e);
        }
    }

    /**
     * The IDE installs its own default file system provider, so paths handed back by the OS
     * watch service may belong to a different provider than the paths we registered. Comparing
     * or resolving across providers fails, hence the re-anchoring by string.
     */
    private static Path watchedDirectory(WatchKey key) {
        if (!(key.watchable() instanceof Path path)) {
            return null;
        }
        FileSystem local = FileSystems.getDefault();
        return path.getFileSystem() == local ? path : local.getPath(path.toString());
    }

    private static String entryName(Path path) {
        return path.getFileName().toString();
    }
}
