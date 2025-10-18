package com.radut.plugin.bfw.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
    name = "FileWatcherSettings",
    storages = @Storage(StoragePathMacros.WORKSPACE_FILE)
)
public class FileWatcherSettings implements PersistentStateComponent<FileWatcherSettings.State> {

    private State state = new State();

    public static FileWatcherSettings getInstance(@NotNull Project project) {
        return project.getService(FileWatcherSettings.class);
    }

    @Nullable
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public static class State {
        public boolean isInSource = true;
        public boolean isInTestSource = true;
        public boolean isInGeneratedSource = true;
        public boolean isInContent = true;
        public boolean autoReloadEnabled = true;
        public boolean autoRebuildEnabled = true;
        public int debounceDelayMs = 500;
        public String pathRegexFilters = "";
        public String ignoredRegexFilters = "";
    }

    // Convenience methods
    public boolean isInSource() {
        return state.isInSource;
    }

    public void setIsInSource(boolean value) {
        state.isInSource = value;
    }

    public boolean isInTestSource() {
        return state.isInTestSource;
    }

    public void setIsInTestSource(boolean value) {
        state.isInTestSource = value;
    }

    public boolean isInGeneratedSource() {
        return state.isInGeneratedSource;
    }

    public void setIsInGeneratedSource(boolean value) {
        state.isInGeneratedSource = value;
    }

    public boolean isInContent() {
        return state.isInContent;
    }

    public void setIsInContent(boolean value) {
        state.isInContent = value;
    }

    public boolean isAutoReloadEnabled() {
        return state.autoReloadEnabled;
    }

    public void setAutoReloadEnabled(boolean value) {
        state.autoReloadEnabled = value;
    }

    public boolean isAutoRebuildEnabled() {
        return state.autoRebuildEnabled;
    }

    public void setAutoRebuildEnabled(boolean value) {
        state.autoRebuildEnabled = value;
    }

    public int getDebounceDelayMs() {
        return state.debounceDelayMs;
    }

    public void setDebounceDelayMs(int value) {
        state.debounceDelayMs = value;
    }

    public String getPathRegexFilters() {
        return state.pathRegexFilters;
    }

    public void setPathRegexFilters(String value) {
        state.pathRegexFilters = value;
    }

    public String getIgnoredRegexFilters() {
        return state.ignoredRegexFilters;
    }

    public void setIgnoredRegexFilters(String value) {
        state.ignoredRegexFilters = value;
    }
}
