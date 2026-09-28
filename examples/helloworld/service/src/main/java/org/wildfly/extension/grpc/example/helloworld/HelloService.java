/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.grpc.example.helloworld;

import jakarta.enterprise.context.ApplicationScoped;

import static java.lang.String.format;

@ApplicationScoped
public class HelloService {
    public String hello(String name) {
        return format("Hello, %s", name);
    }
}
