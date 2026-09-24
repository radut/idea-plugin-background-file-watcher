package com.radut.plugin.bfw.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.radut.plugin.bfw.FileWatcherEvent;
import com.radut.plugin.bfw.FileWatcherService;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.format.DateTimeFormatter;

public class FileWatcherToolWindowContent {
    private static final int MAX_ROWS = 1_000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final FileWatcherService service;
    private final JPanel contentPanel = new JPanel(new BorderLayout());
    private final DefaultTableModel eventsTableModel;
    private final JBTable eventsTable;
    private final JButton startButton = new JButton("Start Watching");
    private final JButton stopButton = new JButton("Stop Watching");
    private final JBLabel statusLabel = new JBLabel("Status: Unknown");
    private final FileWatcherService.StateChangeListener stateChangeListener = this::updateControlStatus;
    private final FileWatcherService.EventListener eventListener = this::addEvent;

    public FileWatcherToolWindowContent(Project project) {
        service = project.getService(FileWatcherService.class);

        eventsTableModel = new DefaultTableModel(new String[]{"Timestamp", "Event Type", "Trigger", "Matched Rule", "File Path"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return column == 2 ? Boolean.class : String.class;
            }
        };

        eventsTable = new JBTable(eventsTableModel);
        eventsTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        eventsTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        eventsTable.getColumnModel().getColumn(0).setMaxWidth(230);
        eventsTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        eventsTable.getColumnModel().getColumn(1).setMaxWidth(150);
        eventsTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        eventsTable.getColumnModel().getColumn(2).setMaxWidth(150);
        eventsTable.getColumnModel().getColumn(3).setPreferredWidth(200);
        eventsTable.getColumnModel().getColumn(3).setMaxWidth(350);

        JButton clearButton = new JButton("Clear Events");
        clearButton.addActionListener(e -> clear());

        JButton scrollToBottomButton = new JButton("Scroll to Bottom");
        scrollToBottomButton.addActionListener(e -> scrollToBottom());

        startButton.addActionListener(e -> service.startWatching());
        stopButton.addActionListener(e -> service.stopWatching());

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        controlPanel.add(startButton);
        controlPanel.add(stopButton);
        controlPanel.add(statusLabel);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttonPanel.add(scrollToBottomButton);
        buttonPanel.add(clearButton);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(new JBLabel("File Watcher Events - Project: " + project.getName()), BorderLayout.WEST);
        headerPanel.add(controlPanel, BorderLayout.CENTER);
        headerPanel.add(buttonPanel, BorderLayout.EAST);

        contentPanel.add(headerPanel, BorderLayout.NORTH);
        contentPanel.add(new JBScrollPane(eventsTable), BorderLayout.CENTER);

        service.recentEvents().forEach(this::appendRow);
        service.addEventListener(eventListener);
        service.addStateChangeListener(stateChangeListener);
        updateControlStatus();
    }

    public JPanel getContentPanel() {
        return contentPanel;
    }

    private void addEvent(FileWatcherEvent event) {
        SwingUtilities.invokeLater(() -> appendRow(event));
    }

    private void appendRow(FileWatcherEvent event) {
        eventsTableModel.addRow(new Object[]{
                TIME_FORMAT.format(event.time()), event.kind(), event.triggersActions(), event.reason(), event.path()});
        while (eventsTableModel.getRowCount() > MAX_ROWS) {
            eventsTableModel.removeRow(0);
        }
        scrollToBottom();
    }

    private void clear() {
        service.clearRecentEvents();
        eventsTableModel.setRowCount(0);
    }

    private void scrollToBottom() {
        int lastRow = eventsTable.getRowCount() - 1;
        if (lastRow >= 0) {
            eventsTable.scrollRectToVisible(eventsTable.getCellRect(lastRow, 0, true));
        }
    }

    private void updateControlStatus() {
        SwingUtilities.invokeLater(() -> {
            boolean running = service.isRunning();
            statusLabel.setText(running ? "Status: Running" : "Status: Stopped");
            startButton.setEnabled(!running);
            stopButton.setEnabled(running);
        });
    }

    public void dispose() {
        service.removeEventListener(eventListener);
        service.removeStateChangeListener(stateChangeListener);
    }
}
