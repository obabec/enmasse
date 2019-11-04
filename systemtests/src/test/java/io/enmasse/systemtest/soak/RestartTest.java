/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.soak;

import io.enmasse.address.model.Address;
import io.enmasse.address.model.AddressSpace;
import io.enmasse.address.model.AddressSpaceBuilder;
import io.enmasse.systemtest.TestTag;
import io.enmasse.systemtest.UserCredentials;
import io.enmasse.systemtest.bases.isolated.ITestBaseIsolated;
import io.enmasse.systemtest.bases.soak.SoakTestBase;
import io.enmasse.systemtest.logs.CustomLogger;
import io.enmasse.systemtest.model.addressspace.AddressSpacePlans;
import io.enmasse.systemtest.model.addressspace.AddressSpaceType;
import io.enmasse.systemtest.utils.AddressUtils;
import io.enmasse.systemtest.utils.TestUtils;
import io.fabric8.kubernetes.api.model.Pod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Tag(TestTag.ISOLATED)
class RestartTest extends SoakTestBase implements ITestBaseIsolated {
    private static Logger LOGGER = CustomLogger.getLogger();
    private ScheduledExecutorService deleteService;

    @BeforeEach
    void setUp() {
        deleteService = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDownRestart() {
        if (deleteService != null) {
            deleteService.shutdownNow();
        }
    }

    @Test
    void testRandomDeletePods() throws Exception {

        UserCredentials user = new UserCredentials("test-user", "passsswooooord");
        AddressSpace standard = new AddressSpaceBuilder()
                .withNewMetadata()
                .withName("ttest-restart-standard")
                .withNamespace(KUBERNETES.getInfraNamespace())
                .endMetadata()
                .withNewSpec()
                .withType(AddressSpaceType.STANDARD.toString())
                .withPlan(AddressSpacePlans.STANDARD_UNLIMITED)
                .withNewAuthenticationService()
                .withName("standard-authservice")
                .endAuthenticationService()
                .endSpec()
                .build();
        AddressSpace brokered = new AddressSpaceBuilder()
                .withNewMetadata()
                .withName("test-restart-brokered")
                .withNamespace(KUBERNETES.getInfraNamespace())
                .endMetadata()
                .withNewSpec()
                .withType(AddressSpaceType.BROKERED.toString())
                .withPlan(AddressSpacePlans.BROKERED)
                .withNewAuthenticationService()
                .withName("standard-authservice")
                .endAuthenticationService()
                .endSpec()
                .build();
        ISOLATED_RESOURCES_MANAGER.createAddressSpaceList(standard, brokered);
        resourcesManager.createOrUpdateUser(brokered, user);
        resourcesManager.createOrUpdateUser(standard, user);

        List<Address> brokeredAddresses = AddressUtils.getAllBrokeredAddresses(brokered);
        List<Address> standardAddresses = AddressUtils.getAllStandardAddresses(standard);

        resourcesManager.setAddresses(brokeredAddresses.toArray(new Address[0]));
        resourcesManager.setAddresses(standardAddresses.toArray(new Address[0]));

        getClientUtils().assertCanConnect(brokered, user, brokeredAddresses, resourcesManager);
        getClientUtils().assertCanConnect(standard, user, standardAddresses, resourcesManager);

        //set up restart scheduler
        deleteService.scheduleAtFixedRate(() -> {
            LOGGER.info("............................................................");
            LOGGER.info("............................................................");
            LOGGER.info("..........Scheduler will pick pod and delete them...........");
            List<Pod> pods = KUBERNETES.listPods();
            int podNum = new Random(System.currentTimeMillis()).nextInt(pods.size() - 1);
            KUBERNETES.deletePod(environment.namespace(), pods.get(podNum).getMetadata().getName());
            LOGGER.info("............................................................");
            LOGGER.info("............................................................");
            LOGGER.info("............................................................");
        }, 5, 25, TimeUnit.SECONDS);

        runTestInLoop(60, () ->
                assertSystemWorks(brokered, standard, user, brokeredAddresses, standardAddresses));
    }

    @Test
    @Disabled("Due to issue #2127")
    public void testHAqdrouter() throws Exception {

        UserCredentials user = new UserCredentials("test-user", "passsswooooord");
        AddressSpace standard = new AddressSpaceBuilder()
                .withNewMetadata()
                .withName("test-ha-routers")
                .withNamespace(KUBERNETES.getInfraNamespace())
                .endMetadata()
                .withNewSpec()
                .withType(AddressSpaceType.STANDARD.toString())
                .withPlan(AddressSpacePlans.STANDARD_UNLIMITED)
                .withNewAuthenticationService()
                .withName("standard-authservice")
                .endAuthenticationService()
                .endSpec()
                .build();
        ISOLATED_RESOURCES_MANAGER.createAddressSpaceList(standard);
        resourcesManager.createOrUpdateUser(standard, user);

        List<Address> standardAddresses = AddressUtils.getAllStandardAddresses(standard);

        resourcesManager.setAddresses(standardAddresses.toArray(new Address[0]));

        getClientUtils().assertCanConnect(standard, user, standardAddresses, resourcesManager);

        //set up restart scheduler
        deleteService.scheduleAtFixedRate(() -> {
            LOGGER.info("............................................................");
            LOGGER.info("............................................................");
            LOGGER.info("...........Scheduler will delete one of qdrouter............");
            List<Pod> qdrouters = KUBERNETES.listPods().stream().filter(pod -> pod.getMetadata().getName().contains("qdrouter")).collect(Collectors.toList());
            Pod qdrouter = qdrouters.get(new Random(System.currentTimeMillis()).nextInt(qdrouters.size()) % qdrouters.size());
            KUBERNETES.deletePod(environment.namespace(), qdrouter.getMetadata().getName());
            LOGGER.info("............................................................");
            LOGGER.info("............................................................");
            LOGGER.info("............................................................");
        }, 5, 75, TimeUnit.SECONDS);

        runTestInLoop(30, () ->
                getClientUtils().assertCanConnect(standard, user, standardAddresses, resourcesManager));
    }

    private void assertSystemWorks(AddressSpace brokered, AddressSpace standard, UserCredentials existingUser,
                                   List<Address> brAddresses, List<Address> stAddresses) throws Exception {
        LOGGER.info("Check if system works");
        TestUtils.runUntilPass(60, () -> resourcesManager.getAddressSpace(brokered.getMetadata().getName()));
        TestUtils.runUntilPass(60, () -> resourcesManager.getAddressSpace(standard.getMetadata().getName()));
        TestUtils.runUntilPass(60, () -> resourcesManager.createOrUpdateUser(brokered, new UserCredentials("jenda", "cenda")));
        TestUtils.runUntilPass(60, () -> resourcesManager.createOrUpdateUser(standard, new UserCredentials("jura", "fura")));
        TestUtils.runUntilPass(60, () -> {
            getClientUtils().assertCanConnect(brokered, existingUser, brAddresses, resourcesManager);
            return true;
        });
        TestUtils.runUntilPass(60, () -> {
            getClientUtils().assertCanConnect(standard, existingUser, stAddresses, resourcesManager);
            return true;
        });
    }
}
