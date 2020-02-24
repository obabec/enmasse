/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

// Code generated by go generate; DO NOT EDIT.

package watchers

import (
	"fmt"
	tp "github.com/enmasseproject/enmasse/pkg/apis/admin/v1beta2"
	cp "github.com/enmasseproject/enmasse/pkg/client/clientset/versioned/typed/admin/v1beta2"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql/cache"
	"k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/watch"
	"k8s.io/client-go/rest"
	"log"
	"math/rand"
	"reflect"
	"time"
)

type AddressPlanWatcher struct {
	Namespace string
	cache.Cache
	ClientInterface cp.AdminV1beta2Interface
	watching        chan struct{}
	watchingStarted bool
	stopchan        chan struct{}
	stoppedchan     chan struct{}
	create          func(*tp.AddressPlan) interface{}
	update          func(*tp.AddressPlan, interface{}) bool
	restartCounter  int32
	resyncInterval  *time.Duration
}

func NewAddressPlanWatcher(c cache.Cache, resyncInterval *time.Duration, namespace string, options ...WatcherOption) (ResourceWatcher, error) {

	kw := &AddressPlanWatcher{
		Namespace:      namespace,
		Cache:          c,
		watching:       make(chan struct{}),
		stopchan:       make(chan struct{}),
		stoppedchan:    make(chan struct{}),
		resyncInterval: resyncInterval,
		create: func(v *tp.AddressPlan) interface{} {
			return v
		},
		update: func(v *tp.AddressPlan, e interface{}) bool {
			if !reflect.DeepEqual(v, e) {
				*e.(*tp.AddressPlan) = *v
				return true
			} else {
				return false
			}
		},
	}

	for _, option := range options {
		option(kw)
	}

	if kw.ClientInterface == nil {
		return nil, fmt.Errorf("Client must be configured using the AddressPlanWatcherConfig or AddressPlanWatcherClient")
	}
	return kw, nil
}

func AddressPlanWatcherFactory(create func(*tp.AddressPlan) interface{}, update func(*tp.AddressPlan, interface{}) bool) WatcherOption {
	return func(watcher ResourceWatcher) error {
		w := watcher.(*AddressPlanWatcher)
		w.create = create
		w.update = update
		return nil
	}
}

func AddressPlanWatcherConfig(config *rest.Config) WatcherOption {
	return func(watcher ResourceWatcher) error {
		w := watcher.(*AddressPlanWatcher)

		var cl interface{}
		cl, _ = cp.NewForConfig(config)

		client, ok := cl.(cp.AdminV1beta2Interface)
		if !ok {
			return fmt.Errorf("unexpected type %T", cl)
		}

		w.ClientInterface = client
		return nil
	}
}

// Used to inject the fake client set for testing purposes
func AddressPlanWatcherClient(client cp.AdminV1beta2Interface) WatcherOption {
	return func(watcher ResourceWatcher) error {
		w := watcher.(*AddressPlanWatcher)
		w.ClientInterface = client
		return nil
	}
}

func (kw *AddressPlanWatcher) Watch() error {
	go func() {
		defer close(kw.stoppedchan)
		defer func() {
			if !kw.watchingStarted {
				close(kw.watching)
			}
		}()
		resource := kw.ClientInterface.AddressPlans(kw.Namespace)
		log.Printf("AddressPlan - Watching")
		running := true
		for running {
			err := kw.doWatch(resource)
			if err != nil {
				log.Printf("AddressPlan - Restarting watch - %v", err)
				atomicInc(&kw.restartCounter)
			} else {
				running = false
			}
		}
		log.Printf("AddressPlan - Watching stopped")
	}()

	return nil
}

func (kw *AddressPlanWatcher) AwaitWatching() {
	<-kw.watching
}

func (kw *AddressPlanWatcher) Shutdown() {
	close(kw.stopchan)
	<-kw.stoppedchan
}

func (kw *AddressPlanWatcher) GetRestartCount() int32 {
	return atomicGet(&kw.restartCounter)
}

func (kw *AddressPlanWatcher) doWatch(resource cp.AddressPlanInterface) error {
	resourceList, err := resource.List(v1.ListOptions{})
	if err != nil {
		return err
	}

	keyCreator, err := kw.Cache.GetKeyCreator(cache.PrimaryObjectIndex)
	if err != nil {
		return err
	}
	curr := make(map[string]interface{}, 0)
	_, err = kw.Cache.Get(cache.PrimaryObjectIndex, "AddressPlan/", func(obj interface{}) (bool, bool, error) {
		gen, key, err := keyCreator(obj)
		if err != nil {
			return false, false, err
		} else if !gen {
			return false, false, fmt.Errorf("failed to generate key for existing object %+v", obj)
		}
		curr[key] = obj
		return false, true, nil
	})

	var added = 0
	var updated = 0
	var unchanged = 0
	for _, res := range resourceList.Items {
		copy := res.DeepCopy()
		kw.updateGroupVersionKind(copy)

		candidate := kw.create(copy)
		gen, key, err := keyCreator(candidate)
		if err != nil {
			return err
		} else if !gen {
			return fmt.Errorf("failed to generate key for new object %+v", copy)
		}
		if existing, ok := curr[key]; ok {
			err = kw.Cache.Update(func(target interface{}) (interface{}, error) {
				if kw.update(copy, target) {
					updated++
					return target, nil
				} else {
					unchanged++
					return nil, nil
				}
			}, existing)
			if err != nil {
				return err
			}
			delete(curr, key)
		} else {
			err = kw.Cache.Add(candidate)
			if err != nil {
				return err
			}
			added++
		}
	}

	// Now remove any stale
	for _, stale := range curr {
		err = kw.Cache.Delete(stale)
		if err != nil {
			return err
		}
	}
	var stale = len(curr)
	log.Printf("AddressPlan - Cache initialised population added %d, updated %d, unchanged %d, stale %d", added, updated, unchanged, stale)

	watchOptions := v1.ListOptions{
		ResourceVersion: resourceList.ResourceVersion,
	}
	if kw.resyncInterval != nil {
		ts := int64(kw.resyncInterval.Seconds() * (rand.Float64() + 1.0))
		watchOptions.TimeoutSeconds = &ts
	}
	resourceWatch, err := resource.Watch(watchOptions)
	if err != nil {
		return err
	}
	defer resourceWatch.Stop()

	if !kw.watchingStarted {
		close(kw.watching)
		kw.watchingStarted = true
	}

	ch := resourceWatch.ResultChan()
	for {
		select {
		case event, chok := <-ch:
			if !chok {
				return fmt.Errorf("watch ended due to channel error")
			} else if event.Type == watch.Error {
				return fmt.Errorf("watch ended in error")
			}

			var err error
			log.Printf("AddressPlan - Received event type %s", event.Type)
			res, ok := event.Object.(*tp.AddressPlan)
			if !ok {
				err = fmt.Errorf("Watch error - object of unexpected type, %T, received", event.Object)
			} else {
				copy := res.DeepCopy()
				kw.updateGroupVersionKind(copy)
				switch event.Type {
				case watch.Added:
					err = kw.Cache.Add(kw.create(copy))
				case watch.Modified:
					updatingKey := kw.create(copy)
					err = kw.Cache.Update(func(target interface{}) (interface{}, error) {
						if kw.update(copy, target) {
							return target, nil
						} else {
							return nil, nil
						}
					}, updatingKey)
				case watch.Deleted:
					err = kw.Cache.Delete(kw.create(copy))
				}
			}

			if err != nil {
				return err
			}
		case <-kw.stopchan:
			log.Printf("AddressPlan - Shutdown received")
			return nil
		}
	}
}

// KubernetesRBACAccessController relies on the GVK information to be set on objects.
// List provides GVK (https://github.com/kubernetes/kubernetes/pull/63972) but Watch does not not so we set it ourselves.
func (kw *AddressPlanWatcher) updateGroupVersionKind(o *tp.AddressPlan) {
	if o.TypeMeta.Kind == "" || o.TypeMeta.APIVersion == "" {
		o.TypeMeta.SetGroupVersionKind(tp.SchemeGroupVersion.WithKind("AddressPlan"))
	}
}
