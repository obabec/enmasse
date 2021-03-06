/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

import gql from "graphql-tag";

const DOWNLOAD_CERTIFICATE = gql`
  query messagingCertificateChain($as: ObjectMeta_v1_Input!) {
    messagingCertificateChain(input: $as)
  }
`;

const RETURN_AUTHENTICATION_SERVICES = gql`
  query addressspace_schema {
    addressSpaceSchema_v2 {
      metadata {
        name
      }
      spec {
        authenticationServices
      }
    }
  }
`;

const RETURN_WHOAMI = gql`
  query whoami {
    whoami {
      metadata {
        name
      }
      fullName
    }
  }
`;

const RETURN_FILTERED_AUTHENTICATION_SERVICES = gql`
  query filtered_addressspace_schema($t: AddressSpaceType = standard) {
    addressSpaceSchema_v2(addressSpaceType: $t) {
      metadata {
        name
      }
      spec {
        authenticationServices
      }
    }
  }
`;

export {
  DOWNLOAD_CERTIFICATE,
  RETURN_AUTHENTICATION_SERVICES,
  RETURN_WHOAMI,
  RETURN_FILTERED_AUTHENTICATION_SERVICES
};
