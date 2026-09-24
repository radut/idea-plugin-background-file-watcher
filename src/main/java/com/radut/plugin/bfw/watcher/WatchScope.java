package com.radut.plugin.bfw.watcher;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * The directories a watcher subscribes to: everything below a root, minus everything below an
 * exclusion. When roots and exclusions nest, the deepest one wins, so a generated source root
 * inside an excluded build directory is still watched.
 */
public final class WatchScope {
    public static final WatchScope EMPTY = new WatchScope(Set.of(), Set.of());

    private final Set<Path> roots;
    private final Set<Path> exclusions;

    public WatchScope(Set<Path> roots, Set<Path> exclusions) {
        this.roots = Set.copyOf(roots);
        this.exclusions = Set.copyOf(exclusions);
    }

    public Set<Path> roots() {
        return roots;
    }

    public Set<Path> exclusions() {
        return exclusions;
    }

    public boolean covers(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (roots.contains(current)) {
                return true;
            }
            if (exclusions.contains(current)) {
                return false;
            }
        }
        return false;
    }

    public boolean hasRootBelow(Path directory) {
        for (Path root : roots) {
            if (root.startsWith(directory) && !root.equals(directory)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WatchScope that && roots.equals(that.roots) && exclusions.equals(that.exclusions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roots, exclusions);
    }

    @Override
    public String toString() {
        return roots.size() + " roots, " + exclusions.size() + " exclusions";
    }
}
