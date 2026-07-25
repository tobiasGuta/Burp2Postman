package com.tobiasare.burp2postman;

import burp.api.montoya.MontoyaApi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static com.tobiasare.burp2postman.Models.Destination;
import static com.tobiasare.burp2postman.Models.FolderRef;
import static com.tobiasare.burp2postman.Models.ItemRef;

final class Burp2PostmanPanel {
    private static final FolderRef COLLECTION_ROOT = new FolderRef("", "Collection root", "(Collection root)");

    private final MontoyaApi api;
    private final PostmanClient client;
    private final ConfigStore store;
    private final ExecutorService executor;
    private final Consumer<String> externalLogger;

    private final JPanel root = new JPanel(new BorderLayout(10, 10));
    private final JPasswordField apiKeyField = new JPasswordField();
    private final JTextField baseUrlField = new JTextField();
    private final JCheckBox rememberApiKey = new JCheckBox("Remember API key in Burp preferences");
    private final JComboBox<ItemRef> workspaceCombo = new JComboBox<>();
    private final JComboBox<ItemRef> collectionCombo = new JComboBox<>();
    private final JComboBox<FolderRef> folderCombo = new JComboBox<>();
    private final JCheckBox preserveHost = new JCheckBox("Preserve custom Host header", true);
    private final JCheckBox removeTransportHeaders = new JCheckBox("Remove transport-managed headers", true);
    private final JButton connectButton = new JButton("Connect / Refresh");
    private final JButton saveButton = new JButton("Save default destination");
    private final JButton createCollectionButton = new JButton("New collection");
    private final JButton createFolderButton = new JButton("New folder");
    private final JLabel statusLabel = new JLabel("Not connected");
    private final JTextArea logArea = new JTextArea();

    private volatile boolean suppressSelectionEvents;
    private volatile Destination currentDestination;

    Burp2PostmanPanel(
            MontoyaApi api,
            PostmanClient client,
            ConfigStore store,
            ExecutorService executor,
            Consumer<String> externalLogger
    ) {
        this.api = api;
        this.client = client;
        this.store = store;
        this.executor = executor;
        this.externalLogger = externalLogger;
        buildUi();
        restoreSettings();
    }

    JComponent component() {
        return root;
    }

    String apiKey() {
        return new String(apiKeyField.getPassword()).trim();
    }

    String baseUrl() {
        return PostmanClient.normalizeBaseUrl(baseUrlField.getText());
    }

    Destination currentDestination() {
        return currentDestination;
    }

    RequestConverter.Options requestOptions() {
        return new RequestConverter.Options(preserveHost.isSelected(), removeTransportHeaders.isSelected());
    }

    DestinationDialog.Selection chooseDestination(Component parent) {
        String key = apiKey();
        if (key.isBlank()) {
            JOptionPane.showMessageDialog(parent, "Enter and connect a Postman API key first.",
                    "Burp2Postman", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        final String base;
        try {
            base = baseUrl();
        } catch (RuntimeException ex) {
            showError(ex);
            return null;
        }
        DestinationDialog dialog = new DestinationDialog(
                SwingUtilities.getWindowAncestor(parent),
                client,
                executor,
                base,
                key,
                currentDestination
        );
        DestinationDialog.Selection selection = dialog.showDialog();
        if (selection != null && selection.makeDefault()) {
            applyDestination(selection.destination(), true);
        }
        return selection;
    }

    void appendLog(String message) {
        Runnable update = () -> {
            String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.append("[" + timestamp + "] " + message + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) update.run();
        else SwingUtilities.invokeLater(update);
        externalLogger.accept(message);
    }

    void setStatus(String text) {
        Runnable update = () -> statusLabel.setText(text);
        if (SwingUtilities.isEventDispatchThread()) update.run();
        else SwingUtilities.invokeLater(update);
    }

    private void buildUi() {
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel configuration = new JPanel(new GridBagLayout());
        configuration.setBorder(BorderFactory.createTitledBorder("Postman connection and destination"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        c.gridx = 0;
        c.gridy = 0;

        addRow(configuration, c, "API base URL", baseUrlField);
        addRow(configuration, c, "Postman API key", apiKeyField);

        c.gridx = 1;
        c.weightx = 1;
        configuration.add(rememberApiKey, c);
        c.gridy++;

        addRow(configuration, c, "Workspace", workspaceCombo);
        addRow(configuration, c, "Collection", collectionCombo);
        addRow(configuration, c, "Folder", folderCombo);

        JPanel connectionButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        connectionButtons.add(connectButton);
        connectionButtons.add(createCollectionButton);
        connectionButtons.add(createFolderButton);
        connectionButtons.add(saveButton);
        c.gridx = 1;
        c.weightx = 1;
        configuration.add(connectionButtons, c);
        c.gridy++;

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        options.add(preserveHost);
        options.add(removeTransportHeaders);
        c.gridx = 1;
        configuration.add(options, c);
        c.gridy++;

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        statusPanel.add(new JLabel("Status:"));
        statusPanel.add(statusLabel);
        c.gridx = 1;
        configuration.add(statusPanel, c);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logPane = new JScrollPane(logArea);
        logPane.setBorder(BorderFactory.createTitledBorder("Activity"));

        JTextArea help = new JTextArea(
                "Usage: connect your Postman account, choose a workspace/collection/folder, save it as the default, " +
                        "then right-click one or more requests in Proxy HTTP history and choose Burp2Postman. " +
                        "Exact send preserves credentials; sanitized send replaces common tokens and cookies with Postman variables."
        );
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setOpaque(false);
        help.setBorder(new EmptyBorder(4, 4, 4, 4));

        root.add(configuration, BorderLayout.NORTH);
        root.add(logPane, BorderLayout.CENTER);
        root.add(help, BorderLayout.SOUTH);

        connectButton.addActionListener(e -> connect());
        workspaceCombo.addActionListener(e -> {
            if (!suppressSelectionEvents) loadCollections(selected(workspaceCombo));
        });
        collectionCombo.addActionListener(e -> {
            if (!suppressSelectionEvents) loadFolders(selected(collectionCombo));
        });
        saveButton.addActionListener(e -> saveCurrentDestination());
        createCollectionButton.addActionListener(e -> createCollection());
        createFolderButton.addActionListener(e -> createFolder());
        rememberApiKey.addActionListener(e -> {
            store.rememberApiKey(rememberApiKey.isSelected());
            store.apiKey(apiKey());
        });
        preserveHost.addActionListener(e -> store.preserveHostHeader(preserveHost.isSelected()));
        removeTransportHeaders.addActionListener(e -> store.removeTransportHeaders(removeTransportHeaders.isSelected()));

        createCollectionButton.setEnabled(false);
        createFolderButton.setEnabled(false);
        saveButton.setEnabled(false);
        api.userInterface().applyThemeToComponent(root);
    }

    private void restoreSettings() {
        baseUrlField.setText(store.baseUrl());
        rememberApiKey.setSelected(store.rememberApiKey());
        apiKeyField.setText(store.apiKey());
        preserveHost.setSelected(store.preserveHostHeader());
        removeTransportHeaders.setSelected(store.removeTransportHeaders());
        currentDestination = store.destination();
        if (currentDestination != null) {
            statusLabel.setText("Saved default: " + currentDestination.displayName());
        }
    }

    private void connect() {
        final String key = apiKey();
        final String base;
        try {
            base = baseUrl();
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        if (key.isBlank()) {
            showError(new IllegalArgumentException("Enter a Postman API key."));
            return;
        }

        setBusy(true, "Connecting to Postman…");
        executor.submit(() -> {
            try {
                List<ItemRef> workspaces = client.getWorkspaces(base, key);
                SwingUtilities.invokeLater(() -> {
                    suppressSelectionEvents = true;
                    setItems(workspaceCombo, workspaces);
                    selectById(workspaceCombo, currentDestination == null ? "" : currentDestination.workspace().id());
                    suppressSelectionEvents = false;
                    setBusy(false, "Connected. " + workspaces.size() + " workspace(s) loaded.");
                    store.baseUrl(base);
                    store.rememberApiKey(rememberApiKey.isSelected());
                    store.apiKey(key);
                    createCollectionButton.setEnabled(workspaceCombo.getSelectedItem() != null);
                    ItemRef workspace = selected(workspaceCombo);
                    if (workspace != null) loadCollections(workspace);
                    appendLog("Connected to Postman and loaded " + workspaces.size() + " workspace(s).");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, "Connection failed");
                    showError(ex);
                });
            }
        });
    }

    private void loadCollections(ItemRef workspace) {
        if (workspace == null) {
            return;
        }
        final String base;
        try {
            base = baseUrl();
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        final String key = apiKey();
        setStatus("Loading collections…");
        executor.submit(() -> {
            try {
                List<ItemRef> collections = client.getCollections(base, key, workspace.id());
                SwingUtilities.invokeLater(() -> {
                    suppressSelectionEvents = true;
                    setItems(collectionCombo, collections);
                    String savedId = currentDestination != null
                            && Objects.equals(currentDestination.workspace().id(), workspace.id())
                            ? currentDestination.collection().id() : "";
                    selectById(collectionCombo, savedId);
                    suppressSelectionEvents = false;
                    createFolderButton.setEnabled(collectionCombo.getSelectedItem() != null);
                    saveButton.setEnabled(collectionCombo.getSelectedItem() != null);
                    setStatus(collections.size() + " collection(s) loaded.");
                    ItemRef collection = selected(collectionCombo);
                    if (collection != null) loadFolders(collection);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError(ex));
            }
        });
    }

    private void loadFolders(ItemRef collection) {
        if (collection == null) {
            return;
        }
        final String base;
        try {
            base = baseUrl();
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        final String key = apiKey();
        setStatus("Loading folders…");
        executor.submit(() -> {
            try {
                List<FolderRef> folders = client.getFolders(base, key, collection.id());
                SwingUtilities.invokeLater(() -> {
                    suppressSelectionEvents = true;
                    folderCombo.removeAllItems();
                    folderCombo.addItem(COLLECTION_ROOT);
                    folders.forEach(folderCombo::addItem);
                    String savedId = currentDestination != null
                            && Objects.equals(currentDestination.collection().id(), collection.id())
                            ? currentDestination.folderId() : "";
                    selectFolderById(folderCombo, savedId);
                    suppressSelectionEvents = false;
                    setStatus("Ready. " + folders.size() + " folder(s) loaded.");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError(ex));
            }
        });
    }

    private void saveCurrentDestination() {
        ItemRef workspace = selected(workspaceCombo);
        ItemRef collection = selected(collectionCombo);
        FolderRef folder = selected(folderCombo);
        if (workspace == null || collection == null) {
            showError(new IllegalStateException("Choose a workspace and collection first."));
            return;
        }
        Destination destination = new Destination(workspace, collection,
                folder == null || folder.id().isBlank() ? null : folder);
        try {
            applyDestination(destination, true);
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        appendLog("Saved default destination: " + destination.displayName());
    }

    private void applyDestination(Destination destination, boolean persist) {
        currentDestination = destination;
        if (persist) {
            store.destination(destination);
            store.baseUrl(baseUrl());
            store.rememberApiKey(rememberApiKey.isSelected());
            store.apiKey(apiKey());
        }
        setStatus("Default: " + destination.displayName());
    }

    private void createCollection() {
        ItemRef workspace = selected(workspaceCombo);
        if (workspace == null) {
            showError(new IllegalStateException("Choose a workspace first."));
            return;
        }
        String name = JOptionPane.showInputDialog(root, "Collection name:", "New Postman collection",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;

        final String base;
        try {
            base = baseUrl();
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        final String key = apiKey();
        setStatus("Creating collection…");
        executor.submit(() -> {
            try {
                ItemRef created = client.createCollection(base, key, workspace.id(), name.trim());
                SwingUtilities.invokeLater(() -> {
                    appendLog("Created collection: " + created.name());
                    loadCollections(workspace);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError(ex));
            }
        });
    }

    private void createFolder() {
        ItemRef collection = selected(collectionCombo);
        if (collection == null) {
            showError(new IllegalStateException("Choose a collection first."));
            return;
        }
        FolderRef parent = selected(folderCombo);
        String name = JOptionPane.showInputDialog(root, "Folder name:", "New Postman folder",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;

        final String base;
        try {
            base = baseUrl();
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        final String key = apiKey();
        setStatus("Creating folder…");
        executor.submit(() -> {
            try {
                client.createFolder(base, key, collection.id(),
                        parent == null ? "" : parent.id(), name.trim());
                SwingUtilities.invokeLater(() -> {
                    appendLog("Created folder: " + name.trim());
                    loadFolders(collection);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError(ex));
            }
        });
    }

    private void showError(Throwable throwable) {
        String message = rootMessage(throwable);
        setStatus("Error: " + message);
        appendLog("ERROR: " + message);
        JOptionPane.showMessageDialog(root, message, "Burp2Postman error", JOptionPane.ERROR_MESSAGE);
    }

    private void setBusy(boolean busy, String status) {
        connectButton.setEnabled(!busy);
        workspaceCombo.setEnabled(!busy);
        collectionCombo.setEnabled(!busy);
        folderCombo.setEnabled(!busy);
        setStatus(status);
    }

    private static void addRow(JPanel panel, GridBagConstraints c, String label, JComponent component) {
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel(label + ":"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(component, c);
        c.gridy++;
    }

    private static <T> T selected(JComboBox<T> combo) {
        @SuppressWarnings("unchecked")
        T item = (T) combo.getSelectedItem();
        return item;
    }

    private static void setItems(JComboBox<ItemRef> combo, List<ItemRef> items) {
        combo.removeAllItems();
        items.forEach(combo::addItem);
    }

    private static void selectById(JComboBox<ItemRef> combo, String id) {
        if (id == null || id.isBlank()) {
            if (combo.getItemCount() > 0) combo.setSelectedIndex(0);
            return;
        }
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (id.equals(combo.getItemAt(i).id())) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        if (combo.getItemCount() > 0) combo.setSelectedIndex(0);
    }

    private static void selectFolderById(JComboBox<FolderRef> combo, String id) {
        if (id == null || id.isBlank()) {
            combo.setSelectedIndex(0);
            return;
        }
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (id.equals(combo.getItemAt(i).id())) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.setSelectedIndex(0);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
