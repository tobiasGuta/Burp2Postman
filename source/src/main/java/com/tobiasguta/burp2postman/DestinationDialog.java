package com.tobiasguta.burp2postman;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static com.tobiasguta.burp2postman.Models.Destination;
import static com.tobiasguta.burp2postman.Models.FolderRef;
import static com.tobiasguta.burp2postman.Models.ItemRef;

final class DestinationDialog {
    private static final FolderRef COLLECTION_ROOT = new FolderRef("", "Collection root", "(Collection root)");

    record Selection(Destination destination, boolean makeDefault) {}

    private final JDialog dialog;
    private final PostmanClient client;
    private final ExecutorService executor;
    private final ApiEndpoint endpoint;
    private final String apiKey;
    private final Destination initial;

    private final JComboBox<ItemRef> workspaceCombo = new JComboBox<>();
    private final JComboBox<ItemRef> collectionCombo = new JComboBox<>();
    private final JComboBox<FolderRef> folderCombo = new JComboBox<>();
    private final JCheckBox makeDefault = new JCheckBox("Use this as the new default destination");
    private final JLabel status = new JLabel("Loading workspaces…");
    private final JButton sendButton = new JButton("Send");
    private final JButton cancelButton = new JButton("Cancel");

    private volatile boolean suppressEvents;
    private volatile Selection result;
    private final AtomicLong workspaceLoadGeneration = new AtomicLong();
    private final AtomicLong collectionLoadGeneration = new AtomicLong();
    private final AtomicLong folderLoadGeneration = new AtomicLong();

    DestinationDialog(
            Window owner,
            PostmanClient client,
            ExecutorService executor,
            ApiEndpoint endpoint,
            String apiKey,
            Destination initial
    ) {
        this.client = client;
        this.executor = executor;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.initial = initial;
        this.dialog = new JDialog(owner, "Send to Postman", Dialog.ModalityType.APPLICATION_MODAL);
        buildUi();
    }

    Selection showDialog() {
        loadWorkspaces();
        dialog.setVisible(true);
        return result;
    }

    private void buildUi() {
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(new EmptyBorder(14, 14, 14, 14));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;

        addRow(content, c, "Workspace", workspaceCombo);
        addRow(content, c, "Collection", collectionCombo);
        addRow(content, c, "Folder", folderCombo);

        c.gridx = 1;
        c.weightx = 1;
        content.add(makeDefault, c);
        c.gridy++;
        content.add(status, c);
        c.gridy++;

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancelButton);
        buttons.add(sendButton);
        c.gridx = 0;
        c.gridwidth = 2;
        content.add(buttons, c);

        workspaceCombo.addActionListener(e -> {
            if (!suppressEvents) loadCollections(selected(workspaceCombo));
        });
        collectionCombo.addActionListener(e -> {
            if (!suppressEvents) loadFolders(selected(collectionCombo));
        });
        cancelButton.addActionListener(e -> dialog.dispose());
        sendButton.addActionListener(e -> complete());

        workspaceCombo.setEnabled(false);
        collectionCombo.setEnabled(false);
        folderCombo.setEnabled(false);
        sendButton.setEnabled(false);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(sendButton);
        dialog.setMinimumSize(new Dimension(520, 260));
        dialog.pack();
        dialog.setLocationRelativeTo(dialog.getOwner());
    }

    private void loadWorkspaces() {
        long generation = workspaceLoadGeneration.incrementAndGet();
        collectionLoadGeneration.incrementAndGet();
        folderLoadGeneration.incrementAndGet();
        executor.submit(() -> {
            try {
                List<ItemRef> workspaces = client.getWorkspaces(endpoint, apiKey);
                SwingUtilities.invokeLater(() -> {
                    if (generation != workspaceLoadGeneration.get()) return;
                    suppressEvents = true;
                    replace(workspaceCombo, workspaces);
                    selectItem(workspaceCombo, initial == null ? "" : initial.workspace().id());
                    suppressEvents = false;
                    workspaceCombo.setEnabled(true);
                    ItemRef workspace = selected(workspaceCombo);
                    status.setText(workspaces.size() + " workspace(s) loaded.");
                    if (workspace != null) loadCollections(workspace);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (generation == workspaceLoadGeneration.get()) fail(ex);
                });
            }
        });
    }

    private void loadCollections(ItemRef workspace) {
        long generation = collectionLoadGeneration.incrementAndGet();
        folderLoadGeneration.incrementAndGet();
        if (workspace == null) return;
        collectionCombo.setEnabled(false);
        folderCombo.setEnabled(false);
        sendButton.setEnabled(false);
        status.setText("Loading collections…");

        executor.submit(() -> {
            try {
                List<ItemRef> collections = client.getCollections(endpoint, apiKey, workspace.id());
                SwingUtilities.invokeLater(() -> {
                    if (generation != collectionLoadGeneration.get()) return;
                    suppressEvents = true;
                    replace(collectionCombo, collections);
                    String initialId = initial != null && Objects.equals(initial.workspace().id(), workspace.id())
                            ? initial.collection().id() : "";
                    selectItem(collectionCombo, initialId);
                    suppressEvents = false;
                    collectionCombo.setEnabled(true);
                    sendButton.setEnabled(collectionCombo.getSelectedItem() != null);
                    ItemRef collection = selected(collectionCombo);
                    status.setText(collections.size() + " collection(s) loaded.");
                    if (collection != null) loadFolders(collection);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (generation == collectionLoadGeneration.get()) fail(ex);
                });
            }
        });
    }

    private void loadFolders(ItemRef collection) {
        long generation = folderLoadGeneration.incrementAndGet();
        if (collection == null) return;
        folderCombo.setEnabled(false);
        status.setText("Loading folders…");

        executor.submit(() -> {
            try {
                List<FolderRef> folders = client.getFolders(endpoint, apiKey, collection.id());
                SwingUtilities.invokeLater(() -> {
                    if (generation != folderLoadGeneration.get()) return;
                    suppressEvents = true;
                    folderCombo.removeAllItems();
                    folderCombo.addItem(COLLECTION_ROOT);
                    folders.forEach(folderCombo::addItem);
                    String initialId = initial != null && Objects.equals(initial.collection().id(), collection.id())
                            ? initial.folderId() : "";
                    selectFolder(folderCombo, initialId);
                    suppressEvents = false;
                    folderCombo.setEnabled(true);
                    sendButton.setEnabled(true);
                    status.setText("Ready.");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (generation == folderLoadGeneration.get()) fail(ex);
                });
            }
        });
    }

    private void complete() {
        ItemRef workspace = selected(workspaceCombo);
        ItemRef collection = selected(collectionCombo);
        FolderRef folder = selected(folderCombo);
        if (workspace == null || collection == null) {
            status.setText("Choose a workspace and collection.");
            return;
        }
        Destination destination = new Destination(workspace, collection,
                folder == null || folder.id().isBlank() ? null : folder);
        result = new Selection(destination, makeDefault.isSelected());
        dialog.dispose();
    }

    private void fail(Throwable throwable) {
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        status.setText("Error: " + message);
        JOptionPane.showMessageDialog(dialog, message, "Burp2Postman error", JOptionPane.ERROR_MESSAGE);
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

    private static void replace(JComboBox<ItemRef> combo, List<ItemRef> items) {
        combo.removeAllItems();
        items.forEach(combo::addItem);
    }

    private static void selectItem(JComboBox<ItemRef> combo, String id) {
        if (id != null && !id.isBlank()) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (id.equals(combo.getItemAt(i).id())) {
                    combo.setSelectedIndex(i);
                    return;
                }
            }
        }
        if (combo.getItemCount() > 0) combo.setSelectedIndex(0);
    }

    private static void selectFolder(JComboBox<FolderRef> combo, String id) {
        if (id != null && !id.isBlank()) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (id.equals(combo.getItemAt(i).id())) {
                    combo.setSelectedIndex(i);
                    return;
                }
            }
        }
        if (combo.getItemCount() > 0) combo.setSelectedIndex(0);
    }

    private static <T> T selected(JComboBox<T> combo) {
        @SuppressWarnings("unchecked")
        T value = (T) combo.getSelectedItem();
        return value;
    }
}
