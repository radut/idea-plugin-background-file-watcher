package com.radut.plugin.bfw;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import org.jetbrains.annotations.NotNull;

public class ProjectRootsChangeListener implements ModuleRootListener {
    private static final Logger LOG = Logger.getInstance(ProjectRootsChangeListener.class);

    @Override
    public void rootsChanged(@NotNull ModuleRootEvent event) {
        Project project = event.getProject();
        if (project.isDisposed()) {
            return;
        }
        FileWatcherService service = project.getService(FileWatcherService.class);
        if (service.isRunning()) {
            LOG.info("Project structure changed, refreshing watched roots for project " + project.getName());
            service.refreshWatchedRoots();
        }
    }
}
