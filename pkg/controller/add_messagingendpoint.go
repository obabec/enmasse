/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package controller

import (
	"github.com/enmasseproject/enmasse/pkg/controller/messagingendpoint"
	"github.com/enmasseproject/enmasse/pkg/util"
)

func init() {

	// add ourselves to the list of controllers

	if util.IsModuleEnabled("MESSAGING_ENDPOINT") {
		AddToManagerFuncs = append(AddToManagerFuncs, messagingendpoint.Add)
	}
}
