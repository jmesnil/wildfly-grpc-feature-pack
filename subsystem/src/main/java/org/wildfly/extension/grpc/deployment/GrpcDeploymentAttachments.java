/*
 *  Copyright The WildFly Authors
 *  SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.grpc.deployment;

import java.util.List;

import org.jboss.as.server.deployment.AttachmentKey;
import org.jboss.jandex.ClassInfo;

import io.grpc.BindableService;

/**
 * Attachment keys used to pass gRPC deployment data from
 * {@link GrpcDeploymentProcessor} (POST_MODULE) to {@link GrpcServiceInstallProcessor} (INSTALL).
 */
public final class GrpcDeploymentAttachments {

    /** Leaf {@link BindableService} implementors detected by Jandex, with CDI scope annotations. */
    @SuppressWarnings("rawtypes")
    public static final AttachmentKey<List> CDI_SERVICE_CLASSES = AttachmentKey.create(List.class);

    /** Leaf {@link BindableService} implementors without CDI scope annotations (plain reflection). */
    @SuppressWarnings("rawtypes")
    public static final AttachmentKey<List> PLAIN_SERVICE_CLASSES = AttachmentKey.create(List.class);

    /** Class names of discovered {@link io.grpc.ServerInterceptor} implementations. */
    @SuppressWarnings("rawtypes")
    public static final AttachmentKey<List> INTERCEPTOR_CLASS_NAMES = AttachmentKey.create(List.class);

    /** All leaf {@link ClassInfo}s for management model registration. */
    @SuppressWarnings("rawtypes")
    public static final AttachmentKey<List> LEAF_CLASS_INFOS = AttachmentKey.create(List.class);

    private GrpcDeploymentAttachments() {
    }
}
