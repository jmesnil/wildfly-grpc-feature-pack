/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.feature.pack.grpc.test.helloworld;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;

import javax.net.ssl.TrustManagerFactory;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.wildfly.feature.pack.grpc.InterceptorTracker;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.TlsChannelCredentials;
import messages.HelloRequest;

/**
 * Executes {@link HelloWorldParent#hello() HelloWorldParent.hello()} over a one-way TLS connection.
 * The server is pre-configured with TLS via the provisioning CLI script; no server setup task needed.
 */
@RunWith(Arquillian.class)
@RunAsClient
public class OnewaySecureHelloWorldTest extends HelloWorldParent {

    @Deployment
    public static Archive<?> createTestArchive() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "OnewaySecureHelloWorldTest.war");
        war.addClasses(OnewaySecureHelloWorldTest.class, GreeterServiceImpl.class);
        war.addPackage(HelloRequest.class.getPackage());
        war.addClass(GreeterGrpc.class);
        war.addClass(InterceptorTracker.class);
        return war;
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        final Path sslDir = Paths.get(System.getProperty("grpc.ssl.dir"));
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(sslDir.resolve("client.truststore.p12"))) {
            ts.load(in, "secret".toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        ChannelCredentials creds = TlsChannelCredentials.newBuilder().trustManager(tmf.getTrustManagers()).build();
        channel = Grpc.newChannelBuilderForAddress(TARGET_HOST, SECURE_PORT, creds).build();
        blockingStub = GreeterGrpc.newBlockingStub(channel);
    }
}
