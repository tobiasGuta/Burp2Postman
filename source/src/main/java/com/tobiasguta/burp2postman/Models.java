package com.tobiasguta.burp2postman;

import java.util.Objects;

final class Models {
    private Models() {}

    record ItemRef(String id, String name) {
        ItemRef {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? "(unnamed)" : name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    record FolderRef(String id, String name, String path) {
        FolderRef {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? "(unnamed)" : name;
            path = path == null || path.isBlank() ? name : path;
        }

        @Override
        public String toString() {
            return path;
        }
    }

    record Destination(ItemRef workspace, ItemRef collection, FolderRef folder) {
        Destination {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(collection, "collection");
        }

        String folderId() {
            return folder == null ? "" : folder.id();
        }

        String displayName() {
            String base = workspace.name() + " / " + collection.name();
            return folder == null || folder.id().isBlank() ? base : base + " / " + folder.path();
        }
    }

    record SendResult(boolean success, String requestName, String detail) {}
}
