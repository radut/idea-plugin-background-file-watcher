package com.intellij.plugin.bfw.settings;

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
        return "Background File Watcher";
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

        return settingsComponent.isInContent() != state.isInContent ||
               settingsComponent.isInSource() != state.isInSource ||
               settingsComponent.isInTestSource() != state.isInTestSource ||
               settingsComponent.isInGeneratedSource() != state.isInGeneratedSource ||
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
        settings.setIsInContent(settingsComponent.isInContent());
        settings.setIsInSource(settingsComponent.isInSource());
        settings.setIsInTestSource(settingsComponent.isInTestSource());
        settings.setIsInGeneratedSource(settingsComponent.isInGeneratedSource());
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

        settingsComponent.setIsInContent(state.isInContent);
        settingsComponent.setIsInSource(state.isInSource);
        settingsComponent.setIsInTestSource(state.isInTestSource);
        settingsComponent.setIsInGeneratedSource(state.isInGeneratedSource);
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
