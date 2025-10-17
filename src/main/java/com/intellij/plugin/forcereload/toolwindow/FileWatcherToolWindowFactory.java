package com.intellij.plugin.forcereload.toolwindow;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class FileWatcherToolWindowFactory implements ToolWindowFactory, DumbAware {

    public static final Key<FileWatcherToolWindowContent> TOOL_WINDOW_CONTENT_KEY =
            Key.create("FileWatcherToolWindowContent");

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        FileWatcherToolWindowContent toolWindowContent = new FileWatcherToolWindowContent(project);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(toolWindowContent.getContentPanel(), "", false);

        // Store the content object as user data so we can access it later
        content.putUserData(TOOL_WINDOW_CONTENT_KEY, toolWindowContent);

        toolWindow.getContentManager().addContent(content);
    }
}
