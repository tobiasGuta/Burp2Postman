package com.tobiasare.burp2postman;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static com.tobiasare.burp2postman.Models.Destination;
import static com.tobiasare.burp2postman.Models.SendResult;

public final class Burp2PostmanExtension implements BurpExtension {
    private MontoyaApi api;
    private ExecutorService executor;
    private PostmanClient postmanClient;
    private RequestConverter converter;
    private Burp2PostmanPanel panel;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("Burp2Postman");

        this.executor = Executors.newFixedThreadPool(4, new DaemonThreadFactory());
        this.postmanClient = new PostmanClient();
        this.converter = new RequestConverter();
        ConfigStore store = new ConfigStore(api.persistence().preferences());

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                initializeUi(store);
            } else {
                SwingUtilities.invokeAndWait(() -> initializeUi(store));
            }
        } catch (Exception e) {
            api.logging().logToError("Unable to initialize Burp2Postman UI", e);
            executor.shutdownNow();
            throw new IllegalStateException("Unable to initialize Burp2Postman UI", e);
        }

        api.userInterface().registerContextMenuItemsProvider(new MenuProvider());
        api.extension().registerUnloadingHandler(() -> executor.shutdownNow());
        api.logging().logToOutput("Burp2Postman 0.1.1 loaded.");
    }

    private void initializeUi(ConfigStore store) {
        panel = new Burp2PostmanPanel(
                api,
                postmanClient,
                store,
                executor,
                message -> api.logging().logToOutput("[Burp2Postman] " + message)
        );
        api.userInterface().registerSuiteTab("Burp2Postman", panel.component());
    }

    private void sendRequests(List<HttpRequest> requests, Destination destination, boolean sanitized, Component parent) {
        if (requests.isEmpty()) {
            return;
        }
        if (destination == null) {
            JOptionPane.showMessageDialog(parent,
                    "Configure and save a Postman destination in the Burp2Postman tab first.",
                    "Burp2Postman", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final String apiKey = panel.apiKey();
        if (apiKey.isBlank()) {
            JOptionPane.showMessageDialog(parent,
                    "Enter and connect a Postman API key in the Burp2Postman tab first.",
                    "Burp2Postman", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final String baseUrl;
        try {
            baseUrl = panel.baseUrl();
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(parent, e.getMessage(), "Burp2Postman", JOptionPane.ERROR_MESSAGE);
            return;
        }
        final RequestConverter.Options options = panel.requestOptions();

        panel.setStatus("Sending " + requests.size() + " request(s) to Postman…");
        panel.appendLog("Sending " + requests.size() + " request(s) to " + destination.displayName()
                + (sanitized ? " using sanitized mode." : " using exact mode."));

        executor.submit(() -> {
            List<SendResult> results = new ArrayList<>();
            for (HttpRequest request : requests) {
                Map<String, Object> payload = converter.convert(request, sanitized, options);
                String fallbackName = request.method() + " " + request.pathWithoutQuery();
                String requestName = payload.get("name") instanceof String name && !name.isBlank()
                        ? name : fallbackName;
                try {
                    String postmanId = postmanClient.createRequest(
                            baseUrl,
                            apiKey,
                            destination.collection().id(),
                            destination.folderId(),
                            payload
                    );
                    results.add(new SendResult(true, requestName, postmanId));
                    panel.appendLog("Sent: " + requestName);
                } catch (Exception e) {
                    String message = rootMessage(e);
                    results.add(new SendResult(false, requestName, message));
                    panel.appendLog("FAILED: " + requestName + " — " + message);
                    api.logging().logToError("Failed to send " + requestName + " to Postman", e);
                }
            }

            long succeeded = results.stream().filter(SendResult::success).count();
            long failed = results.size() - succeeded;
            SwingUtilities.invokeLater(() -> {
                panel.setStatus("Sent " + succeeded + "; failed " + failed + ".");
                String message = "Destination: " + destination.displayName()
                        + "\nSent successfully: " + succeeded
                        + "\nFailed: " + failed;
                JOptionPane.showMessageDialog(parent, message, "Burp2Postman",
                        failed == 0 ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
            });
        });
    }

    private final class MenuProvider implements ContextMenuItemsProvider {
        @Override
        public List<Component> provideMenuItems(ContextMenuEvent event) {
            List<HttpRequestResponse> selected = event.selectedRequestResponses();
            if (selected == null || selected.isEmpty()) {
                return List.of();
            }

            List<HttpRequest> requests = selected.stream()
                    .map(HttpRequestResponse::request)
                    .filter(java.util.Objects::nonNull)
                    .map(HttpRequest::copyToTempFile)
                    .toList();
            if (requests.isEmpty()) {
                return List.of();
            }

            JMenu menu = new JMenu("Burp2Postman");
            Destination destination = panel.currentDestination();

            JMenuItem exact = new JMenuItem(destination == null
                    ? "Send exact request to default destination"
                    : "Send exact to " + destination.collection().name());
            exact.setEnabled(destination != null);
            exact.addActionListener(e -> sendRequests(requests, panel.currentDestination(), false, menu));

            JMenuItem sanitized = new JMenuItem(destination == null
                    ? "Send sanitized request to default destination"
                    : "Send sanitized to " + destination.collection().name());
            sanitized.setEnabled(destination != null);
            sanitized.addActionListener(e -> sendRequests(requests, panel.currentDestination(), true, menu));

            JMenuItem choose = new JMenuItem("Choose destination and send…");
            choose.addActionListener(e -> {
                DestinationDialog.Selection selection = panel.chooseDestination(menu);
                if (selection != null) {
                    sendRequests(requests, selection.destination(), false, menu);
                }
            });

            JMenuItem configure = new JMenuItem("Open Burp2Postman settings");
            configure.addActionListener(e -> JOptionPane.showMessageDialog(menu,
                    "Open the Burp2Postman suite tab to configure the API key and destination.",
                    "Burp2Postman", JOptionPane.INFORMATION_MESSAGE));

            menu.add(exact);
            menu.add(sanitized);
            menu.addSeparator();
            menu.add(choose);
            menu.add(configure);
            return List.of(menu);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "burp2postman-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
