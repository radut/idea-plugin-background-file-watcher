package com.radut.plugin.bfw.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.radut.plugin.bfw.FileWatcherService;
import com.radut.plugin.bfw.Icons;
import org.jetbrains.annotations.NotNull;

public class ToggleFileWatcherAction extends AnAction implements DumbAware {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // update() only reads the service state, so it must not occupy the EDT.
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        FileWatcherService service = project.getService(FileWatcherService.class);
        if (service == null) {
            return;
        }
        // Toggle the file watcher
        // The state change notification will automatically update the tool window and icon
        service.toggleWatchingState();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        FileWatcherService service = project == null ? null : project.getService(FileWatcherService.class);

        if (service == null) {
            e.getPresentation().setEnabled(false);
            return;
        }

        e.getPresentation().setEnabled(true);

        // Update the text and icon based on the current state
        updatePresentationState(e.getPresentation(), service);
    }

    private void updatePresentationState(Presentation presentation, FileWatcherService service) {
        if (service.isRunning()) {
            presentation.setText("Disable File Watching");
            presentation.setDescription("Stop watching files for changes");
            // Blue eye icon when running
            presentation.setIcon(Icons.EYE_BLUE);
        } else {
            presentation.setText("Enable File Watching");
            presentation.setDescription("Start watching files for changes");
            // Gray eye icon when stopped
            presentation.setIcon(Icons.EYE_GRAY);
        }
    }
}
