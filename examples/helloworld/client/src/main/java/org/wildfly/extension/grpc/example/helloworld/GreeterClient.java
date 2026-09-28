/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.grpc.example.helloworld;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;

import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;

public class GreeterClient {

    private static final Logger logger = Logger.getLogger(GreeterClient.class.getName());

    private final GreeterGrpc.GreeterBlockingStub blockingStub;

    /**
     * Construct client for accessing HelloWorld server using the existing channel.
     */
    public GreeterClient(Channel channel) {
        // 'channel' here is a Channel, not a ManagedChannel, so it is not this code's responsibility to
        // shut it down.

        // Passing Channels to code makes code easier to test and makes it easier to reuse Channels.
        blockingStub = GreeterGrpc.newBlockingStub(channel);
    }

    /**
     * Say hello to server.
     */
    public void greet(String name) {
        logger.info("Will try to greet " + name + " ...");
        HelloRequest request = HelloRequest.newBuilder().setName(name).build();
        HelloReply response;
        try {
            response = blockingStub.sayHello(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return;
        }
        logger.info("Greeting: " + response.getMessage());
    }

    /**
     * Greet server. If provided, the first element of {@code args} is the name to use in the greeting. The second argument is
     * the target server.
     */
    public static void main(String[] args) throws Exception {
        String user = "world";
        String ssl = "none";
        // Default targets: 8080 for plaintext (h2c), 8443 for TLS
        // Use 127.0.0.1 for TLS to avoid IPv6 resolution issues on macOS
        String target = "localhost:8080";
        String tlsTarget = "127.0.0.1:8443";
        ManagedChannel channel = null;

        // Allow passing in the user and target strings as command line arguments
        if (args.length > 0) {
            if ("--help".equals(args[0])) {
                System.err.println("Usage: [name ssl [target]]");
                System.err.println("");
                System.err.println("  name    The name you wish to be greeted by. Defaults to " + user);
                System.err.println("  ssl     none (port 8080), oneway (port 8443), or twoway (port 8443)");
                System.err.println("  target  The server to connect to. Defaults to " + target + " or " + tlsTarget);
                System.exit(1);
            }
            user = args[0];
            ssl = args[1];
        }
        if (args.length > 2) {
            target = args[2];
            tlsTarget = args[2];
        }

        if ("none".equals(ssl)) {
            channel = ManagedChannelBuilder.forTarget(target)
                    .usePlaintext().build();
        } else if ("oneway".equals(ssl)) {
            ChannelCredentials creds = TlsChannelCredentials.newBuilder()
                    .trustManager(caPem(sslDir()))
                    .build();
            channel = Grpc.newChannelBuilder(tlsTarget, creds).build();
        } else if ("twoway".equals(ssl)) {
            ChannelCredentials creds = TlsChannelCredentials.newBuilder()
                    .trustManager(caPem(sslDir()))
                    .keyManager(loadKeyManagers(sslDir()))
                    .build();
            channel = Grpc.newChannelBuilder(tlsTarget, creds).build();
        } else {
            System.err.println("unrecognized ssl value: " + ssl);
        }

        try {
            GreeterClient client = new GreeterClient(channel);
            client.greet(user);
        } finally {
            // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
            // resources the channel should be shut down when it will no longer be used. If it may be used
            // again leave it running.
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static Path sslDir() {
        return Paths.get(System.getProperty("grpc.ssl.dir",
                Paths.get(System.getProperty("user.dir"), "ssl-gen", "target", "generated-certs").toString()));
    }

    private static InputStream caPem(Path sslDir) throws Exception {
        return Files.newInputStream(sslDir.resolve("ca.pem"));
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
