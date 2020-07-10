/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.platform.cluster;

public enum ClusterType {
    MINIKUBE,
    OPENSHIFT,
    CRC,
    KUBERNETES,
    WHATEVER,
}
