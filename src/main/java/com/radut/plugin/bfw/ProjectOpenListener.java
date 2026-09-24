package com.radut.plugin.bfw;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.radut.plugin.bfw.settings.FileWatcherSettings;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ProjectOpenListener implements ProjectActivity {
    private static final Logger LOG = Logger.getInstance(ProjectOpenListener.class);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (FileWatcherSettings.getInstance(project).isEnabled()) {
            LOG.info("File watching is enabled for project " + project.getName() + ", starting it");
            project.getService(FileWatcherService.class).startWatching();
        } else {
            LOG.info("File watching is disabled for project " + project.getName());
        }
        return Unit.INSTANCE;
    }
}
