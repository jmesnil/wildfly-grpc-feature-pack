/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.grpc.example.helloworld;

import io.grpc.stub.StreamObserver;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

@Dependent
public class GreeterServiceImpl extends GreeterGrpc.GreeterImplBase {

    @Inject
    HelloService service;

    @Override
    public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        String name = request.getName();
        String message = service.hello(name);
        responseObserver.onNext(HelloReply.newBuilder().setMessage(message).build());
        responseObserver.onCompleted();
    }
}
