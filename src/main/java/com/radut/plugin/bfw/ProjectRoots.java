package com.radut.plugin.bfw;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.radut.plugin.bfw.settings.FileWatcherSettings;
import com.radut.plugin.bfw.watcher.WatchScope;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/** A snapshot of the module roots of a project, taken once per structure change instead of once per file event. */
final class ProjectRoots {

    enum Location { SOURCE, TEST_SOURCE, CONTENT, EXCLUDED, OUTSIDE }

    static final ProjectRoots EMPTY = new ProjectRoots(Set.of(), Set.of(), Set.of(), Set.of());

    private final Set<Path> sourceRoots;
    private final Set<Path> testSourceRoots;
    private final Set<Path> contentRoots;
    private final Set<Path> excludedFolders;

    private ProjectRoots(Set<Path> sourceRoots, Set<Path> testSourceRoots, Set<Path> contentRoots, Set<Path> excludedFolders) {
        this.sourceRoots = Set.copyOf(sourceRoots);
        this.testSourceRoots = Set.copyOf(testSourceRoots);
        this.contentRoots = Set.copyOf(contentRoots);
        this.excludedFolders = Set.copyOf(excludedFolders);
    }

    static ProjectRoots collect(Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<ProjectRoots>) () -> {
            Set<Path> source = new HashSet<>();
            Set<Path> test = new HashSet<>();
            Set<Path> content = new HashSet<>();
            Set<Path> excluded = new HashSet<>();
            for (Module module : ModuleManager.getInstance(project).getModules()) {
                for (ContentEntry entry : ModuleRootManager.getInstance(module).getContentEntries()) {
                    addLocalPath(content, entry.getFile());
                    for (SourceFolder folder : entry.getSourceFolders()) {
                        addLocalPath(folder.isTestSource() ? test : source, folder.getFile());
                    }
                    for (VirtualFile folder : entry.getExcludeFolderFiles()) {
                        addLocalPath(excluded, folder);
                    }
                }
            }
            return new ProjectRoots(source, test, content, excluded);
        });
    }

    private static void addLocalPath(Set<Path> target, VirtualFile file) {
        if (file != null && file.isInLocalFileSystem()) {
            target.add(Paths.get(file.getPath()));
        }
    }

    /** Classifies by the nearest enclosing root, so a source root inside an excluded folder still counts as source. */
    Location classify(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (sourceRoots.contains(current)) {
                return Location.SOURCE;
            }
            if (testSourceRoots.contains(current)) {
                return Location.TEST_SOURCE;
            }
            if (excludedFolders.contains(current)) {
                return Location.EXCLUDED;
            }
            if (contentRoots.contains(current)) {
                return Location.CONTENT;
            }
        }
        return Location.OUTSIDE;
    }

    WatchScope toWatchScope(FileWatcherSettings settings) {
        Set<Path> roots = new HashSet<>();
        Set<Path> exclusions = new HashSet<>(excludedFolders);
        (settings.isInSource() ? roots : exclusions).addAll(sourceRoots);
        (settings.isInTestSource() ? roots : exclusions).addAll(testSourceRoots);
        if (settings.isInContent()) {
            roots.addAll(contentRoots);
        }
        return new WatchScope(roots, exclusions);
    }

    @Override
    public String toString() {
        return sourceRoots.size() + " source roots, " + testSourceRoots.size() + " test source roots, "
               + contentRoots.size() + " content roots, " + excludedFolders.size() + " excluded folders";
    }
}
