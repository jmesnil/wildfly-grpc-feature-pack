/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package com.example.grpc.chat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import org.wildfly.extension.grpc.example.chat.ChatMessage;
import org.wildfly.extension.grpc.example.chat.ChatMessageFromServer;
import org.wildfly.extension.grpc.example.chat.ChatServiceGrpc;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.StreamObserver;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class ChatClient extends Application {

    private static ManagedChannel channel = null;

    private final ObservableList<String> messages = FXCollections.observableArrayList();
    private final ListView<String> messagesView = new ListView<>();
    private final TextField name = new TextField("name");
    private final TextField message = new TextField();
    private final Button send = new Button();

    public static void main(String[] args) {
        setup(args);
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        messagesView.setItems(messages);

        send.setText("Send");

        BorderPane pane = new BorderPane();
        pane.setLeft(name);
        pane.setCenter(message);
        pane.setRight(send);

        BorderPane root = new BorderPane();
        root.setCenter(messagesView);
        root.setBottom(pane);

        primaryStage.setTitle("gRPC Chat");
        primaryStage.setScene(new Scene(root, 480, 320));

        primaryStage.show();

        ChatServiceGrpc.ChatServiceStub chatService = ChatServiceGrpc.newStub(channel);
        StreamObserver<ChatMessage> chat = chatService.chat(new StreamObserver<>() {
            @Override
            public void onNext(ChatMessageFromServer value) {
                Platform.runLater(() -> {
                    messages.add(value.getMessage().getFrom() + ": " + value.getMessage().getMessage());
                    messagesView.scrollTo(messages.size());
                });
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
                System.out.println("Disconnected");
            }

            @Override
            public void onCompleted() {
                System.out.println("Disconnected");
            }
        });

        send.setOnAction(e -> {
            chat.onNext(ChatMessage.newBuilder().setFrom(name.getText()).setMessage(message.getText()).build());
            message.setText("");
        });
        primaryStage.setOnCloseRequest(e -> {
            chat.onCompleted();
            channel.shutdown();
        });
    }

    private static void setup(String[] args) {
        // Default targets: 8080 for plaintext (h2c), 8443 for TLS
        // Use 127.0.0.1 for TLS to avoid IPv6 resolution issues on macOS
        String target = "localhost:8080";
        String tlsTarget = "127.0.0.1:8443";
        String ssl = "none";

        // Allow passing in the user and target strings as command line arguments
        if (args.length > 0) {
            if ("--help".equals(args[0])) {
                System.err.println("Usage: [ssl [target]]");
                System.err.println("");
                System.err.println("  ssl     none (port 8080), oneway (port 8443), or twoway (port 8443)");
                System.err.println("  target  The server to connect to. Defaults to " + target + " or " + tlsTarget);
                System.exit(1);
            }
            ssl = args[0];
        }
        if (args.length > 1) {
            target = args[1];
            tlsTarget = args[1];
        }
        try {
            if ("none".equals(ssl)) {
                channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
            } else if ("oneway".equals(ssl)) {
                ChannelCredentials creds = TlsChannelCredentials.newBuilder()
                        .trustManager(loadTrustManagers(sslDir()))
                        .build();
                channel = Grpc.newChannelBuilder(tlsTarget, creds).build();
            } else if ("twoway".equals(ssl)) {
                ChannelCredentials creds = TlsChannelCredentials.newBuilder()
                        .trustManager(loadTrustManagers(sslDir()))
                        .keyManager(loadKeyManagers(sslDir()))
                        .build();
                channel = Grpc.newChannelBuilder(tlsTarget, creds).build();
            } else {
                System.err.println("unrecognized ssl value: " + ssl);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Path sslDir() {
        return Paths.get(System.getProperty("grpc.ssl.dir",
                Paths.get(System.getProperty("user.dir"), "ssl-gen", "target", "generated-certs").toString()));
    }

    private static javax.net.ssl.TrustManager[] loadTrustManagers(Path sslDir) throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(sslDir.resolve("client.truststore.p12"))) {
            ts.load(in, "secret".toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        return tmf.getTrustManagers();
    }

    private static javax.net.ssl.KeyManager[] loadKeyManagers(Path sslDir) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(sslDir.resolve("client.keystore.p12"))) {
            ks.load(in, "secret".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "secret".toCharArray());
        return kmf.getKeyManagers();
    }
}
