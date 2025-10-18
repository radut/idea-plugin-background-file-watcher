package com.radut.plugin.bfw.settings;

import com.intellij.ide.BrowserUtil;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ui.FormBuilder;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;

public class FileWatcherSettingsComponent {

    private final JPanel mainPanel;
    private final JBCheckBox isInContent = new JBCheckBox("Include files in project content");
    private final JBCheckBox isInSource = new JBCheckBox("Include source files");
    private final JBCheckBox isInTestSource = new JBCheckBox("Include test source files");
    private final JBCheckBox isInGeneratedSource = new JBCheckBox("Include generated source files");
    private final JBCheckBox autoReloadEnabled = new JBCheckBox("Enable automatic reload from disk");
    private final JBCheckBox autoRebuildEnabled = new JBCheckBox("Enable automatic rebuild after reload");
    private final JBTextField debounceDelayField = new JBTextField();
    private final JBTextArea pathRegexFiltersArea = new JBTextArea();
    private final JBTextArea ignoredRegexFiltersArea = new JBTextArea();

    public FileWatcherSettingsComponent() {
        debounceDelayField.setColumns(6);
        pathRegexFiltersArea.setRows(5);
        pathRegexFiltersArea.setLineWrap(false);
        ignoredRegexFiltersArea.setRows(5);
        ignoredRegexFiltersArea.setLineWrap(false);

        JScrollPane includedScrollPane = new JScrollPane(pathRegexFiltersArea);
        includedScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        JScrollPane ignoredScrollPane = new JScrollPane(ignoredRegexFiltersArea);
        ignoredScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // Create donation link
        LinkLabel<String> donateLink = new LinkLabel<>("Donate", null, (aSource, aLinkData) -> {
            BrowserUtil.browse("https://www.paypal.com/donate/?hosted_button_id=C9U54KULFG48C");
        });
        donateLink.setToolTipText("Support the development of this plugin");

        // Create a panel for the header with donate link
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(new JBLabel("<html><b>File Watching Configuration</b></html>"), BorderLayout.WEST);
        headerPanel.add(donateLink, BorderLayout.EAST);

        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(headerPanel, 0)
                .addVerticalGap(10)
                .addComponent(new JBLabel("<html>Configure which files should trigger auto-reload and rebuild:</html>"), 0)
                .addVerticalGap(5)
                .addComponent(isInSource, 1)
                .addTooltip("Include files in source directories (src/main/java, etc.)")
                .addComponent(isInTestSource, 1)
                .addTooltip("Include files in test source directories (src/test/java, etc.)")
                .addComponent(isInGeneratedSource, 1)
                .addTooltip("Include files in generated source directories")
                .addComponent(isInContent, 1)
                .addTooltip("Include files that are part of the project content")
                .addVerticalGap(15)
                .addComponent(new JBLabel("<html><b>Included Path Regex Filters</b></html>"), 0)
                .addVerticalGap(5)
                .addComponent(new JBLabel("<html>Enter regex patterns (one per line) to INCLUDE file paths. Files matching at least one pattern will be watched:<br/>Example: .*\\.java$ (matches all Java files)</html>"), 0)
                .addVerticalGap(5)
                .addComponent(includedScrollPane, 3)
                .addVerticalGap(15)
                .addComponent(new JBLabel("<html><b>Ignored Path Regex Filters</b></html>"), 0)
                .addVerticalGap(5)
                .addComponent(new JBLabel("<html>Enter regex patterns (one per line) to IGNORE file paths. Files matching any pattern will be ignored:<br/>Example: .*\\.log$ (ignores all log files)</html>"), 0)
                .addVerticalGap(5)
                .addComponent(ignoredScrollPane, 3)
                .addVerticalGap(15)
                .addComponent(new JBLabel("<html><b>Actions Configuration</b></html>"), 0)
                .addVerticalGap(10)
                .addComponent(autoReloadEnabled, 1)
                .addTooltip("Automatically reload files from disk when changes are detected")
                .addComponent(autoRebuildEnabled, 1)
                .addTooltip("Automatically rebuild the project after reloading files")
                .addVerticalGap(10)
                .addLabeledComponent(new JBLabel("Debounce delay (milliseconds):"), debounceDelayField, 1)
                .addTooltip("Wait this many milliseconds after the last change before triggering reload/rebuild")
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public JPanel getPanel() {
        return mainPanel;
    }

    public boolean isInContent() {
        return isInContent.isSelected();
    }

    public void setIsInContent(boolean value) {
        isInContent.setSelected(value);
    }

    public boolean isInSource() {
        return isInSource.isSelected();
    }

    public void setIsInSource(boolean value) {
        isInSource.setSelected(value);
    }

    public boolean isInTestSource() {
        return isInTestSource.isSelected();
    }

    public void setIsInTestSource(boolean value) {
        isInTestSource.setSelected(value);
    }

    public boolean isInGeneratedSource() {
        return isInGeneratedSource.isSelected();
    }

    public void setIsInGeneratedSource(boolean value) {
        isInGeneratedSource.setSelected(value);
    }

    public boolean isAutoReloadEnabled() {
        return autoReloadEnabled.isSelected();
    }

    public void setAutoReloadEnabled(boolean value) {
        autoReloadEnabled.setSelected(value);
    }

    public boolean isAutoRebuildEnabled() {
        return autoRebuildEnabled.isSelected();
    }

    public void setAutoRebuildEnabled(boolean value) {
        autoRebuildEnabled.setSelected(value);
    }

    public int getDebounceDelayMs() {
        try {
            return Integer.parseInt(debounceDelayField.getText());
        } catch (NumberFormatException e) {
            return 500; // default
        }
    }

    public void setDebounceDelayMs(int value) {
        debounceDelayField.setText(String.valueOf(value));
    }

    public String getPathRegexFilters() {
        return pathRegexFiltersArea.getText();
    }

    public void setPathRegexFilters(String value) {
        pathRegexFiltersArea.setText(value != null ? value : "");
    }

    public String getIgnoredRegexFilters() {
        return ignoredRegexFiltersArea.getText();
    }

    public void setIgnoredRegexFilters(String value) {
        ignoredRegexFiltersArea.setText(value != null ? value : "");
    }
}
