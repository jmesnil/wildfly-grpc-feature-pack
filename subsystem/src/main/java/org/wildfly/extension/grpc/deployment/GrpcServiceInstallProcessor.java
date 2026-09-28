/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.grpc.deployment;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import jakarta.enterprise.inject.spi.BeanManager;

import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.weld.WeldCapability;
import org.jboss.jandex.ClassInfo;
import org.jboss.modules.Module;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.wildfly.extension.grpc.Capabilities;
import org.wildfly.extension.grpc.GrpcSubsystemDefinition;
import org.wildfly.extension.grpc.WildFlyGrpcDeploymentRegistry;
import org.wildfly.extension.grpc._private.GrpcLogger;

import io.grpc.BindableService;

/**
 * INSTALL phase deployment processor: reads the service and interceptor lists stored by
 * {@link GrpcDeploymentProcessor} and installs a named {@link GrpcCdiIntegrationService}
 * MSC service that handles both plain-reflection and CDI-managed gRPC services.
 * <p>
 * For CDI deployments, the service depends on the deployment's Weld {@link BeanManager} and
 * {@code WeldStartService} (which fires after {@code AfterDeploymentValidation}), ensuring all
 * CDI beans are fully registered before instantiation.
 */
public class GrpcServiceInstallProcessor implements DeploymentUnitProcessor {

    @Override
    @SuppressWarnings("unchecked")
    public void deploy(DeploymentPhaseContext phaseContext) {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();

        final List<Class<? extends BindableService>> plainServices = deploymentUnit
                .getAttachment(GrpcDeploymentAttachments.PLAIN_SERVICE_CLASSES);
        final List<Class<? extends BindableService>> cdiServices = deploymentUnit
                .getAttachment(GrpcDeploymentAttachments.CDI_SERVICE_CLASSES);

        final boolean hasAnyService = (plainServices != null && !plainServices.isEmpty())
                || (cdiServices != null && !cdiServices.isEmpty());
        if (!hasAnyService) {
            return;
        }

        final List<String> interceptorNames = deploymentUnit
                .getAttachment(GrpcDeploymentAttachments.INTERCEPTOR_CLASS_NAMES);
        final List<ClassInfo> leafClassInfos = deploymentUnit
                .getAttachment(GrpcDeploymentAttachments.LEAF_CLASS_INFOS);
        final Module module = deploymentUnit.getAttachment(Attachments.MODULE);
        final String deploymentName = deploymentUnit.getName();

        final ServiceName serviceName = deploymentUnit.getServiceName().append("grpc");
        final ServiceBuilder<?> sb = phaseContext.getServiceTarget().addService(serviceName);

        final Supplier<WildFlyGrpcDeploymentRegistry> registrySupplier = sb
                .requires(GrpcSubsystemDefinition.SERVER_CAPABILITY.getCapabilityServiceName());

        Supplier<BeanManager> beanManagerSupplier = null;
        if (cdiServices != null && !cdiServices.isEmpty()) {
            try {
                final WeldCapability weldCapability = deploymentUnit
                        .getAttachment(Attachments.CAPABILITY_SERVICE_SUPPORT)
                        .getOptionalCapabilityRuntimeAPI(Capabilities.WELD_CAPABILITY, WeldCapability.class)
                        .orElse(null);
                if (weldCapability == null) {
                    GrpcLogger.LOGGER.debug("WeldCapability not available for " + deploymentName
                            + "; CDI-annotated gRPC services will fall back to reflection.");
                } else if (!weldCapability.isPartOfWeldDeployment(deploymentUnit)) {
                    GrpcLogger.LOGGER.debug(deploymentName + " is not a Weld deployment;"
                            + " CDI-annotated gRPC services will fall back to reflection.");
                } else {
                    beanManagerSupplier = weldCapability.addBeanManagerService(deploymentUnit, sb);
                    sb.requires(deploymentUnit.getServiceName().append("WeldStartService"));
                }
            } catch (Exception e) {
                GrpcLogger.LOGGER.warn("Failed to wire Weld integration for " + deploymentName, e);
            }
        }

        sb.setInstance(new GrpcCdiIntegrationService(
                deploymentUnit,
                plainServices != null ? plainServices : Collections.emptyList(),
                cdiServices != null ? cdiServices : Collections.emptyList(),
                interceptorNames != null ? interceptorNames : Collections.emptyList(),
                leafClassInfos != null ? leafClassInfos : Collections.emptyList(),
                module.getClassLoader(),
                registrySupplier,
                beanManagerSupplier));
        sb.install();
    }
}
