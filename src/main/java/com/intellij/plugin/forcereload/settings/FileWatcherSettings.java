package com.intellij.plugin.forcereload.settings;

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
        public boolean checkIsInContent = true;
        public boolean checkIsExcluded = true;
        public boolean checkIsInSource = false;
        public boolean checkIsInTestSource = false;
        public boolean checkIsInLibrary = false;
        public boolean autoReloadEnabled = true;
        public boolean autoRebuildEnabled = true;
        public int debounceDelayMs = 500;
        public String pathRegexFilters = "";
        public String ignoredRegexFilters = "";
    }

    // Convenience methods
    public boolean isCheckIsInContent() {
        return state.checkIsInContent;
    }

    public void setCheckIsInContent(boolean value) {
        state.checkIsInContent = value;
    }

    public boolean isCheckIsExcluded() {
        return state.checkIsExcluded;
    }

    public void setCheckIsExcluded(boolean value) {
        state.checkIsExcluded = value;
    }

    public boolean isCheckIsInSource() {
        return state.checkIsInSource;
    }

    public void setCheckIsInSource(boolean value) {
        state.checkIsInSource = value;
    }

    public boolean isCheckIsInTestSource() {
        return state.checkIsInTestSource;
    }

    public void setCheckIsInTestSource(boolean value) {
        state.checkIsInTestSource = value;
    }

    public boolean isCheckIsInLibrary() {
        return state.checkIsInLibrary;
    }

    public void setCheckIsInLibrary(boolean value) {
        state.checkIsInLibrary = value;
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
