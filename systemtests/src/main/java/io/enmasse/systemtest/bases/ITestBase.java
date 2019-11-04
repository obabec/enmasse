/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.bases;

import io.enmasse.systemtest.manager.ResourceManager;
import io.enmasse.systemtest.model.address.AddressType;
import io.enmasse.systemtest.model.addressspace.AddressSpaceType;
import io.enmasse.systemtest.platform.Kubernetes;
import io.enmasse.systemtest.utils.MessagingUtils;

public interface ITestBase {
    MessagingUtils MESSAGING_UTILS = new MessagingUtils();
    Kubernetes KUBERNETES = Kubernetes.getInstance();
    default MessagingUtils getClientUtils() {
        return MESSAGING_UTILS;
    }

    default AddressSpaceType getAddressSpaceType() {
        return null;
    }

    default String getDefaultPlan(AddressType addressType) {
        return null;
    }

    default String getDefaultAddressSpacePlan() {
        return null;
    }

    default String getDefaultAddrSpaceIdentifier() {
        return "default";
    }

    default ResourceManager getResourceManager() {
        return null;
    }

}
