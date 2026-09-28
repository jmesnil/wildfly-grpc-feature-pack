/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.grpc.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.msc.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.wildfly.extension.grpc.WildFlyGrpcDeploymentRegistry;
import org.wildfly.extension.grpc._private.GrpcLogger;

import io.grpc.BindableService;
import io.grpc.ServerInterceptor;

/**
 * An MSC service that registers CDI-managed gRPC services with the gRPC server once the
 * deployment's Weld {@link BeanManager} is available. It depends on the deployment's
 * {@code beanmanager} MSC service (so it starts after Weld bootstraps) and on
 * {@code WeldStartService} (which fires after {@code AfterDeploymentValidation}, ensuring
 * all beans are fully registered before we look them up).
 */
class GrpcCdiIntegrationService implements Service {

    private final Supplier<BeanManager> beanManager;
    private final DeploymentUnit deploymentUnit;
    private final List<Class<? extends BindableService>> cdiServiceClasses;
    private final List<ServerInterceptor> interceptors;
    private final WildFlyGrpcDeploymentRegistry registry;

    // Held to properly release @Dependent bean instances on undeploy
    private final List<CreationalContext<?>> creationalContexts = new ArrayList<>();

    GrpcCdiIntegrationService(final Supplier<BeanManager> beanManager,
            final DeploymentUnit deploymentUnit,
            final List<Class<? extends BindableService>> cdiServiceClasses,
            final List<ServerInterceptor> interceptors,
            final WildFlyGrpcDeploymentRegistry registry) {
        this.beanManager = beanManager;
        this.deploymentUnit = deploymentUnit;
        this.cdiServiceClasses = cdiServiceClasses;
        this.interceptors = interceptors;
        this.registry = registry;
    }

    @Override
    public void start(final StartContext context) throws StartException {
        final BeanManager bm = beanManager.get();
        for (final Class<? extends BindableService> serviceClass : cdiServiceClasses) {
            GrpcLogger.LOGGER.registerCdiService(serviceClass.getName());
            @SuppressWarnings({ "unchecked", "rawtypes" })
            final Set<Bean<?>> beans = bm.getBeans(serviceClass);
            @SuppressWarnings({ "unchecked", "rawtypes" })
            final Bean bean = bm.resolve(beans);
            if (bean == null) {
                throw new StartException("No CDI bean found for gRPC service " + serviceClass.getName()
                        + ". Ensure the service class has a CDI scope annotation (e.g. @Dependent).");
            }
            @SuppressWarnings("unchecked")
            final CreationalContext<BindableService> cc = bm.createCreationalContext(bean);
            creationalContexts.add(cc);
            final BindableService service = serviceClass.cast(bm.getReference(bean, serviceClass, cc));
            registry.addService(deploymentUnit, service, interceptors);
        }
    }

    @Override
    public void stop(final StopContext context) {
        // Release CreationalContext to allow CDI to destroy @Dependent bean instances.
        // GrpcDeploymentProcessor.undeploy() removes services from the gRPC registry.
        for (final CreationalContext<?> cc : creationalContexts) {
            cc.release();
        }
        creationalContexts.clear();
    }
}
