/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.feature.pack.grpc.test.cdi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.wildfly.feature.pack.grpc.test.helloworld.GreeterGrpc;

import io.grpc.stub.StreamObserver;
import messages.HelloReply;
import messages.HelloRequest;

/**
 * A gRPC service that is CDI-managed ({@code @ApplicationScoped}) and uses
 * {@code @Inject} to receive a {@link GreetingService}. The test verifies that:
 * <ol>
 * <li>The subsystem instantiates this class via CDI rather than reflection.</li>
 * <li>CDI injection ({@code @Inject}) is fully functional inside a gRPC service.</li>
 * </ol>
 */
@ApplicationScoped
public class CdiGreeterServiceImpl extends GreeterGrpc.GreeterImplBase {

    @Inject
    GreetingService greetingService;

    @Override
    public void sayHello(final HelloRequest request, final StreamObserver<HelloReply> responseObserver) {
        final String message = greetingService.greet(request.getName());
        responseObserver.onNext(HelloReply.newBuilder().setMessage(message).build());
        responseObserver.onCompleted();
    }
}
