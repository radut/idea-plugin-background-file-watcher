package com.intellij.plugin.forcereload.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FileWatcherConfigurable implements Configurable {

    private final Project project;
    private FileWatcherSettingsComponent settingsComponent;

    public FileWatcherConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @NlsContexts.ConfigurableName
    @Override
    public String getDisplayName() {
        return "File Watcher Auto Reload";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        settingsComponent = new FileWatcherSettingsComponent();
        return settingsComponent.getPanel();
    }

    @Override
    public boolean isModified() {
        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
        FileWatcherSettings.State state = settings.getState();

        return settingsComponent.isCheckIsInContent() != state.checkIsInContent ||
               settingsComponent.isCheckIsExcluded() != state.checkIsExcluded ||
               settingsComponent.isCheckIsInSource() != state.checkIsInSource ||
               settingsComponent.isCheckIsInTestSource() != state.checkIsInTestSource ||
               settingsComponent.isCheckIsInLibrary() != state.checkIsInLibrary ||
               settingsComponent.isAutoReloadEnabled() != state.autoReloadEnabled ||
               settingsComponent.isAutoRebuildEnabled() != state.autoRebuildEnabled ||
               settingsComponent.getDebounceDelayMs() != state.debounceDelayMs ||
               !settingsComponent.getPathRegexFilters().equals(state.pathRegexFilters) ||
               !settingsComponent.getIgnoredRegexFilters().equals(state.ignoredRegexFilters);
    }

    @Override
    public void apply() throws ConfigurationException {
        // Validate included regex patterns
        String regexFilters = settingsComponent.getPathRegexFilters();
        if (regexFilters != null && !regexFilters.trim().isEmpty()) {
            String[] patterns = regexFilters.split("\n");
            for (int i = 0; i < patterns.length; i++) {
                String pattern = patterns[i].trim();
                if (!pattern.isEmpty()) {
                    try {
                        Pattern.compile(pattern);
                    } catch (PatternSyntaxException e) {
                        throw new ConfigurationException(
                                "Invalid included regex pattern on line " + (i + 1) + ": " + pattern + "\nError: " + e.getMessage()
                        );
                    }
                }
            }
        }

        // Validate ignored regex patterns
        String ignoredRegexFilters = settingsComponent.getIgnoredRegexFilters();
        if (ignoredRegexFilters != null && !ignoredRegexFilters.trim().isEmpty()) {
            String[] patterns = ignoredRegexFilters.split("\n");
            for (int i = 0; i < patterns.length; i++) {
                String pattern = patterns[i].trim();
                if (!pattern.isEmpty()) {
                    try {
                        Pattern.compile(pattern);
                    } catch (PatternSyntaxException e) {
                        throw new ConfigurationException(
                                "Invalid ignored regex pattern on line " + (i + 1) + ": " + pattern + "\nError: " + e.getMessage()
                        );
                    }
                }
            }
        }

        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
        settings.setCheckIsInContent(settingsComponent.isCheckIsInContent());
        settings.setCheckIsExcluded(settingsComponent.isCheckIsExcluded());
        settings.setCheckIsInSource(settingsComponent.isCheckIsInSource());
        settings.setCheckIsInTestSource(settingsComponent.isCheckIsInTestSource());
        settings.setCheckIsInLibrary(settingsComponent.isCheckIsInLibrary());
        settings.setAutoReloadEnabled(settingsComponent.isAutoReloadEnabled());
        settings.setAutoRebuildEnabled(settingsComponent.isAutoRebuildEnabled());
        settings.setDebounceDelayMs(settingsComponent.getDebounceDelayMs());
        settings.setPathRegexFilters(settingsComponent.getPathRegexFilters());
        settings.setIgnoredRegexFilters(settingsComponent.getIgnoredRegexFilters());
    }

    @Override
    public void reset() {
        FileWatcherSettings settings = FileWatcherSettings.getInstance(project);
        FileWatcherSettings.State state = settings.getState();

        settingsComponent.setCheckIsInContent(state.checkIsInContent);
        settingsComponent.setCheckIsExcluded(state.checkIsExcluded);
        settingsComponent.setCheckIsInSource(state.checkIsInSource);
        settingsComponent.setCheckIsInTestSource(state.checkIsInTestSource);
        settingsComponent.setCheckIsInLibrary(state.checkIsInLibrary);
        settingsComponent.setAutoReloadEnabled(state.autoReloadEnabled);
        settingsComponent.setAutoRebuildEnabled(state.autoRebuildEnabled);
        settingsComponent.setDebounceDelayMs(state.debounceDelayMs);
        settingsComponent.setPathRegexFilters(state.pathRegexFilters);
        settingsComponent.setIgnoredRegexFilters(state.ignoredRegexFilters);
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }
}
