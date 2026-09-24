package com.radut.plugin.bfw.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import com.radut.plugin.bfw.FileWatcherService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FileWatcherConfigurable implements Configurable {

    private final Project project;
    private FileWatcherSettingsComponent settingsComponent;
    private final FileWatcherService.StateChangeListener stateChangeListener = this::updateButtonStates;

    public FileWatcherConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @NlsContexts.ConfigurableName
    @Override
    public String getDisplayName() {
        return "Background File Watcher";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        settingsComponent = new FileWatcherSettingsComponent();
        FileWatcherService service = service();
        settingsComponent.getStartButton().addActionListener(e -> service.startWatching());
        settingsComponent.getStopButton().addActionListener(e -> service.stopWatching());
        service.addStateChangeListener(stateChangeListener);
        updateButtonStates();
        return settingsComponent.getPanel();
    }

    private void updateButtonStates() {
        if (settingsComponent != null) {
            settingsComponent.updateControlStatus(service().isRunning());
        }
    }

    private FileWatcherService service() {
        return project.getService(FileWatcherService.class);
    }

    private static void validateRegexPatterns(String filterString, String filterType) throws ConfigurationException {
        if (filterString == null) {
            return;
        }
        String[] lines = filterString.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String pattern = lines[i].trim();
            if (pattern.isEmpty()) {
                continue;
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new ConfigurationException(
                        "Invalid " + filterType + " regex pattern on line " + (i + 1) + ": " + pattern + "\nError: " + e.getMessage());
            }
        }
    }

    @Override
    public boolean isModified() {
        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
        return settingsComponent.isInContent() != settings.isInContent()
               || settingsComponent.isInSource() != settings.isInSource()
               || settingsComponent.isInTestSource() != settings.isInTestSource()
               || settingsComponent.isAutoReloadEnabled() != settings.isAutoReloadEnabled()
               || settingsComponent.isAutoRebuildEnabled() != settings.isAutoRebuildEnabled()
               || settingsComponent.getDebounceDelayMs() != settings.getDebounceDelayMs()
               || !settingsComponent.getPathRegexFilters().equals(settings.getPathRegexFilters())
               || !settingsComponent.getIgnoredRegexFilters().equals(settings.getIgnoredRegexFilters());
    }

    @Override
    public void apply() throws ConfigurationException {
        validateRegexPatterns(settingsComponent.getPathRegexFilters(), "included");
        validateRegexPatterns(settingsComponent.getIgnoredRegexFilters(), "ignored");

        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
        settings.setAutoReloadEnabled(settingsComponent.isAutoReloadEnabled());
        settings.setAutoRebuildEnabled(settingsComponent.isAutoRebuildEnabled());
        settings.setDebounceDelayMs(settingsComponent.getDebounceDelayMs());
        settings.setInSource(settingsComponent.isInSource());
        settings.setInTestSource(settingsComponent.isInTestSource());
        settings.setInContent(settingsComponent.isInContent());
        settings.setPathRegexFilters(settingsComponent.getPathRegexFilters());
        settings.setIgnoredRegexFilters(settingsComponent.getIgnoredRegexFilters());

        service().onSettingsChanged();
    }

    @Override
    public void reset() {
        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
        settingsComponent.setAutoReloadEnabled(settings.isAutoReloadEnabled());
        settingsComponent.setAutoRebuildEnabled(settings.isAutoRebuildEnabled());
        settingsComponent.setDebounceDelayMs(settings.getDebounceDelayMs());
        settingsComponent.setIsInSource(settings.isInSource());
        settingsComponent.setIsInTestSource(settings.isInTestSource());
        settingsComponent.setIsInContent(settings.isInContent());
        settingsComponent.setPathRegexFilters(settings.getPathRegexFilters());
        settingsComponent.setIgnoredRegexFilters(settings.getIgnoredRegexFilters());
        updateButtonStates();
    }

    @Override
    public void disposeUIResources() {
        service().removeStateChangeListener(stateChangeListener);
        settingsComponent = null;
    }
}
