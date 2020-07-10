/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.platform;

import io.enmasse.systemtest.Endpoint;
import io.enmasse.systemtest.Environment;
import io.enmasse.systemtest.condition.OpenShiftVersion;
import io.enmasse.systemtest.framework.LoggerUtils;
import io.enmasse.systemtest.utils.TestUtils;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.utils.HttpClientUtils;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Handles interaction with openshift cluster
 */
public class OpenShift extends Kubernetes {
    private static final Logger log = LoggerUtils.getLogger();

    private static final String OLM_NAMESPACE = "openshift-operators";

    public OpenShift() {
        super(() -> {
            Config config = new ConfigBuilder().withMasterUrl(Environment.getInstance().getApiUrl())
                    .withOauthToken(Environment.getInstance().getApiToken())
                    .build();
            OkHttpClient httpClient = HttpClientUtils.createHttpClient(config);
            // Workaround https://github.com/square/okhttp/issues/3146
            httpClient = httpClient.newBuilder()
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .connectTimeout(Environment.getInstance().getKubernetesApiConnectTimeout())
                    .writeTimeout(Environment.getInstance().getKubernetesApiWriteTimeout())
                    .readTimeout(Environment.getInstance().getKubernetesApiReadTimeout())
                    .build();
            return new DefaultOpenShiftClient(httpClient, new OpenShiftConfig(config));
        });
    }

    @Override
    public Endpoint getMasterEndpoint() {
        return new Endpoint(client.getMasterUrl());
    }

    @Override
    public Endpoint getRestEndpoint() {
        Endpoint endpoint = null;

        endpoint = new Endpoint(client.getMasterUrl());

        if (TestUtils.resolvable(endpoint)) {
            return endpoint;
        } else {
            throw new IllegalStateException("Unable to get REST endpoint");
        }
    }

    @Override
    public Endpoint getKeycloakEndpoint() {
        OpenShiftClient openShift = client.adapt(OpenShiftClient.class);
        Route route = openShift.routes().inNamespace(infraNamespace).withName("keycloak").get();
        Endpoint endpoint = new Endpoint(route.getSpec().getHost(), 443);
        log.info("Testing endpoint : " + endpoint);
        if (TestUtils.resolvable(endpoint)) {
            return endpoint;
        } else {
            log.info("Endpoint didn't resolve, falling back to service endpoint");
            return getEndpoint("standard-authservice", infraNamespace, "https");
        }
    }

    @Override
    public Endpoint getExternalEndpoint(String endpointName) {
        return getExternalEndpoint(endpointName, infraNamespace);
    }

    @Override
    public Endpoint getExternalEndpoint(String endpointName, String namespace) {
        OpenShiftClient openShift = client.adapt(OpenShiftClient.class);
        Route route = openShift.routes().inNamespace(namespace).withName(endpointName).get();
        if (route == null) {
            throw new IllegalStateException(String.format("Route '%s'/'%s' does not exist", namespace, endpointName));
        }
        Endpoint endpoint = new Endpoint(route.getSpec().getHost(), 443);
        log.info("Testing endpoint: {}", endpoint);
        if (TestUtils.resolvable(endpoint)) {
            return endpoint;
        } else {
            log.info("Endpoint didn't resolve, falling back to service endpoint");
            String port;
            switch (endpointName) {
                case "messaging":
                    port = "amqps";
                    break;
                case "console":
                    port = "https";
                    break;
                case "mqtt":
                    port = "secure-mqtt";
                    break;
                default:
                    throw new IllegalStateException(String.format("Endpoint '%s' doesn't exist.",
                            endpointName));
            }
            return getEndpoint(endpointName, namespace, port);
        }
    }

    @Override
    public void createExternalEndpoint(String name, String namespace, Service service, ServicePort targetPort) {
        Route route = new RouteBuilder()
                .editOrNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .editOrNewSpec()
                .editOrNewPort()
                .withNewTargetPort(targetPort.getPort())
                .endPort()
                .withNewTls()
                .withTermination("passthrough")
                .endTls()
                .withNewTo()
                .withName(service.getMetadata().getName())
                .withKind("Service")
                .endTo()
                .endSpec()
                .build();

        OpenShiftClient openShift = client.adapt(OpenShiftClient.class);
        openShift.routes().inNamespace(namespace).withName(name).createOrReplace(route);
    }

    @Override
    public void deleteExternalEndpoint(String namespace, String name) {
        OpenShiftClient openShift = client.adapt(OpenShiftClient.class);
        openShift.routes().inNamespace(namespace).withName(name).cascading(true).delete();
    }

    @Override
    public String getOlmNamespace() {
        return OLM_NAMESPACE;
    }

    @Override
    public List<Route> listRoutes(String namespace, Map<String, String> labels) {
        return client.adapt(OpenShiftClient.class).routes().inNamespace(namespace).withLabels(labels).list().getItems();
    }

    @Override
    public String getClusterExternalImageRegistry() {
        if (Kubernetes.isOpenShiftCompatible(OpenShiftVersion.OCP4)) {
            return getExternalEndpoint("default-route", "openshift-image-registry").getHost();
        } else {
            return "172.30.1.1:5000";
        }
    }

    @Override
    public String getClusterInternalImageRegistry() {
        if (Kubernetes.isOpenShiftCompatible(OpenShiftVersion.OCP4)) {
            return "image-registry.openshift-image-registry.svc:5000";
        } else {
            return "172.30.1.1:5000";
        }
    }
}
