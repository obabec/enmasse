/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.systemtest.iot;

import static io.enmasse.systemtest.TestTag.sharedIot;
import static io.enmasse.systemtest.TestTag.smoke;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.enmasse.iot.model.v1.IoTConfig;
import io.enmasse.iot.model.v1.IoTConfigBuilder;
import io.enmasse.systemtest.Environment;
import io.enmasse.systemtest.Kubernetes;
import io.enmasse.systemtest.TimeoutBudget;
import io.enmasse.systemtest.cmdclients.KubeCMDClient;
import io.enmasse.systemtest.utils.TestUtils;

@Tag(sharedIot)
@Tag(smoke)
public class SimpleK8sDeployTest {

    private static final String NAMESPACE = Environment.getInstance().namespace();

    private static final String[] EXPECTED_DEPLOYMENTS = new String[] {
                    "iot-auth-service",
                    "iot-device-registry",
                    "iot-gc",
                    "iot-http-adapter",
                    "iot-mqtt-adapter",
                    "iot-tenant-service",
    };

    private static final Map<String,String> IOT_LABELS;

    static {
        IOT_LABELS= new HashMap<> ();
        IOT_LABELS.put("component", "iot");
    }

    private static File configTempFile;
    private Kubernetes client = Kubernetes.getInstance();

    @BeforeAll
    protected static void setup () throws Exception {
        Map<String, String> secrets = new HashMap<>();
        secrets.put("iot-auth-service-tls", "systemtests-iot-auth-service-tls");
        secrets.put("iot-tenant-service-tls", "systemtests-iot-tenant-service-tls");
        secrets.put("iot-device-registry-tls", "systemtests-iot-device-registry-tls");
        secrets.put("iot-http-adapter-tls", "systemtests-iot-http-adapter-tls");
        secrets.put("iot-mqtt-adapter-tls", "systemtests-iot-mqtt-adapter-tls");

        IoTConfig config = new IoTConfigBuilder()
                .withNewMetadata()
                .withName("default")
                .endMetadata()
                .withNewSpec()
                .withNewInterServiceCertificates()
                .withNewSecretCertificatesStrategy()
                .withCaSecretName("systemtests-iot-service-ca")
                .withServiceSecretNames(secrets)
                .endSecretCertificatesStrategy()
                .endInterServiceCertificates()
                .endSpec()
                .build();

        configTempFile = File.createTempFile("iot-config", "json");
        new ObjectMapper().writeValue(configTempFile, config);

        KubeCMDClient.createFromFile(NAMESPACE, Paths.get(configTempFile.toURI()));
    }

    @AfterAll
    protected static void cleanup() throws Exception {
        if(configTempFile!=null && configTempFile.exists()) {
            configTempFile.delete();
        }
        KubeCMDClient.deleteIoTConfig(NAMESPACE, "default");
    }

    @Test
    public void testDeploy() throws Exception {

        final TimeoutBudget budget = TimeoutBudget.ofDuration(Duration.ofMinutes(5));

        TestUtils.waitUntilCondition("IoT Config to deploy", () -> allDeploymentsPresent(), budget);
        TestUtils.waitForNReplicas(this.client , EXPECTED_DEPLOYMENTS.length, IOT_LABELS, budget);
    }

    private boolean allDeploymentsPresent () {
        final String[] deployments = this.client.listDeployments(IOT_LABELS).stream()
                .map(deployment -> deployment.getMetadata().getName())
                .toArray(String[]::new);
        Arrays.sort(deployments);
        return Arrays.equals(deployments, EXPECTED_DEPLOYMENTS);
    }


}