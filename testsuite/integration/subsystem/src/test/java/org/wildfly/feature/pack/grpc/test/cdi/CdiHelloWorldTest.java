/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.feature.pack.grpc.test.cdi;

import java.util.concurrent.TimeUnit;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.feature.pack.grpc.test.helloworld.GreeterGrpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import messages.HelloReply;
import messages.HelloRequest;

/**
 * Verifies CDI integration for gRPC services:
 * <ol>
 * <li>A gRPC service annotated {@code @ApplicationScoped} is obtained from the CDI
 * container rather than instantiated by reflection.</li>
 * <li>{@code @Inject} fields inside a CDI-managed gRPC service are populated.</li>
 * </ol>
 * The deployed service delegates to an injected {@link GreetingService} bean; the
 * response prefix {@code "Hello CDI "} can only appear if injection succeeded.
 */
@RunWith(Arquillian.class)
@RunAsClient
public class CdiHelloWorldTest {

    private static final String TARGET = "localhost:8080";

    private static ManagedChannel channel;
    private static GreeterGrpc.GreeterBlockingStub blockingStub;

    @Deployment
    public static Archive<?> createTestArchive() {
        return ShrinkWrap.create(WebArchive.class, "CdiHelloWorldTest.war")
                .addClasses(CdiGreeterServiceImpl.class, GreetingService.class)
                .addPackage(HelloRequest.class.getPackage())
                .addClass(GreeterGrpc.class)
                // beans.xml activates Weld for this deployment
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @BeforeClass
    public static void beforeClass() {
        channel = ManagedChannelBuilder.forTarget(TARGET).usePlaintext().build();
        blockingStub = GreeterGrpc.newBlockingStub(channel);
    }

    @AfterClass
    public static void afterClass() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void cdiManagedServiceWithInjection() {
        final HelloRequest request = HelloRequest.newBuilder().setName("Bob").build();
        final HelloReply reply = blockingStub.sayHello(request);
        // "Hello CDI Bob" can only be produced if @Inject GreetingService was resolved
        Assert.assertEquals("Hello CDI Bob", reply.getMessage());
    }
}
