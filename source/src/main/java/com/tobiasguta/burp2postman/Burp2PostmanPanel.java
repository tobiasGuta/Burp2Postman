package com.tobiasguta.burp2postman;

import burp.api.montoya.MontoyaApi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static com.tobiasguta.burp2postman.Models.Destination;
import static com.tobiasguta.burp2postman.Models.FolderRef;
import static com.tobiasguta.burp2postman.Models.ItemRef;

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
    private final JCheckBox customEndpoint = new JCheckBox("Advanced: use a custom API endpoint");
    private final JLabel apiKeyDestinationHost = new JLabel(ApiEndpoint.DEFAULT_HOST);
    private final JCheckBox rememberApiKey = new JCheckBox("Remember API key in Burp preferences");
    private final JComboBox<ItemRef> workspaceCombo = new JComboBox<>();
    private final JComboBox<ItemRef> collectionCombo = new JComboBox<>();
    private final JComboBox<FolderRef> folderCombo = new JComboBox<>();
    private final JCheckBox preserveHost = new JCheckBox("Preserve custom Host header", true);
    private final JCheckBox removeTransportHeaders = new JCheckBox("Remove transport-managed headers", true);
    private final JComboBox<RequestConverter.HeaderFormat> headerFormat =
            new JComboBox<>(RequestConverter.HeaderFormat.values());
    private final JButton connectButton = new JButton("Connect / Refresh");
    private final JButton saveButton = new JButton("Save default destination");
    private final JButton createCollectionButton = new JButton("New collection");
    private final JButton createFolderButton = new JButton("New folder");
    private final JLabel statusLabel = new JLabel("Not connected");
    private final JTextArea logArea = new JTextArea();

    private volatile boolean suppressSelectionEvents;
    private volatile boolean suppressEndpointEvents;
    private volatile Destination currentDestination;
    private volatile String connectedEndpointBaseUrl;
    private volatile String confirmedCustomBaseUrl;
    private final AtomicLong workspaceLoadGeneration = new AtomicLong();
    private final AtomicLong collectionLoadGeneration = new AtomicLong();
    private final AtomicLong folderLoadGeneration = new AtomicLong();

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
        return ApiEndpoint.normalize(customEndpoint.isSelected()
                ? baseUrlField.getText()
                : ApiEndpoint.DEFAULT_BASE_URL);
    }

    ApiEndpoint approvedEndpoint(Component parent) {
        String base = baseUrl();
        String host = ApiEndpoint.hostOf(base);
        if (ApiEndpoint.DEFAULT_HOST.equalsIgnoreCase(host)) {
            return ApiEndpoint.defaultEndpoint();
        }
        if (!customEndpoint.isSelected()) {
            throw new IllegalStateException("Enable the Advanced custom API endpoint option first.");
        }
        if (base.equals(confirmedCustomBaseUrl)) {
            return ApiEndpoint.confirmed(base);
        }
        int choice = JOptionPane.showConfirmDialog(
                parent,
                "The Postman X-API-Key will be sent to this exact hostname:\n\n"
                        + host
                        + "\n\nOnly continue if you trust and intended to configure this host.",
                "Confirm custom API-key destination",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (choice != JOptionPane.YES_OPTION) return null;
        confirmedCustomBaseUrl = base;
        return ApiEndpoint.confirmed(base);
    }

    Destination currentDestination() {
        Destination destination = currentDestination;
        if (destination == null) return null;
        try {
            return destination.endpointBaseUrl().equals(baseUrl()) ? destination : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    RequestConverter.Options requestOptions() {
        return new RequestConverter.Options(
                preserveHost.isSelected(),
                removeTransportHeaders.isSelected(),
                (RequestConverter.HeaderFormat) headerFormat.getSelectedItem()
        );
    }

    DestinationDialog.Selection chooseDestination(Component parent) {
        String key = apiKey();
        if (key.isBlank()) {
            JOptionPane.showMessageDialog(parent, "Enter and connect a Postman API key first.",
                    "Burp2Postman", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        final ApiEndpoint endpoint;
        try {
            endpoint = approvedEndpoint(parent);
        } catch (RuntimeException ex) {
            showError(ex);
            return null;
        }
        if (endpoint == null) return null;
        DestinationDialog dialog = new DestinationDialog(
                SwingUtilities.getWindowAncestor(parent),
                client,
                executor,
                endpoint,
                key,
                currentDestination()
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

        c.gridx = 1;
        c.weightx = 1;
        configuration.add(customEndpoint, c);
        c.gridy++;

        JPanel keyDestination = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        keyDestination.add(new JLabel("X-API-Key destination hostname: "));
        keyDestination.add(apiKeyDestinationHost);
        c.gridx = 1;
        configuration.add(keyDestination, c);
        c.gridy++;

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

        addRow(configuration, c, "Header payload format", headerFormat);

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
        headerFormat.addActionListener(e -> store.headerFormat(
                (RequestConverter.HeaderFormat) headerFormat.getSelectedItem()));
        customEndpoint.addActionListener(e -> {
            confirmedCustomBaseUrl = null;
            suppressEndpointEvents = true;
            try {
                baseUrlField.setEnabled(customEndpoint.isSelected());
                if (!customEndpoint.isSelected()) {
                    baseUrlField.setText(ApiEndpoint.DEFAULT_BASE_URL);
                }
            } finally {
                suppressEndpointEvents = false;
            }
            store.customEndpointEnabled(customEndpoint.isSelected());
            refreshApiKeyDestinationHost();
            endpointConfigurationChanged();
        });
        baseUrlField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                endpointTextChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                endpointTextChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                endpointTextChanged();
            }
        });

        createCollectionButton.setEnabled(false);
        createFolderButton.setEnabled(false);
        saveButton.setEnabled(false);
        api.userInterface().applyThemeToComponent(root);
    }

    private void restoreSettings() {
        boolean enableCustomEndpoint = store.customEndpointEnabled();
        suppressEndpointEvents = true;
        try {
            customEndpoint.setSelected(enableCustomEndpoint);
            baseUrlField.setText(enableCustomEndpoint ? store.baseUrl() : ApiEndpoint.DEFAULT_BASE_URL);
            baseUrlField.setEnabled(enableCustomEndpoint);
        } finally {
            suppressEndpointEvents = false;
        }
        refreshApiKeyDestinationHost();
        rememberApiKey.setSelected(store.rememberApiKey());
        apiKeyField.setText(store.apiKey());
        preserveHost.setSelected(store.preserveHostHeader());
        removeTransportHeaders.setSelected(store.removeTransportHeaders());
        headerFormat.setSelectedItem(store.headerFormat());
        currentDestination = store.destination();
        try {
            if (currentDestination != null
                    && !currentDestination.endpointBaseUrl().equals(baseUrl())) {
                currentDestination = null;
                store.destination(null);
            }
        } catch (RuntimeException ignored) {
            currentDestination = null;
            store.destination(null);
        }
        if (currentDestination != null) {
            statusLabel.setText("Saved default: " + currentDestination.displayName());
        }
    }

    private void connect() {
        final String key = apiKey();
        if (key.isBlank()) {
            showError(new IllegalArgumentException("Enter a Postman API key."));
            return;
        }
        final ApiEndpoint endpoint;
        try {
            endpoint = approvedEndpoint(root);
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        if (endpoint == null) return;

        long generation = workspaceLoadGeneration.incrementAndGet();
        collectionLoadGeneration.incrementAndGet();
        folderLoadGeneration.incrementAndGet();
        setBusy(true, "Connecting to Postman…");
        executor.submit(() -> {
            try {
                List<ItemRef> workspaces = client.getWorkspaces(endpoint, key);
                SwingUtilities.invokeLater(() -> {
                    if (generation != workspaceLoadGeneration.get()) return;
                    suppressSelectionEvents = true;
                    setItems(workspaceCombo, workspaces);
                    selectById(workspaceCombo, currentDestination == null ? "" : currentDestination.workspace().id());
                    suppressSelectionEvents = false;
                    setBusy(false, "Connected. " + workspaces.size() + " workspace(s) loaded.");
                    store.baseUrl(endpoint.baseUrl());
                    store.customEndpointEnabled(customEndpoint.isSelected());
                    store.rememberApiKey(rememberApiKey.isSelected());
                    store.apiKey(key);
                    connectedEndpointBaseUrl = endpoint.baseUrl();
                    createCollectionButton.setEnabled(workspaceCombo.getSelectedItem() != null);
                    ItemRef workspace = selected(workspaceCombo);
                    if (workspace != null) loadCollections(workspace);
                    appendLog("Connected to Postman and loaded " + workspaces.size() + " workspace(s).");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (generation != workspaceLoadGeneration.get()) return;
                    setBusy(false, "Connection failed");
                    showError(ex);
                });
            }
        });
    }

    private void loadCollections(ItemRef workspace) {
        long generation = collectionLoadGeneration.incrementAndGet();
        folderLoadGeneration.incrementAndGet();
        if (workspace == null) {
            return;
        }
        final ApiEndpoint endpoint;
        try {
            endpoint = approvedEndpoint(root);
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        if (endpoint == null) return;
        final String key = apiKey();
        setStatus("Loading collections…");
        executor.submit(() -> {
            try {
                List<ItemRef> collections = client.getCollections(endpoint, key, workspace.id());
                SwingUtilities.invokeLater(() -> {
                    if (generation != collectionLoadGeneration.get()) return;
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
                SwingUtilities.invokeLater(() -> {
                    if (generation == collectionLoadGeneration.get()) showError(ex);
                });
            }
        });
    }

    private void loadFolders(ItemRef collection) {
        long generation = folderLoadGeneration.incrementAndGet();
        if (collection == null) {
            return;
        }
        final ApiEndpoint endpoint;
        try {
            endpoint = approvedEndpoint(root);
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        if (endpoint == null) return;
        final String key = apiKey();
        setStatus("Loading folders…");
        executor.submit(() -> {
            try {
                List<FolderRef> folders = client.getFolders(endpoint, key, collection.id());
                SwingUtilities.invokeLater(() -> {
                    if (generation != folderLoadGeneration.get()) return;
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
                SwingUtilities.invokeLater(() -> {
                    if (generation == folderLoadGeneration.get()) showError(ex);
                });
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
        if (connectedEndpointBaseUrl == null
                || !connectedEndpointBaseUrl.equals(baseUrl())) {
            showError(new IllegalStateException(
                    "Reconnect and load a destination from the current API endpoint first."));
            return;
        }
        Destination destination = new Destination(connectedEndpointBaseUrl, workspace, collection,
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
        if (!destination.endpointBaseUrl().equals(baseUrl())) {
            throw new IllegalArgumentException(
                    "The destination belongs to a different API endpoint. Reconnect and select it again.");
        }
        currentDestination = destination;
        if (persist) {
            store.destination(destination);
            store.baseUrl(baseUrl());
            store.customEndpointEnabled(customEndpoint.isSelected());
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

        final ApiEndpoint endpoint;
        try {
            endpoint = approvedEndpoint(root);
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        if (endpoint == null) return;
        final String key = apiKey();
        setStatus("Creating collection…");
        executor.submit(() -> {
            try {
                ItemRef created = client.createCollection(endpoint, key, workspace.id(), name.trim());
                SwingUtilities.invokeLater(() -> {
                    if (!isCurrentEndpoint(endpoint)) return;
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

        final ApiEndpoint endpoint;
        try {
            endpoint = approvedEndpoint(root);
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        if (endpoint == null) return;
        final String key = apiKey();
        setStatus("Creating folder…");
        executor.submit(() -> {
            try {
                client.createFolder(endpoint, key, collection.id(),
                        parent == null ? "" : parent.id(), name.trim());
                SwingUtilities.invokeLater(() -> {
                    if (!isCurrentEndpoint(endpoint)) return;
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

    private void refreshApiKeyDestinationHost() {
        String host;
        try {
            host = ApiEndpoint.hostOf(customEndpoint.isSelected()
                    ? baseUrlField.getText()
                    : ApiEndpoint.DEFAULT_BASE_URL);
        } catch (RuntimeException ignored) {
            host = "(invalid URL)";
        }
        apiKeyDestinationHost.setText(host);
    }

    void invalidateDestinationForEndpointMismatch() {
        endpointConfigurationChanged();
    }

    private void endpointTextChanged() {
        confirmedCustomBaseUrl = null;
        refreshApiKeyDestinationHost();
        if (!suppressEndpointEvents) {
            endpointConfigurationChanged();
        }
    }

    private void endpointConfigurationChanged() {
        workspaceLoadGeneration.incrementAndGet();
        collectionLoadGeneration.incrementAndGet();
        folderLoadGeneration.incrementAndGet();
        connectedEndpointBaseUrl = null;
        currentDestination = null;
        store.destination(null);

        suppressSelectionEvents = true;
        try {
            workspaceCombo.removeAllItems();
            collectionCombo.removeAllItems();
            folderCombo.removeAllItems();
        } finally {
            suppressSelectionEvents = false;
        }
        createCollectionButton.setEnabled(false);
        createFolderButton.setEnabled(false);
        saveButton.setEnabled(false);
        connectButton.setEnabled(true);
        workspaceCombo.setEnabled(false);
        collectionCombo.setEnabled(false);
        folderCombo.setEnabled(false);
        setStatus("Endpoint changed. Connect and select a destination.");
    }

    private boolean isCurrentEndpoint(ApiEndpoint endpoint) {
        try {
            return endpoint != null && endpoint.baseUrl().equals(baseUrl());
        } catch (RuntimeException ignored) {
            return false;
        }
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
