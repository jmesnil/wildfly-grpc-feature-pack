/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.feature.pack.grpc.test.cdi;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A simple CDI bean injected into {@link CdiGreeterServiceImpl} to verify that
 * {@code @Inject} works inside a CDI-managed gRPC service.
 */
@ApplicationScoped
public class GreetingService {

    public String greet(String name) {
        return "Hello CDI " + name;
    }
}
