/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.platform;

import io.enmasse.systemtest.Endpoint;
import io.enmasse.systemtest.Environment;
import io.enmasse.systemtest.executor.Exec;
import io.enmasse.systemtest.logs.CustomLogger;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.utils.HttpClientUtils;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MicroK8s extends Kubernetes {
    private static Logger log = CustomLogger.getLogger();

    private static final String OLM_NAMESPACE = "operators";

    protected MicroK8s(Environment environment) {
        super(environment, () -> {
            Config config = new ConfigBuilder().build();
            OkHttpClient httpClient = HttpClientUtils.createHttpClient(config);
            // Workaround https://github.com/square/okhttp/issues/3146
            httpClient = httpClient.newBuilder()
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .connectTimeout(environment.getKubernetesApiConnectTimeout())
                    .writeTimeout(environment.getKubernetesApiWriteTimeout())
                    .readTimeout(environment.getKubernetesApiReadTimeout())
                    .build();
            return new DefaultKubernetesClient(httpClient, config);
        });
    }

    private static String runCommand(String... cmd) {
        try {
            Exec executor = new Exec(false);
            int returnCode = executor.exec(Arrays.asList(cmd), 10000);
            if (returnCode == 0) {
                return executor.getStdOut();
            } else {
                throw new RuntimeException(executor.getStdErr());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Endpoint getMasterEndpoint() {
        return new Endpoint(client.getMasterUrl());
    }

    @Override
    public Endpoint getRestEndpoint() {
        return new Endpoint(client.getMasterUrl());
    }

    @Override
    public Endpoint getKeycloakEndpoint() {
        return getExternalEndpoint("standard-authservice");
    }

    @Override
    public Endpoint getExternalEndpoint(String name) {
        return getExternalEndpoint(name, infraNamespace);
    }

    @Override
    public Endpoint getExternalEndpoint(String name, String namespace) {
        String externalName = name;
        if (!name.endsWith("-external")) {
            externalName += "-external";
        }
        Endpoint endpoint;
        List<Service> services = Kubernetes.getInstance().listServices(Map.of("app", "enmasse"));
        for (Service service : services) {
            if (service.getMetadata().getName().equals(externalName)) {
                endpoint = new Endpoint("localhost", service.getSpec().getPorts().get(1).getPort());
                log.info("MicroK8s external endpoint - " + endpoint.toString());
                return endpoint;
            }
        }
        log.error("Could not find MicroK8s external endpoint " + externalName);
        throw new NullPointerException();
    }

    @Override
    public void createExternalEndpoint(String name, String namespace, Service service, ServicePort targetPort) {
        if (!name.endsWith("-external")) {
            name += "-external";
        }
        ServiceBuilder builder = new ServiceBuilder()
                .editOrNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .editOrNewSpec()
                .withType("NodePort")
                .addToPorts(targetPort)
                .withSelector(service.getSpec().getSelector())
                .endSpec();

        client.services().inNamespace(namespace).withName(name).createOrReplace(builder.build());
    }

    @Override
    public void deleteExternalEndpoint(String namespace, String name) {
        if (!name.endsWith("-external")) {
            name += "-external";
        }
        client.services().inNamespace(name).withName(name).cascading(true).delete();
    }

    @Override
    public String getOlmNamespace() {
        return OLM_NAMESPACE;
    }

    @Override
    public String getHost() {
        return "localhost";
    }

    @Override
    public String getClusterExternalImageRegistry() {
        return "localhost:32000";
    }

    @Override
    public String getClusterInternalImageRegistry() {
        return "localhost:32000";
    }
}