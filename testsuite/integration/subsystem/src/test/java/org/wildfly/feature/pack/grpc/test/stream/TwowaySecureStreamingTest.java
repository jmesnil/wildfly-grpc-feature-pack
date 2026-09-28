/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.feature.pack.grpc.test.stream;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.wildfly.extension.grpc.example.chat.ChatServiceGrpc;

import chatmessages.ChatMessage;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.TlsChannelCredentials;

/**
 * Executes {@link StreamingTestParent#streamingTest() StreamingTestParent.streamingTest()}
 * over a mutual TLS connection. The server is pre-configured with TLS via the provisioning
 * CLI script; no server setup task needed.
 */
@RunWith(Arquillian.class)
@RunAsClient
public class TwowaySecureStreamingTest extends StreamingTestParent {

    @Deployment
    public static Archive<?> createTestArchive() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "TwowaySecureStreamingTest.war");
        war.addClasses(OnewaySecureStreamingTest.class, ChatServiceImpl.class, ChatServiceGrpc.class);
        war.addPackage(ChatMessage.class.getPackage());
        return war;
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        final var sslDir = Paths.get(System.getProperty("grpc.ssl.dir"));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(sslDir.resolve("client.keystore.p12"))) {
            ks.load(in, "secret".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "secret".toCharArray());
        ChannelCredentials creds = TlsChannelCredentials.newBuilder()
                .trustManager(Files.newInputStream(sslDir.resolve("ca.pem")))
                .keyManager(kmf.getKeyManagers())
                .build();
        channel = Grpc.newChannelBuilderForAddress(TARGET_HOST, SECURE_PORT, creds).build();
        stub = ChatServiceGrpc.newStub(channel);
    }
}
