/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.grpc;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import jakarta.servlet.ServletException;

import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.msc.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.wildfly.extension.grpc._private.GrpcLogger;
import org.wildfly.extension.undertow.Host;
import org.wildfly.extension.undertow.UndertowFilter;

import io.grpc.BindableService;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.servlet.jakarta.GrpcServlet;
import io.grpc.servlet.jakarta.ServletServerBuilder;
import io.grpc.util.MutableHandlerRegistry;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.InstanceHandle;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.util.Headers;

/**
 * An MSC service that serves gRPC traffic via WildFly's Undertow HTTP/2 stack.
 * <p>
 * Rather than opening a separate Netty TCP socket, this service registers an
 * {@link UndertowFilter} on the configured virtual host. The filter inspects the
 * {@code Content-Type} header and routes {@code application/grpc} requests through a
 * programmatic servlet deployment backed by {@code grpc-servlet-jakarta}. All other
 * traffic passes through the existing handler chain unmodified.
 */
class GrpcUndertowService implements Service, WildFlyGrpcDeploymentRegistry {

    private final Consumer<GrpcUndertowService> serviceConsumer;
    private final Supplier<Host> undertowHost;
    private final ServletConfiguration configuration;
    private final Map<String, Collection<ServerServiceDefinition>> deploymentServices;

    private volatile MutableHandlerRegistry registry;
    private volatile GrpcServlet grpcServlet;
    private volatile DeploymentManager deploymentManager;
    private ServletContainer servletContainer;
    private volatile UndertowFilter grpcFilter;
    private volatile Host resolvedHost;

    GrpcUndertowService(final Consumer<GrpcUndertowService> serviceConsumer,
            final Supplier<Host> undertowHost,
            final ServletConfiguration configuration) {
        this.serviceConsumer = serviceConsumer;
        this.undertowHost = undertowHost;
        this.configuration = configuration;
        this.deploymentServices = new ConcurrentHashMap<>();
    }

    @Override
    public void start(final StartContext context) throws StartException {
        registry = new MutableHandlerRegistry();

        // ServletServerBuilder delegates transport concerns (keepalive, connection age/idle)
        // to the servlet container (Undertow). Only message-level settings are applicable.
        final ServletServerBuilder builder = new ServletServerBuilder()
                .fallbackHandlerRegistry(registry)
                .maxInboundMessageSize(configuration.maxInboundMessageSize())
                .maxInboundMetadataSize(configuration.maxInboundMetadataSize());

        grpcServlet = builder.buildServlet();

        // Create a standalone servlet container (independent of WildFly's default container)
        // so this deployment doesn't interfere with application deployments.
        servletContainer = Servlets.newContainer();
        final io.undertow.servlet.api.InstanceFactory<GrpcServlet> factory = () -> new InstanceHandle<GrpcServlet>() {
            @Override
            public GrpcServlet getInstance() {
                return grpcServlet;
            }

            @Override
            public void release() {
            }
        };
        final DeploymentInfo deploymentInfo = Servlets.deployment()
                .setClassLoader(GrpcUndertowService.class.getClassLoader())
                .setContextPath("/")
                .setDeploymentName("wildfly-grpc-subsystem")
                .addServlet(Servlets.servlet("GrpcServlet", GrpcServlet.class, factory)
                        .addMapping("/*").setAsyncSupported(true));

        deploymentManager = servletContainer.addDeployment(deploymentInfo);
        deploymentManager.deploy();

        final HttpHandler servletHandler;
        try {
            servletHandler = deploymentManager.start();
        } catch (ServletException e) {
            deploymentManager.undeploy();
            deploymentManager = null;
            throw new StartException(e);
        }

        // Register an UndertowFilter that wraps the host's root handler.
        // This sits above Undertow's path router so application/grpc requests are
        // intercepted before any context-path dispatch — including ROOT.war deployments
        // at "/". All other traffic passes through to the existing handler chain unchanged.
        grpcFilter = new UndertowFilter() {
            @Override
            public int getPriority() {
                return 1;
            }

            @Override
            public HttpHandler wrap(final HttpHandler next) {
                return Handlers.predicate(
                        exchange -> {
                            final String ct = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
                            return ct != null && ct.regionMatches(true, 0, "application/grpc", 0, 16);
                        },
                        servletHandler, next);
            }
        };
        resolvedHost = undertowHost.get();
        resolvedHost.addFilter(grpcFilter);

        GrpcLogger.LOGGER.grpcServingViaUndertow(resolvedHost.getName());
        serviceConsumer.accept(this);
    }

    @Override
    public void stop(final StopContext context) {
        GrpcLogger.LOGGER.grpcStopping();
        final Host host = resolvedHost;
        if (host != null && grpcFilter != null) {
            host.removeFilter(grpcFilter);
        }
        grpcFilter = null;
        resolvedHost = null;

        if (deploymentManager != null) {
            try {
                deploymentManager.stop();
            } catch (Exception e) {
                GrpcLogger.LOGGER.failedToStopGrpcServer(e);
            }
            try {
                deploymentManager.undeploy();
            } catch (Exception e) {
                GrpcLogger.LOGGER.failedToUndeployGrpcServer(e);
            }
            deploymentManager = null;
        }

        // grpcServlet.destroy() is called by deploymentManager.stop() via the servlet lifecycle
        grpcServlet = null;
        registry = null;

        serviceConsumer.accept(null);
    }

    @Override
    public void addService(final DeploymentUnit deployment, final Class<? extends BindableService> serviceType,
            final List<ServerInterceptor> interceptors) {
        final String deploymentName = deployment.getName();
        GrpcLogger.LOGGER.registerService(serviceType.getName(), deploymentName);
        final BindableService bindableService;
        try {
            final Constructor<? extends BindableService> constructor = serviceType.getConstructor();
            bindableService = constructor.newInstance();
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException
                | IllegalAccessException e) {
            throw GrpcLogger.LOGGER.failedToRegister(e, serviceType.getName(), deploymentName);
        }
        final ServerServiceDefinition ssd = ServerInterceptors.intercept(bindableService, interceptors);
        deploymentServices.computeIfAbsent(deploymentName, k -> ConcurrentHashMap.newKeySet()).add(ssd);
        // TODO https://github.com/wildfly-extras/wildfly-grpc-feature-pack/issues/139
        // addService() silently overwrites if two deployments register a service with the same name;
        // the displaced entry then can't be removed by removeDeploymentServices(), leaking the registration.
        registry.addService(ssd);
    }

    @Override
    public void removeDeploymentServices(final DeploymentUnit deployment) {
        final Collection<ServerServiceDefinition> defs = deploymentServices.remove(deployment.getName());
        if (defs != null) {
            for (ServerServiceDefinition def : defs) {
                registry.removeService(def);
            }
        }
    }
}
