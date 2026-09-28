/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.grpc.deployment;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.CDI;

import org.jboss.as.controller.PathElement;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentResourceSupport;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.annotation.CompositeIndex;
import org.jboss.dmr.ModelNode;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.modules.Module;
import org.wildfly.extension.grpc.Constants;
import org.wildfly.extension.grpc.GrpcExtension;
import org.wildfly.extension.grpc.InterceptorQueue;
import org.wildfly.extension.grpc.WildFlyGrpcDeploymentRegistry;
import org.wildfly.extension.grpc._private.GrpcLogger;

import io.grpc.BindableService;
import io.grpc.ServerInterceptor;

public class GrpcDeploymentProcessor implements DeploymentUnitProcessor {

    private static final DotName BINDABLE_CLASS = DotName.createSimple(BindableService.class.getName());
    private static final DotName SERVER_INTERCEPTOR_CLASS = DotName.createSimple(ServerInterceptor.class.getName());

    // CDI scope annotations — presence on a class means it is CDI-managed
    private static final Set<DotName> CDI_SCOPE_ANNOTATIONS = Set.of(
            DotName.createSimple("jakarta.enterprise.context.ApplicationScoped"),
            DotName.createSimple("jakarta.enterprise.context.Dependent"),
            DotName.createSimple("jakarta.enterprise.context.RequestScoped"),
            DotName.createSimple("jakarta.enterprise.context.SessionScoped"),
            DotName.createSimple("jakarta.enterprise.context.ConversationScoped"),
            DotName.createSimple("jakarta.inject.Singleton"));

    private final WildFlyGrpcDeploymentRegistry registry;

    public GrpcDeploymentProcessor(final WildFlyGrpcDeploymentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void deploy(DeploymentPhaseContext phaseContext) {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final CompositeIndex index = deploymentUnit.getAttachment(Attachments.COMPOSITE_ANNOTATION_INDEX);
        final Module module = deploymentUnit.getAttachment(Attachments.MODULE);
        final ClassLoader classLoader = module.getClassLoader();

        final Collection<ClassInfo> serviceClassInfos = index.getAllKnownImplementors(BINDABLE_CLASS);
        final List<ClassInfo> leaves = getLeafClassInfos(serviceClassInfos);
        processManagement(deploymentUnit, leaves, classLoader);

        final List<ServerInterceptor> interceptors = getInterceptors(
                index.getAllKnownImplementors(SERVER_INTERCEPTOR_CLASS), classLoader);

        for (ClassInfo classInfo : leaves) {
            try {
                final Class<? extends BindableService> serviceClass = classLoader
                        .loadClass(classInfo.name().toString())
                        .asSubclass(BindableService.class);
                final BindableService instance = instantiate(serviceClass, classInfo);
                registry.addService(deploymentUnit, instance, interceptors);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void undeploy(DeploymentUnit context) {
        registry.removeDeploymentServices(context);
    }

    private BindableService instantiate(final Class<? extends BindableService> serviceClass,
            final ClassInfo classInfo) {
        if (isCdiManaged(classInfo)) {
            try {
                return CDI.current().select(serviceClass).get();
            } catch (UnsatisfiedResolutionException e) {
                GrpcLogger.LOGGER.serviceNotCdiManaged(serviceClass.getName());
            }
        }
        try {
            final Constructor<? extends BindableService> constructor = serviceClass.getConstructor();
            return constructor.newInstance();
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException
                | IllegalAccessException e) {
            throw GrpcLogger.LOGGER.failedToRegister(e, serviceClass.getName());
        }
    }

    private boolean isCdiManaged(final ClassInfo classInfo) {
        return CDI_SCOPE_ANNOTATIONS.stream().anyMatch(classInfo::hasDeclaredAnnotation);
    }

    private List<ClassInfo> getLeafClassInfos(final Collection<ClassInfo> classInfos) {
        final List<ClassInfo> leaves = new ArrayList<>();
        for (ClassInfo candidate : classInfos) {
            boolean hasSubclass = classInfos.stream()
                    .anyMatch(other -> other != candidate
                            && candidate.name().equals(other.superName()));
            if (!hasSubclass) {
                leaves.add(candidate);
            }
        }
        return leaves;
    }

    private List<ServerInterceptor> getInterceptors(final Collection<ClassInfo> classInfos,
            final ClassLoader classLoader) {
        final InterceptorQueue queue = new InterceptorQueue();
        try {
            for (ClassInfo ci : classInfos) {
                queue.add(classLoader.loadClass(ci.name().toString()).asSubclass(ServerInterceptor.class));
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        final List<ServerInterceptor> interceptors = new ArrayList<>();
        for (Class<? extends ServerInterceptor> interceptorType : queue.toList()) {
            GrpcLogger.LOGGER.registerServerInterceptor(interceptorType.getName());
            try {
                interceptors.add(interceptorType.getConstructor().newInstance());
            } catch (NoSuchMethodException | InvocationTargetException | InstantiationException
                    | IllegalAccessException e) {
                throw GrpcLogger.LOGGER.failedToRegister(e, interceptorType.getName());
            }
        }
        return interceptors;
    }

    private void processManagement(final DeploymentUnit deploymentUnit, final List<ClassInfo> grpcServiceClassInfos,
            final ClassLoader classLoader) {
        final DeploymentResourceSupport drs = deploymentUnit
                .getAttachment(Attachments.DEPLOYMENT_RESOURCE_SUPPORT);
        for (ClassInfo classInfo : grpcServiceClassInfos) {
            try {
                final Class<?> clazz = classLoader.loadClass(classInfo.name().toString());
                final ModelNode serviceModel = drs.getDeploymentSubModel(GrpcExtension.SUBSYSTEM_NAME,
                        PathElement.pathElement(Constants.GRPC_SERVICE, clazz.getSimpleName()));
                serviceModel.get(Constants.SERVICE_CLASS).set(clazz.getName());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
