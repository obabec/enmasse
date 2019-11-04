/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.enmasse.address.model.Address;
import io.enmasse.address.model.AddressBuilder;
import io.enmasse.address.model.AddressList;
import io.enmasse.address.model.AddressSpace;
import io.enmasse.address.model.AddressStatus;
import io.enmasse.address.model.AddressStatusForwarder;
import io.enmasse.address.model.BrokerState;
import io.enmasse.address.model.BrokerStatus;
import io.enmasse.systemtest.logs.CustomLogger;
import io.enmasse.systemtest.model.addressplan.DestinationPlan;
import io.enmasse.systemtest.platform.Kubernetes;
import io.enmasse.systemtest.time.SystemtestsOperation;
import io.enmasse.systemtest.time.TimeMeasuringSystem;
import io.enmasse.systemtest.time.TimeoutBudget;
import io.enmasse.systemtest.time.WaitPhase;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListMultiDeletable;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class AddressUtils {
    private static Logger LOGGER = CustomLogger.getLogger();

    public static List<Address> getAddresses(AddressSpace addressSpace) {
        return getAddresses(addressSpace.getMetadata().getNamespace(), addressSpace.getMetadata().getName());
    }

    private static List<Address> getAddresses(String namespace, String addressSpace) {
        return Kubernetes.getInstance().getAddressClient(namespace).list().getItems().stream()
                .filter(address -> getAddressSpaceNameFromAddress(address).equals(addressSpace)).collect(Collectors.toList());
    }

    private static String getAddressSpaceNameFromAddress(Address address) {
        return address.getMetadata().getName().split("\\.")[0];
    }

    public static JsonObject addressToJson(Address address) throws Exception {
        return new JsonObject(new ObjectMapper().writeValueAsString(address));
    }

    public static String addressToYaml(Address address) throws Exception {
        JsonNode jsonNodeTree = new ObjectMapper().readTree(addressToJson(address).toString());
        return new YAMLMapper().writeValueAsString(jsonNodeTree);
    }

    public static String generateAddressMetadataName(AddressSpace addressSpace, String address) {
        return String.format("%s.%s", addressSpace.getMetadata().getName(), sanitizeAddress(address));
    }

    public static String getQualifiedSubscriptionAddress(Address address) {
        return address.getSpec().getTopic() == null ? address.getSpec().getAddress() :
                address.getSpec().getTopic() + "::" + address.getSpec().getAddress();
    }

    private static String sanitizeAddress(String address) {
        return address != null ? address.toLowerCase().replaceAll("[^a-z0-9.\\-]", "") : null;
    }

    public static void delete(Address... destinations) {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.DELETE_ADDRESS);
        Arrays.stream(destinations).forEach(address -> Kubernetes.getInstance().getAddressClient(
                address.getMetadata().getNamespace()).withName(address.getMetadata().getName())
                .cascading(true).delete());
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void delete(AddressSpace addressSpace) {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.DELETE_ADDRESS);
        var client = Kubernetes.getInstance().getAddressClient(addressSpace.getMetadata().getNamespace());
        for (Address address : client.list().getItems()) {
            client.withName(address.getMetadata().getName()).cascading(true).delete();
            waitForAddressDeleted(address, new TimeoutBudget(5, TimeUnit.MINUTES));
        }
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void setAddresses(TimeoutBudget budget, boolean wait, Address... addresses) {
        LOGGER.info("Addresses {} will be created", new Object[]{addresses});
        String operationID = TimeMeasuringSystem.startOperation(addresses.length > 0 ?
                SystemtestsOperation.CREATE_ADDRESS : SystemtestsOperation.DELETE_ADDRESS);
        LOGGER.info("Remove addresses in every addresses's address space");
        waitForAddresses(operationID, budget, wait, addresses);
    }

    public static void appendAddresses(TimeoutBudget budget, boolean wait, Address... addresses) {
        LOGGER.info("Addresses {} will be appended", new Object[]{addresses});
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.APPEND_ADDRESS);
        waitForAddresses(operationID, budget, wait, addresses);
    }

    private static void waitForAddresses(String operationID, TimeoutBudget budget, Boolean wait, Address... addresses) {
        for (Address address : addresses) {
            address = Kubernetes.getInstance().getAddressClient(address.getMetadata().getNamespace()).create(address);
            LOGGER.info("Address {} created", address.getMetadata().getName());
        }
        if (wait) {
            waitForDestinationsReady(budget, addresses);
        }
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void replaceAddress(Address destination, boolean wait, TimeoutBudget timeoutBudget) throws Exception {
        LOGGER.info("Address {} will be replaced", destination);
        var client = Kubernetes.getInstance().getAddressClient(destination.getMetadata().getNamespace());
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.UPDATE_ADDRESS);
        client.createOrReplace(destination);
        Thread.sleep(10_000);
        if (wait) {
            waitForDestinationsReady(timeoutBudget, destination);
            waitForDestinationPlanApplied(timeoutBudget, destination);
        }
        TimeMeasuringSystem.stopOperation(operationID);
    }


    private static boolean isAddressReady(Address address) {
        return address.getStatus().isReady();
    }

    private static boolean isPlanSynced(Address address) {
        boolean isReady = false;
        Map<String, String> annotations = address.getMetadata().getAnnotations();
        if (annotations != null) {
            String appliedPlan = address.getStatus().getPlanStatus().getName();
            String actualPlan = address.getSpec().getPlan();
            isReady = actualPlan.equals(appliedPlan);
        }
        return isReady;
    }

    private static boolean areBrokersDrained(Address address) {
        boolean isReady = true;
        List<BrokerStatus> brokerStatuses = address.getStatus().getBrokerStatuses();
        for (BrokerStatus status : brokerStatuses) {
            if (BrokerState.Draining.equals(status.getState())) {
                isReady = false;
                break;
            }
        }
        return isReady;
    }

    private static boolean areForwardersReady(Address address) {
        return Optional.ofNullable(address)
                .map(Address::getStatus)
                .map(AddressStatus::getForwarders).stream()
                .flatMap(Collection::stream)
                .allMatch(AddressStatusForwarder::isReady);
    }

    private static FilterWatchListMultiDeletable<Address, AddressList, Boolean, Watch,
            Watcher<Address>> getAddressClient(Address... destinations) {
        List<String> namespaces = Stream.of(destinations)
                .map(address -> address.getMetadata().getNamespace())
                .distinct()
                .collect(Collectors.toList());
        if (namespaces.size() != 1) {
            return Kubernetes.getInstance().getAddressClient().inAnyNamespace();
        } else {
            return Kubernetes.getInstance().getAddressClient(namespaces.get(0));
        }
    }

    public static void waitForDestinationsReady(Address... destinations) {
        TimeoutBudget budget = new TimeoutBudget(10, TimeUnit.MINUTES);
        waitForDestinationsReady(budget, destinations);
    }

    public static void waitForDestinationsReady(TimeoutBudget budget, Address... destinations) {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.ADDRESS_WAIT_READY);
        waitForAddressesMatched(budget, destinations.length, getAddressClient(destinations),
                addressList -> checkAddressesMatching(addressList, AddressUtils::isAddressReady, destinations));
        TimeMeasuringSystem.stopOperation(operationID);
    }

    private static void waitForDestinationPlanApplied(TimeoutBudget budget, Address... destinations) {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.ADDRESS_WAIT_PLAN_CHANGE);
        waitForAddressesMatched(budget, destinations.length, getAddressClient(destinations),
                addressList -> checkAddressesMatching(addressList, AddressUtils::isPlanSynced, destinations));
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void waitForBrokersDrained(TimeoutBudget budget, Address... destinations) {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.ADDRESS_WAIT_BROKER_DRAINED);
        waitForAddressesMatched(budget, destinations.length, getAddressClient(destinations),
                addressList -> checkAddressesMatching(addressList, AddressUtils::areBrokersDrained, destinations));
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void waitForForwardersReady(TimeoutBudget budget, Address... destinations) {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.ADDRESS_WAIT_FORWARDERS);
        waitForAddressesMatched(budget, destinations.length, getAddressClient(destinations),
                addressList -> checkAddressesMatching(addressList, AddressUtils::areForwardersReady, destinations));
        TimeMeasuringSystem.stopOperation(operationID);
    }

    private static void waitForAddressesMatched(TimeoutBudget timeoutBudget,
                                                int totalDestinations,FilterWatchListMultiDeletable<Address,
            AddressList, Boolean, Watch, Watcher<Address>> addressClient, AddressListMatcher addressListMatcher) {
        TestUtils.waitUntilCondition(totalDestinations + " match", phase -> {
            try {
                List<Address> addressList = addressClient.list().getItems();
                Map<String, Address> notMatched = addressListMatcher.matchAddresses(addressList);
                notMatched.values().forEach(address ->
                        LOGGER.info("Waiting until address {} ready, message {}",
                                address.getMetadata().getName(), address.getStatus().getMessages()));
                if (!notMatched.isEmpty() && phase == WaitPhase.LAST_TRY) {
                    LOGGER.info(notMatched.size() + " out of " + totalDestinations +
                            " addresses are not matched: " + notMatched.values());
                }
                return notMatched.isEmpty();
            } catch (KubernetesClientException e) {
                if (phase == WaitPhase.LAST_TRY) {
                    LOGGER.error("Client can't read address resources", e);
                } else {
                    LOGGER.warn("Client can't read address resources");
                }
                return false;
            }
        }, timeoutBudget);
    }

    private static Map<String, Address> checkAddressesMatching(List<Address> addressList, Predicate<Address> predicate, Address... destinations) {
        Map<String, Address> notMatchingAddresses = new HashMap<>();
        for (Address destination : destinations) {
            Optional<Address> lookupAddressResult = addressList.stream()
                    .filter(addr -> addr.getMetadata().getName().contains(destination.getMetadata().getName()))
                    .findFirst();
            if (lookupAddressResult.isEmpty()) {
                notMatchingAddresses.put(destination.getSpec().getAddress(), null);
            } else if (!predicate.test(lookupAddressResult.get())) {
                notMatchingAddresses.put(destination.getSpec().getAddress(), lookupAddressResult.get());
            }
        }
        return notMatchingAddresses;
    }

    public static void waitForAddressDeleted(Address address, TimeoutBudget timeoutBudget) {
        Kubernetes kubernetes = Kubernetes.getInstance();

        TestUtils.waitUntilCondition(address + " match", phase -> {
            try {
                AddressList addressList = kubernetes.getAddressClient()
                        .inNamespace(address.getMetadata().getNamespace()).list();
                List<Address> addressesInSameAddrSpace = addressList.getItems().stream()
                        .filter(address1 -> Address.extractAddressSpace(address1)
                                .equals(Address.extractAddressSpace(address))).collect(Collectors.toList());
                return !addressesInSameAddrSpace.contains(address);
            } catch (KubernetesClientException e) {
                LOGGER.warn("Client can't read address resources");
                return false;
            }
        }, timeoutBudget);
    }

    public static String getTopicPrefix(boolean topicSwitch) {
        return topicSwitch ? "topic://" : "";
    }

    interface AddressListMatcher {
        Map<String, Address> matchAddresses(List<Address> addressList);
    }

    public static List<Address> getAllStandardAddresses(AddressSpace addressspace) {
        return Arrays.asList(
                new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(addressspace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(addressspace, "test-queue"))
                        .endMetadata()
                        .withNewSpec()
                        .withType("queue")
                        .withAddress("test-queue")
                        .withPlan(DestinationPlan.STANDARD_SMALL_QUEUE)
                        .endSpec()
                        .build(),

                new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(addressspace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(addressspace, "test-topic"))
                        .endMetadata()
                        .withNewSpec()
                        .withType("topic")
                        .withAddress("test-topic")
                        .withPlan(DestinationPlan.STANDARD_SMALL_TOPIC)
                        .endSpec()
                        .build(),

                new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(addressspace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(addressspace, "test-queue-sharded"))
                        .endMetadata()
                        .withNewSpec()
                        .withType("queue")
                        .withAddress("test-queue-sharded")
                        .withPlan(DestinationPlan.STANDARD_LARGE_QUEUE)
                        .endSpec()
                        .build(),

                new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(addressspace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(addressspace, "test-topic-sharded"))
                        .endMetadata()
                        .withNewSpec()
                        .withType("topic")
                        .withAddress("test-topic-sharded")
                        .withPlan(DestinationPlan.STANDARD_LARGE_TOPIC)
                        .endSpec()
                        .build(),

                new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(addressspace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(addressspace, "test-anycast"))
                        .endMetadata()
                        .withNewSpec()
                        .withType("anycast")
                        .withAddress("test-anycast")
                        .withPlan(DestinationPlan.STANDARD_SMALL_ANYCAST)
                        .endSpec()
                        .build(),

                new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(addressspace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(addressspace, "test-multicast"))
                        .endMetadata()
                        .withNewSpec()
                        .withType("multicast")
                        .withAddress("test-multicast")
                        .withPlan(DestinationPlan.STANDARD_SMALL_MULTICAST)
                        .endSpec()
                        .build());
    }

    public static List<Address> getAllBrokeredAddresses(AddressSpace addressspace) {
        return Arrays.asList(
                new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(addressspace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(addressspace, "test-queue"))
                        .endMetadata()
                        .withNewSpec()
                        .withType("queue")
                        .withAddress("test-queue")
                        .withPlan(DestinationPlan.BROKERED_QUEUE)
                        .endSpec()
                        .build(),

                new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(addressspace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(addressspace, "test-topic"))
                        .endMetadata()
                        .withNewSpec()
                        .withType("topic")
                        .withAddress("test-topic")
                        .withPlan(DestinationPlan.BROKERED_TOPIC)
                        .endSpec()
                        .build());
    }
    /**
     * Gets addresses wildcard.
     *
     * @param addressspace the addressspace
     * @return the addresses wildcard
     */
    public static List<Address> getAddressesWildcard(AddressSpace addressspace, String addressPlanQueue, String addressPlanTopic) {
        Address queue = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(addressspace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(addressspace, "queue/1234"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("queue/1234")
                .withPlan(addressPlanQueue)
                .endSpec()
                .build();

        Address queue2 = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(addressspace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(addressspace, "queue/ABCD"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("queue/ABCD")
                .withPlan(addressPlanQueue)
                .endSpec()
                .build();

        Address topic = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(addressspace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(addressspace, "topic/2345"))
                .endMetadata()
                .withNewSpec()
                .withType("topic")
                .withAddress("topic/2345")
                .withPlan(addressPlanTopic)
                .endSpec()
                .build();

        Address topic2 = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(addressspace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(addressspace, "topic/ABCD"))
                .endMetadata()
                .withNewSpec()
                .withType("topic")
                .withAddress("topic/ABCD")
                .withPlan(addressPlanTopic)
                .endSpec()
                .build();

        return Arrays.asList(queue, queue2, topic, topic2);
    }
    /**
     * Generate queue topic list array list.
     *
     * @param addressspace the addressspace
     * @param range        the range
     * @return the array list
     */
    public static ArrayList<Address> generateQueueTopicList(AddressSpace addressspace, IntStream range,
                                                            String addressPlanQueue, String addressPlanTopic) {
        ArrayList<Address> addresses = new ArrayList<>();
        range.forEach(i -> {
            if (i % 2 == 0) {
                addresses.add(new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(addressspace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(addressspace, String.format("topic-%s-%d", "via-web", i)))
                        .endMetadata()
                        .withNewSpec()
                        .withType("topic")
                        .withAddress(String.format("topic-%s-%d", "via-web", i))
                        .withPlan(addressPlanTopic)
                        .endSpec()
                        .build());
            } else {
                addresses.add(new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(addressspace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(addressspace, String.format("queue-%s-%d", "via-web", i)))
                        .endMetadata()
                        .withNewSpec()
                        .withType("queue")
                        .withAddress(String.format("queue-%s-%d", "via-web", i))
                        .withPlan(addressPlanQueue)
                        .endSpec()
                        .build());
            }
        });
        return addresses;
    }
}
