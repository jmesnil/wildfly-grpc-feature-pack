/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package com.example.grpc.chat;

/**
 * Entry point for {@code java -jar chat-client.jar}.
 * <p>
 * JavaFX requires that the class declared as {@code Main-Class} in the jar manifest does
 * <em>not</em> directly extend {@link javafx.application.Application}; otherwise the module
 * system raises an error at startup. This thin launcher delegates to {@link ChatClient#main}
 * which then starts the JavaFX application normally.
 */
public class ChatClientLauncher {

    public static void main(String[] args) {
        ChatClient.main(args);
    }
}
