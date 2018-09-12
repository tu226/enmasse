/*
 * Copyright 2016 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
'use strict';

var http = require('http');
var util = require('util');
var events = require('events');
var kubernetes = require('./kubernetes.js');
var log = require('./log.js').logger();
var myutils = require('./utils.js');

function extract_address(object) {
    try {
        return JSON.parse(object.data['config.json']);
    } catch (e) {
        console.error('Failed to parse config.json for address: %s %j', e, object);
    }
}

function extract_spec(def) {
    if (def.spec === undefined) {
        console.error('no spec found on %j', def);
    }
    var o = myutils.merge(def.spec, {status:def.status});
    o.name = def.metadata ? def.metadata.name : def.address;
    if (def.metadata && def.metadata.annotations && def.metadata.annotations['enmasse.io/broker-id']) {
        o.allocated_to = def.metadata.annotations['enmasse.io/broker-id'];
    }
    return o;
}

function extract_address_field(object) {
    var def = extract_address(object);
    return def && def.spec ? def.spec.address : undefined;
}

function is_defined (addr) {
    return addr !== undefined;
}

function ready (addr) {
    return addr && addr.status && addr.status.phase !== 'Terminating' && addr.status.phase !== 'Pending';
}

function same_address_definition(a, b) {
    return a.address === b.address && a.type === b.type && a.allocated_to === b.allocated_to;
}

function same_address_status(a, b) {
    if (a === undefined) return b === undefined;
    return a.isReady === b.isReady && a.phase === b.phase && a.message === b.message;
}

function same_address_definition_and_status(a, b) {
    return same_address_definition(a, b) && same_address_status(a.status, b.status);
}

function address_compare(a, b) {
    return myutils.string_compare(a.address, b.address);
}

function configmap_compare(a, b) {
    return myutils.string_compare(a.metadata.name, b.metadata.name);
}

function by_address(a) {
    return a.address;
}

function description(list) {
    const max = 5;
    if (list.length > max) {
        return list.slice(0, max).map(by_address).join(', ') + ' and ' + (list.length - max) + ' more';
    } else {
        return JSON.stringify(list.map(by_address));
    }
}

function AddressSource(address_space, config) {
    this.address_space = address_space;
    this.config = config || {};
    var options = myutils.merge({selector: 'type=address-config,infraUuid=' + address_space.uuid}, this.config);
    events.EventEmitter.call(this);
    this.watcher = kubernetes.watch('configmaps', options);
    this.watcher.on('updated', this.updated.bind(this));
    this.readiness = {};
    this.last = {};
}

util.inherits(AddressSource, events.EventEmitter);

AddressSource.prototype.get_changes = function (name, addresses, unchanged) {
    var c = myutils.changes(this.last[name], addresses, address_compare, unchanged, description);
    this.last[name] = addresses;
    return c;
};

AddressSource.prototype.dispatch = function (name, addresses, description) {
    log.info('%s: %s', name, description);
    this.emit(name, addresses);
};

AddressSource.prototype.dispatch_if_changed = function (name, addresses, unchanged) {
    var changes = this.get_changes(name, addresses, unchanged);
    if (changes) {
        this.dispatch(name, addresses, changes.description);
    }
};

AddressSource.prototype.add_readiness_record = function (definition) {
    var record = this.readiness[definition.address];
    if (record === undefined) {
        record = {ready: false, address: definition.address, name: definition.name};
        this.readiness[definition.address] = record;
    }
};

AddressSource.prototype.delete_readiness_record = function (definition) {
    delete this.readiness[definition.address];
};

AddressSource.prototype.update_readiness = function (changes) {
    if (changes.added.length > 0) {
        changes.added.forEach(this.add_readiness_record.bind(this));
    }
    if (changes.removed.length > 0) {
        changes.removed.forEach(this.delete_readiness_record.bind(this));
    }
};

AddressSource.prototype.updated = function (objects) {
    log.debug('addresses updated: %j', objects);
    var addresses = objects.map(extract_address).filter(is_defined).map(extract_spec);
    var changes = this.get_changes('addresses_defined', addresses, same_address_definition_and_status);
    if (changes) {
        this.update_readiness(changes);
        this.dispatch('addresses_defined', addresses, changes.description);
        this.dispatch_if_changed('addresses_ready', objects.map(extract_address).filter(ready).map(extract_spec), same_address_definition);
    }
};

AddressSource.prototype.update_status = function (record, ready) {
    function update(configmap) {
        var def = JSON.parse(configmap.data['config.json']);
        if (def.status === undefined) {
            def.status = {};
        }
        if (def.status.isReady !== ready) {
            def.status.isReady = ready;
            def.status.phase = ready ? 'Active' : 'Pending';
            configmap.data['config.json'] = JSON.stringify(def);
            return configmap;
        } else {
            return undefined;
        }
    }
    return kubernetes.update('configmaps/' + record.name, update, this.config).then(function (result) {
        if (result === 200) {
            record.ready = ready;
            log.info('updated status for %s to %s: %s', record.address, record.ready, result);
        } else if (result === 304) {
            record.ready = ready;
            log.debug('no need to update status for %j: %s', record, result);
        } else {
            log.error('failed to update status for %j: %s', record, result);
        }
    }).catch(function (error) {
        log.error('failed to update status for %j: %j', record, error);
    });
};

AddressSource.prototype.check_status = function (address_stats) {
    var results = [];
    for (var address in this.readiness) {
        var record = this.readiness[address];
        var stats = address_stats[address];
        if (stats === undefined) {
            log.info('no stats supplied for %s (%s)', address, record.ready);
        } else {
            if (!record.ready) {
                if (stats.propagated === 100) {
                    log.info('%s is now ready', address);
                    results.push(this.update_status(record, true));
                }
            } else {
                if (stats.propagated !== 100) {
                    log.info('%s is no longer ready', address);
                    results.push(this.update_status(record, false));
                }
            }
        }
    }
    return Promise.all(results);
};

function get_configmap_name_for_address(address, addressspace) {
    return addressspace + "." + myutils.kubernetes_name(address);
}

AddressSource.prototype.create_address = function (definition) {
    var configmap_name = get_configmap_name_for_address(definition.address, this.address_space.name);
    var address = {
        apiVersion: 'enmasse.io/v1alpha1',
        kind: 'Address',
        metadata: {
            name: configmap_name,
            namespace: this.address_space.namespace,
            addressSpace: this.address_space.name
        },
        spec: {
            address: definition.address,
            type: definition.type,
            plan: definition.plan
        }
    };
    if (definition.type === 'subscription') {
        address.spec.topic = definition.topic;
    }
    var configmap = {
        apiVersion: 'v1',
        kind: 'ConfigMap',
        metadata: {
            name: configmap_name,
            labels: {
                type: 'address-config',
                infraUuid: this.address_space.uuid,
                infraType: 'any'
            },
            annotations: {
                addressSpace: this.address_space.name,
            },
        },
        data: {
            'config.json': JSON.stringify(address)
        }
    };
    return kubernetes.post('configmaps', configmap, this.config).then(function (result, error) {
        if (result >= 300) {
            log.error('failed to create config map for %j [%d %s]: %s', configmap, result, http.STATUS_CODES[result], error);
            return Promise.reject(new Error(util.format('Failed to created address %j: %d %s %s', definition, result, http.STATUS_CODES[result], error)));
        } else {
            return Promise.resolve();
        }
    });
};

AddressSource.prototype.delete_address = function (definition) {
    return kubernetes.delete_resource('configmaps/' + definition.name, this.config);
};

function extract_address_plan (object) {
    return JSON.parse(object.data.definition);
}

function extract_address_space_plan (object) {
    return JSON.parse(object.data.definition);
}

function display_order (plan_a, plan_b) {
    // explicitly ordered plans always come before those with undefined order
    var a = plan_a.displayOrder === undefined ? Number.MAX_VALUE : plan_a.displayOrder;
    var b = plan_b.displayOrder === undefined ? Number.MAX_VALUE : plan_b.displayOrder;
    return a - b;
}

function extract_plan_details (plan) {
    return {
        name: plan.metadata.name,
        displayName: plan.displayName || plan.metadata.name,
        shortDescription: plan.shortDescription,
        longDescription: plan.longDescription,
    };
}

AddressSource.prototype.get_address_types = function () {
    var address_space_plan_options = myutils.merge({selector: 'type=address-space-plan'}, this.config);
    var address_plan_options = myutils.merge({selector: 'type=address-plan'}, this.config);
    var address_space_plan_name = this.address_space.plan;
    return kubernetes.get('configmaps', address_space_plan_options).then(function (configmaps) {
            var address_space_plan = configmaps.items.map(extract_address_space_plan)
                .filter(function (plan) {
                    return plan.metadata.name === address_space_plan_name;
                })[0];
            return address_space_plan.addressPlans;
        }).then(function (supported_plans) {
            return kubernetes.get('configmaps', address_plan_options).then(function (configmaps) {
                //extract plans
                var plans = configmaps.items.map(extract_address_plan);
                // remove plans not part of address space plan
                plans = plans.filter(function (p) {
                    return supported_plans.includes(p.metadata.name)
                });
                plans.sort(display_order);
                //group by addressType
                var types = [];
                var by_type = plans.reduce(function (map, plan) {
                    var list = map[plan.addressType];
                    if (list === undefined) {
                        list = [];
                        map[plan.addressType] = list;
                        types.push(plan.addressType);
                    }
                    list.push(plan);
                    return map;
                }, {});
                var results = [];
                types.forEach(function (type) {
                    results.push({name:type, plans:by_type[type].map(extract_plan_details)});
                });
                return results;
            });
        });
};

module.exports = AddressSource;
