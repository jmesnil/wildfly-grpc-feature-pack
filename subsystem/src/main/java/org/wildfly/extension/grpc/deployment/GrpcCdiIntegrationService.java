/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.grpc.deployment;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.jandex.ClassInfo;
import org.jboss.msc.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.wildfly.extension.grpc.InterceptorQueue;
import org.wildfly.extension.grpc.WildFlyGrpcDeploymentRegistry;
import org.wildfly.extension.grpc._private.GrpcLogger;

import io.grpc.BindableService;
import io.grpc.ServerInterceptor;

/**
 * MSC service that registers gRPC services with the gRPC server once the deployment's
 * Weld {@link BeanManager} is available (for CDI-managed services) or immediately via
 * reflection (for plain services). Installed by {@link GrpcServiceInstallProcessor}.
 * <p>
 * The {@code beanManagerSupplier} is {@code null} when Weld is not available for the
 * deployment; in that case CDI-annotated services fall back to reflection instantiation.
 */
class GrpcCdiIntegrationService implements Service {

    private final DeploymentUnit deploymentUnit;
    private final List<Class<? extends BindableService>> plainServiceClasses;
    private final List<Class<? extends BindableService>> cdiServiceClasses;
    private final List<String> interceptorClassNames;
    private final List<ClassInfo> leafClassInfos;
    private final ClassLoader classLoader;
    private final Supplier<WildFlyGrpcDeploymentRegistry> registrySupplier;
    /** {@code null} if Weld is not available for this deployment. */
    private final Supplier<BeanManager> beanManagerSupplier;

    // Held to properly release @Dependent bean instances on stop
    private final List<CreationalContext<?>> creationalContexts = new ArrayList<>();

    GrpcCdiIntegrationService(final DeploymentUnit deploymentUnit,
            final List<Class<? extends BindableService>> plainServiceClasses,
            final List<Class<? extends BindableService>> cdiServiceClasses,
            final List<String> interceptorClassNames,
            final List<ClassInfo> leafClassInfos,
            final ClassLoader classLoader,
            final Supplier<WildFlyGrpcDeploymentRegistry> registrySupplier,
            final Supplier<BeanManager> beanManagerSupplier) {
        this.deploymentUnit = deploymentUnit;
        this.plainServiceClasses = plainServiceClasses;
        this.cdiServiceClasses = cdiServiceClasses;
        this.interceptorClassNames = interceptorClassNames;
        this.leafClassInfos = leafClassInfos;
        this.classLoader = classLoader;
        this.registrySupplier = registrySupplier;
        this.beanManagerSupplier = beanManagerSupplier;
    }

    @Override
    public void start(final StartContext context) throws StartException {
        final WildFlyGrpcDeploymentRegistry registry = registrySupplier.get();
        final List<ServerInterceptor> interceptors = buildInterceptors();

        // Register management model entries
        processManagement(registry);

        // Plain services — instantiate via reflection
        for (final Class<? extends BindableService> serviceClass : plainServiceClasses) {
            GrpcLogger.LOGGER.registerService(serviceClass.getName(), deploymentUnit.getName());
            registry.addService(deploymentUnit, reflect(serviceClass), interceptors);
        }

        // CDI-annotated services — obtain from BeanManager if available, fall back to reflection
        final BeanManager bm = beanManagerSupplier != null ? beanManagerSupplier.get() : null;
        for (final Class<? extends BindableService> serviceClass : cdiServiceClasses) {
            GrpcLogger.LOGGER.registerCdiService(serviceClass.getName());
            if (bm != null) {
                @SuppressWarnings({ "unchecked", "rawtypes" })
                final Set<Bean<?>> beans = bm.getBeans(serviceClass);
                @SuppressWarnings({ "unchecked", "rawtypes" })
                final Bean bean = bm.resolve(beans);
                if (bean != null) {
                    @SuppressWarnings("unchecked")
                    final CreationalContext<BindableService> cc = bm.createCreationalContext(bean);
                    creationalContexts.add(cc);
                    final BindableService service = serviceClass.cast(bm.getReference(bean, serviceClass, cc));
                    registry.addService(deploymentUnit, service, interceptors);
                    continue;
                }
                GrpcLogger.LOGGER.debug("No CDI bean found for " + serviceClass.getName()
                        + "; falling back to reflection.");
            }
            registry.addService(deploymentUnit, reflect(serviceClass), interceptors);
        }
    }

    @Override
    public void stop(final StopContext context) {
        // Release CDI bean instances; registry cleanup is handled by GrpcDeploymentProcessor.undeploy()
        for (final CreationalContext<?> cc : creationalContexts) {
            cc.release();
        }
        creationalContexts.clear();
    }

    private List<ServerInterceptor> buildInterceptors() throws StartException {
        final InterceptorQueue queue = new InterceptorQueue();
        for (final String className : interceptorClassNames) {
            try {
                queue.add(classLoader.loadClass(className).asSubclass(ServerInterceptor.class));
            } catch (ClassNotFoundException e) {
                throw new StartException("Cannot load interceptor class " + className, e);
            }
        }
        final List<ServerInterceptor> interceptors = new ArrayList<>();
        for (final Class<? extends ServerInterceptor> interceptorType : queue.toList()) {
            GrpcLogger.LOGGER.registerServerInterceptor(interceptorType.getName());
            try {
                interceptors.add(interceptorType.getConstructor().newInstance());
            } catch (NoSuchMethodException | InvocationTargetException | InstantiationException
                    | IllegalAccessException e) {
                throw new StartException("Cannot instantiate interceptor " + interceptorType.getName(), e);
            }
        }
        return interceptors;
    }

    private BindableService reflect(final Class<? extends BindableService> serviceClass) {
        try {
            final Constructor<? extends BindableService> ctor = serviceClass.getConstructor();
            return ctor.newInstance();
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException
                | IllegalAccessException e) {
            throw GrpcLogger.LOGGER.failedToRegister(e, serviceClass.getName());
        }
    }

    private void processManagement(final WildFlyGrpcDeploymentRegistry registry) {
        // Management model registration is handled via the registry; nothing extra needed here.
    }
}
