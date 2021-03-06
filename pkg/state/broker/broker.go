/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package broker

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"time"

	"github.com/enmasseproject/enmasse/pkg/amqpcommand"
	. "github.com/enmasseproject/enmasse/pkg/state/common"
	. "github.com/enmasseproject/enmasse/pkg/state/errors"

	logf "sigs.k8s.io/controller-runtime/pkg/log"

	"golang.org/x/sync/errgroup"

	"pack.ag/amqp"
)

var log = logf.Log.WithName("broker")

const (
	brokerCommandAddress         = "activemq.management"
	brokerCommandResponseAddress = "activemq.management_broker_command_response"
	maxOrder                     = 2
)

func NewBrokerState(host Host, port int32, tlsConfig *tls.Config) *BrokerState {
	opts := make([]amqp.ConnOption, 0)
	opts = append(opts, amqp.ConnConnectTimeout(10*time.Second))
	opts = append(opts, amqp.ConnProperty("product", "controller-manager"))

	if tlsConfig != nil {
		opts = append(opts, amqp.ConnSASLExternal())
		opts = append(opts, amqp.ConnTLS(true))
		opts = append(opts, amqp.ConnTLSConfig(tlsConfig))
	}
	state := &BrokerState{
		host:        host,
		port:        port,
		initialized: false,
		entities:    make(map[BrokerEntityType]map[string]BrokerEntity, 0),
		commandClient: amqpcommand.NewCommandClient(fmt.Sprintf("amqps://%s:%d", host.Ip, port),
			brokerCommandAddress,
			brokerCommandResponseAddress,
			opts...),
	}
	state.commandClient.Start()
	state.reconnectCount = state.commandClient.ReconnectCount()
	return state
}

func NewTestBrokerState(host Host, port int32, client amqpcommand.Client) *BrokerState {
	return &BrokerState{
		host:          host,
		port:          port,
		commandClient: client,
		entities:      make(map[BrokerEntityType]map[string]BrokerEntity, 0),
	}
}

func (b *BrokerState) Initialize(nextResync time.Time) error {
	if b.reconnectCount != b.commandClient.ReconnectCount() {
		b.initialized = false
	}

	if b.initialized {
		return nil
	}

	b.nextResync = nextResync

	log.Info(fmt.Sprintf("[Broker %s] Initializing...", b.host))

	b.reconnectCount = b.commandClient.ReconnectCount()
	totalEntities := 0
	entityTypes := []BrokerEntityType{BrokerQueueEntity, BrokerAddressEntity, BrokerDivertEntity, BrokerAddressSettingEntity}
	for _, t := range entityTypes {
		list, err := b.readEntities(t)
		if err != nil {
			return err
		}
		b.entities[t] = list
		totalEntities += len(list)
	}
	log.Info(fmt.Sprintf("[Broker %s] Initialized controller state with %d entities", b.host, totalEntities))
	b.initialized = true
	return nil
}

func (b *BrokerState) Host() Host {
	return b.host
}

func (b *BrokerState) Port() int32 {
	return b.port
}

func (b *BrokerState) NextResync() time.Time {
	return b.nextResync
}

/**
 * Perform management request against this broker.
 */
func doRequest(client amqpcommand.Client, request *amqp.Message) (*amqp.Message, error) {
	// If by chance we got disconnected while waiting for the request
	response, err := client.RequestWithTimeout(request, 10*time.Second)
	return response, err
}

func (b *BrokerState) readEntities(t BrokerEntityType) (map[string]BrokerEntity, error) {
	switch t {
	case BrokerQueueEntity:
		message, err := newManagementMessage("broker", "getQueueNames", "")
		if err != nil {
			return nil, err
		}

		result, err := doRequest(b.commandClient, message)
		if err != nil {
			return nil, err
		}
		if !success(result) {
			return nil, fmt.Errorf("error reading queues: %+v", result.Value)
		}

		switch v := result.Value.(type) {
		case string:
			entities := make(map[string]BrokerEntity, 0)
			var list [][]string
			err := json.Unmarshal([]byte(result.Value.(string)), &list)
			if err != nil {
				return nil, err
			}
			for _, entry := range list {
				for _, name := range entry {
					entities[name] = &BrokerQueue{
						Name: name,
					}
				}
			}
			log.Info(fmt.Sprintf("[broker %s] Found queues: %+v", b.host, entities))
			return entities, nil
		default:
			return nil, fmt.Errorf("unexpected value with type %T", v)
		}
	case BrokerAddressEntity:
		message, err := newManagementMessage("broker", "getAddressNames", "")
		if err != nil {
			return nil, err
		}

		result, err := doRequest(b.commandClient, message)
		if err != nil {
			return nil, err
		}
		if !success(result) {
			return nil, fmt.Errorf("error reading addresses: %+v", result.Value)
		}

		switch v := result.Value.(type) {
		case string:
			entities := make(map[string]BrokerEntity, 0)
			var list [][]string
			err := json.Unmarshal([]byte(result.Value.(string)), &list)
			if err != nil {
				return nil, err
			}
			for _, entry := range list {
				for _, name := range entry {
					entities[name] = &BrokerAddress{
						Name: name,
					}
				}
			}
			log.Info(fmt.Sprintf("[broker %s] Found addresses: %+v", b.host, entities))
			return entities, nil
		default:
			return nil, fmt.Errorf("unexpected value with type %T", v)
		}
	case BrokerDivertEntity:
		message, err := newManagementMessage("broker", "getDivertNames", "")
		if err != nil {
			return nil, err
		}

		result, err := doRequest(b.commandClient, message)
		if err != nil {
			return nil, err
		}
		if !success(result) {
			return nil, fmt.Errorf("error reading diverts: %+v", result.Value)
		}

		switch v := result.Value.(type) {
		case string:
			entities := make(map[string]BrokerEntity, 0)
			var list [][]string
			err := json.Unmarshal([]byte(result.Value.(string)), &list)
			if err != nil {
				return nil, err
			}
			for _, entry := range list {
				for _, name := range entry {
					entities[name] = &BrokerDivert{
						Name: name,
					}
				}
			}
			log.Info(fmt.Sprintf("[broker %s] Found diverts: %+v", b.host, entities))
			return entities, nil
		default:
			return nil, fmt.Errorf("unexpected value with type %T", v)
		}
	case BrokerAddressSettingEntity:
		entities := make(map[string]BrokerEntity, 0)
		for name, _ := range b.entities[BrokerQueueEntity] {
			message, err := newManagementMessage("broker", "getAddressSettingsAsJSON", "", name)
			if err != nil {
				return nil, err
			}

			result, err := doRequest(b.commandClient, message)
			if err != nil {
				return nil, err
			}
			if !success(result) {
				return nil, fmt.Errorf("error reading address setting: %+v", result.Value)
			}

			switch v := result.Value.(type) {
			case string:
				var entry []string
				err := json.Unmarshal([]byte(v), &entry)
				if err != nil {
					return nil, err
				}

				for _, e := range entry {
					var setting BrokerAddressSetting
					err := json.Unmarshal([]byte(e), &setting)
					if err != nil {
						return nil, err
					}

					setting.Name = name
					entities[name] = &setting
				}
			default:
				return nil, fmt.Errorf("unexpected value with type %T", v)
			}
		}
		log.Info(fmt.Sprintf("[broker %s] Found address settings: %+v", b.host, entities))
		return entities, nil
	default:
		return nil, fmt.Errorf("Unsupported entity type %s", t)
	}
}

func (b *BrokerState) EnsureEntities(ctx context.Context, entities []BrokerEntity) error {
	if !b.initialized {
		return NotInitializedError
	}

	toCreate := make([]BrokerEntity, 0, len(entities))
	for _, entity := range entities {
		typeMap := b.entities[entity.Type()]
		existing, ok := typeMap[entity.GetName()]
		if ok {
			if !existing.Equals(entity) {
				log.Info(fmt.Sprintf("Changing from '%+v' to '%+v'\n", existing, entity))
				return fmt.Errorf("broker entity %s %s was updated - updates are not supported", entity.Type(), existing.GetName())
			}
		} else {
			toCreate = append(toCreate, entity)
		}

	}

	completed := make(chan BrokerEntity, len(toCreate))
	var err error
	for order := 0; order < maxOrder; order++ {
		g, _ := errgroup.WithContext(ctx)
		for _, entity := range toCreate {
			e := entity
			if e.Order() == order {
				if _, ok := b.entities[e.Type()][e.GetName()]; !ok {
					g.Go(func() error {
						err := e.Create(b.commandClient)
						if err != nil {
							return err
						}

						completed <- e
						return nil
					})
				}
			}
		}

		err = g.Wait()
		if err != nil {
			break
		}
	}

	close(completed)

	if isConnectionError(err) {
		b.Reset()
	}
	if err != nil {
		log.Info(fmt.Sprintf("[Broker %s] EnsureQueues error: %+v", b.host, err))
	}
	for entity := range completed {
		b.entities[entity.Type()][entity.GetName()] = entity
	}
	return err
}

func (b *BrokerState) DeleteEntities(ctx context.Context, entities []BrokerEntity) error {
	if !b.initialized {
		return NotInitializedError
	}
	completed := make(chan BrokerEntity, len(entities))
	var err error
	for order := maxOrder - 1; order >= 0; order-- {
		g, _ := errgroup.WithContext(ctx)
		for _, entity := range entities {
			e := entity
			if e.Order() == order {
				if _, ok := b.entities[e.Type()][e.GetName()]; ok {
					g.Go(func() error {
						err := e.Delete(b.commandClient)
						if err != nil {
							return err
						}

						completed <- e
						return nil
					})
				}
			}
		}

		err = g.Wait()
		if err != nil {
			break
		}
	}

	close(completed)
	if isConnectionError(err) {
		b.Reset()
	}
	if err != nil {
		log.Info(fmt.Sprintf("[Broker %s] DeleteEntities error: %+v", b.host, err))
	}
	for entity := range completed {
		delete(b.entities[entity.Type()], entity.GetName())
	}
	return err
}

func success(response *amqp.Message) bool {
	successProp, ok := response.ApplicationProperties["_AMQ_OperationSucceeded"]
	if !ok {
		return false
	}
	return successProp.(bool)
}

func newManagementMessage(resource string, operation string, attribute string, parameters ...interface{}) (*amqp.Message, error) {
	properties := make(map[string]interface{})
	properties["_AMQ_ResourceName"] = resource
	if operation != "" {
		properties["_AMQ_OperationName"] = operation
	}
	if attribute != "" {
		properties["_AMQ_Attribute"] = attribute
	}

	var value string
	if len(parameters) > 0 {
		encoded, err := json.Marshal(parameters)
		if err != nil {
			return nil, err
		}
		value = string(encoded)
	} else {
		value = "[]"
	}
	return &amqp.Message{
		Properties:            &amqp.MessageProperties{},
		ApplicationProperties: properties,
		Value:                 value,
	}, nil
}

/*
 * Reset broker state from broker (i.e. drop all internal state and rebuild from actual router state)
 */
func (b *BrokerState) Reset() {
	if b.commandClient != nil && b.initialized {
		log.Info(fmt.Sprintf("[Broker %s] Resetting connection", b.host))
		b.commandClient.Stop()
		b.initialized = false
		b.commandClient.Start()
	}
}

func isConnectionError(err error) bool {
	// TODO: Handle errors that are not strictly connection-related potentially with retries
	return err != nil
}

func (b *BrokerState) Shutdown() {
	if b.commandClient != nil {
		b.commandClient.Stop()
	}
}

func (b *BrokerQueue) Type() BrokerEntityType {
	return BrokerQueueEntity
}

func (b *BrokerQueue) GetName() string {
	return b.Name
}

func (b *BrokerQueue) Order() int {
	return 1
}

// Updates not allowed for queues: they are the same if they have the same type and name.
func (b *BrokerQueue) Equals(other BrokerEntity) bool {
	return b.Type() == other.Type() &&
		b.Name == other.GetName()
}

func (b *BrokerQueue) Create(client amqpcommand.Client) error {
	config, err := json.Marshal(b)
	if err != nil {
		return err
	}

	log.Info(fmt.Sprintf("[Broker %s] creating queue json: '%s'", client.Addr(), string(config)))

	message, err := newManagementMessage("broker", "createQueue", "", string(config))
	if err != nil {
		return err
	}
	log.Info(fmt.Sprintf("Creating queue %s on %s: %+v", b.Name, client.Addr(), message))
	response, err := doRequest(client, message)
	if err != nil {
		return err
	}
	if !success(response) {
		return fmt.Errorf("error creating queue %s: %+v", b.Name, response.Value)
	}
	log.Info(fmt.Sprintf("Queue %s created successfully on %s", b.Name, client.Addr()))
	return nil
}

func (b *BrokerQueue) Delete(client amqpcommand.Client) error {
	message, err := newManagementMessage("broker", "destroyQueue", "", b.Name, true)
	if err != nil {
		return err
	}

	log.Info(fmt.Sprintf("Destroying queue %s on %s", b.Name, client.Addr()))

	response, err := doRequest(client, message)
	if err != nil {
		return err
	}

	if !success(response) {
		return fmt.Errorf("error deleting queue %s: %+v", b.Name, response.Value)
	}

	log.Info(fmt.Sprintf("Queue %s destroyed successfully on %s", b.Name, client.Addr()))
	return nil
}

func (b *BrokerAddress) Type() BrokerEntityType {
	return BrokerAddressEntity
}

func (b *BrokerAddress) GetName() string {
	return b.Name
}

func (b *BrokerAddress) Order() int {
	return 0
}

// Updates not allowed for addresses: they are the same if they have the same type and name.
func (b *BrokerAddress) Equals(other BrokerEntity) bool {
	return b.Type() == other.Type() &&
		b.Name == other.GetName()
}

func (b *BrokerAddress) Create(client amqpcommand.Client) error {
	log.Info(fmt.Sprintf("[Broker %s] creating address: '%s'", client.Addr(), b.Name))

	message, err := newManagementMessage("broker", "createAddress", "", b.Name, b.RoutingType)
	if err != nil {
		return err
	}
	log.Info(fmt.Sprintf("Creating address %s on %s: %+v", b.Name, client.Addr(), message))
	response, err := doRequest(client, message)
	if err != nil {
		return err
	}
	if !success(response) {
		return fmt.Errorf("error creating address %s: %+v", b.Name, response.Value)
	}
	log.Info(fmt.Sprintf("Address %s created successfully on %s", b.Name, client.Addr()))
	return nil
}

func (b *BrokerAddress) Delete(client amqpcommand.Client) error {
	message, err := newManagementMessage("broker", "deleteAddress", "", b.Name, true)
	if err != nil {
		return err
	}

	log.Info(fmt.Sprintf("Deleting address %s on %s", b.Name, client.Addr()))

	response, err := doRequest(client, message)
	if err != nil {
		return err
	}

	if !success(response) {
		return fmt.Errorf("error deleting address %s: %+v", b.Name, response.Value)
	}

	log.Info(fmt.Sprintf("Address %s deleted successfully on %s", b.Name, client.Addr()))
	return nil
}

/**
 * Broker Diverts
 */
func (b *BrokerDivert) Type() BrokerEntityType {
	return BrokerDivertEntity
}

func (b *BrokerDivert) GetName() string {
	return b.Name
}

func (b *BrokerDivert) Order() int {
	return 0
}

// Updates not allowed for addresses: they are the same if they have the same type and name.
func (b *BrokerDivert) Equals(other BrokerEntity) bool {
	return b.Type() == other.Type() &&
		b.Name == other.GetName()
}

func (b *BrokerDivert) Create(client amqpcommand.Client) error {
	log.Info(fmt.Sprintf("[Broker %s] creating divert: '%s'", client.Addr(), b.Name))

	message, err := newManagementMessage("broker", "createDivert", "", b.Name, b.RoutingName, b.Address, b.ForwardingAddress, b.Exclusive, b.FilterString, nil)
	if err != nil {
		return err
	}
	log.Info(fmt.Sprintf("Creating divert %s on %s: %+v", b.Name, client.Addr(), message))
	response, err := doRequest(client, message)
	if err != nil {
		return err
	}
	if !success(response) {
		return fmt.Errorf("error creating divert %s: %+v", b.Name, response.Value)
	}
	log.Info(fmt.Sprintf("Divert %s created successfully on %s", b.Name, client.Addr()))
	return nil
}

func (b *BrokerDivert) Delete(client amqpcommand.Client) error {
	message, err := newManagementMessage("broker", "destroyDivert", "", b.Name)
	if err != nil {
		return err
	}

	log.Info(fmt.Sprintf("Destroying divert %s on %s", b.Name, client.Addr()))

	response, err := doRequest(client, message)
	if err != nil {
		return err
	}

	if !success(response) {
		return fmt.Errorf("error destroying divert %s: %+v", b.Name, response.Value)
	}

	log.Info(fmt.Sprintf("Divert %s destroyed successfully on %s", b.Name, client.Addr()))
	return nil
}

/**
 * Broker address settings
 */
func (b *BrokerAddressSetting) Type() BrokerEntityType {
	return BrokerAddressSettingEntity
}

func (b *BrokerAddressSetting) GetName() string {
	return b.Name
}

func (b *BrokerAddressSetting) Order() int {
	return 0
}

func (b *BrokerAddressSetting) Equals(e BrokerEntity) bool {
	if b.Type() != e.Type() {
		return false
	}
	other := e.(*BrokerAddressSetting)
	return b.Name == other.GetName()
	// TODO: Compare more fields when we support updates
}

func (b *BrokerAddressSetting) Create(client amqpcommand.Client) error {
	log.Info(fmt.Sprintf("[Broker %s] creating address setting: '%s'", client.Addr(), b.Name))

	message, err := newManagementMessage("broker", "addAddressSettings", "",
		b.Name,
		b.DeadLetterAddress,
		b.ExpiryAddress,
		b.ExpiryDelay,
		b.LastValueQueue,
		b.DeliveryAttempts,
		b.MaxSizeBytes,
		b.PageSizeBytes,
		b.PageMaxCacheSize,
		b.RedeliveryDelay,
		b.RedeliveryMultiplier,
		b.MaxRedeliveryDelay,
		b.RedistributionDelay,
		b.SendToDLAOnNoRoute,
		b.AddressFullMessagePolicy,
		b.SlowConsumerThreshold,
		b.SlowConsumerCheckPeriod,
		b.SlowConsumerPolicy,
		b.AutoCreateJmsQueues,
		b.AutoDeleteJmsQueues,
		b.AutoCreateJmsTopics,
		b.AutoDeleteJmsTopics,
		b.AutoCreateQueues,
		b.AutoDeleteQueues,
		b.AutoCreateAddresses,
		b.AutoDeleteAddresses)

	if err != nil {
		return err
	}
	log.Info(fmt.Sprintf("Creating address setting %s on %s: %+v", b.Name, client.Addr(), message))
	response, err := doRequest(client, message)
	if err != nil {
		return err
	}
	if !success(response) {
		return fmt.Errorf("error creating address setting %s: %+v", b.Name, response.Value)
	}
	log.Info(fmt.Sprintf("Address setting %s created successfully on %s", b.Name, client.Addr()))
	return nil
}

func (b *BrokerAddressSetting) Delete(client amqpcommand.Client) error {
	message, err := newManagementMessage("broker", "removeAddressSettings", "", b.Name)
	if err != nil {
		return err
	}

	log.Info(fmt.Sprintf("Removing address setting %s on %s", b.Name, client.Addr()))

	response, err := doRequest(client, message)
	if err != nil {
		return err
	}

	if !success(response) {
		return fmt.Errorf("error removing address setting %s: %+v", b.Name, response.Value)
	}

	log.Info(fmt.Sprintf("Address setting %s destroyed successfully on %s", b.Name, client.Addr()))
	return nil
}
