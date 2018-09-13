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

var log = require("./log.js").logger();
var util = require('util');
var events = require('events');
var rhea = require('rhea');
var artemis = require('./artemis.js');
var myevents = require('./events.js');
var myutils = require('./utils.js');

function BrokerController(event_sink) {
    events.EventEmitter.call(this);
    this.check_in_progress = false;
    this.post_event = event_sink || function (event) { log.debug('event: %j', event); };
    this.serial_sync = require('./utils.js').serialize(this._sync_broker_addresses.bind(this));
    this.addresses_synchronized = false;
    this.busy_count = 0;
    this.retrieve_count = 0;
    this.last_retrieval = undefined;
    this.excluded_types = undefined;
};

util.inherits(BrokerController, events.EventEmitter);

BrokerController.prototype.start_polling = function (poll_frequency) {
    this.polling = setInterval(this.check_broker_addresses.bind(this), poll_frequency || 5000);//poll broker stats every 5 secs by default
};

function type_filter(ignored) {
    return function (type) {
        return ignored.indexOf(type) >= 0;
    }
}

BrokerController.prototype.connect = function (options) {
    this.excluded_types = type_filter(['subscription']);
    var container = rhea.create_container();
    container.on('connection_open', this.on_connection_open.bind(this));
    return container.connect(options);
};

BrokerController.prototype.close = function () {
    if (this.polling) {
        clearInterval(this.polling);
        this.polling = undefined;
    }
    return this.broker.close();
};

BrokerController.prototype.on_connection_open = function (context) {
    this.broker = new artemis.Artemis(context.connection);
    this.id = context.connection.container_id;
    log.info('[%s] broker controller ready', this.id);
    this.check_broker_addresses();
    this.emit('ready');
};

BrokerController.prototype.set_connection = function (connection) {
    this.broker = new artemis.Artemis(connection);
    this.id = connection.container_id;
    log.info('[%s] broker controller ready', this.id);
};

BrokerController.prototype.addresses_defined = function (addresses) {
    this.addresses = addresses.reduce(function (map, a) { map[a.address] = a; return map; }, {});
    return this.check_broker_addresses();
};

BrokerController.prototype.sync_addresses = function (addresses) {
    this.addresses = addresses.reduce(function (map, a) { map[a.address] = a; return map; }, {});
    return this.serial_sync();
};

function total() {
    var result = 0;
    for (var i in arguments) {
        if (arguments[i]) result += arguments[i];
    }
    return result;
}

function transform_queue_stats(queue) {
    return {
        receivers: queue.consumers,
        senders: 0,/*add this later*/
        depth: queue.messages,
        messages_in: queue.enqueued,
        messages_out: total(queue.acknowledged, queue.expired, queue.killed),
        propagated: 100,
        shards: [queue],
        outcomes: {
            egress: {
                links: [],
                accepted: queue.acknowledged,
                unsettled: queue.delivered,
                rejected: queue.killed
            },
            ingress: {
                links: [],
                accepted: queue.enqueued
            }
        }
    };
}

function sum(field, list) {
    return util.isArray(list) ? list.map(function (o) { return o[field] || 0; }).reduce(function (a, b) { return a + b; }, 0) : 0;
}

function transform_topic_stats(topic) {
    return {
        receivers: topic.subscription_count,
        senders: 0,/*add this later*/
        depth: topic.enqueued,
        /*this isn't really correct; what we want is the total number of messages sent to the topic (independent of the number of subscribers)*/
        messages_in: sum('enqueued', topic.subscriptions),
        messages_out: sum('acknowledged', topic.subscriptions),
        propagated: 100,
        shards: [topic],
        outcomes: {
            egress: {
                links: [],
                accepted: sum('acknowledged', topic.subscriptions),
                unsettled: sum('delivered', topic.subscriptions),
                rejected: sum('killed', topic.subscriptions)
            },
            ingress: {
                links: [],
                accepted: topic.enqueued
            }
        }
    };
}

function transform_address_stats(address) {
    return address.type == 'queue' ? transform_queue_stats(address) : transform_topic_stats(address);
}

function transform_connection_stats(raw) {
    return {
        id: raw.connectionID,
        host: raw.clientAddress,
        container: 'not available',
        user: raw.sessions.length ? raw.sessions[0].principal : '',
        senders: [],
        receivers: []
    };
}

function transform_producer_stats(raw) {
    return {
        name: undefined,
        connection_id: raw.connectionID,
        address: raw.destination,
        deliveries: raw.msgSent,
        deliveryCount: raw.msgSent,
        lastUpdated: Date.now()
    };
}

function transform_consumer_stats(raw) {
    return {
        name: raw.consumerID,
        connection_id: raw.connectionID,
        address: raw.queueName,//mapped to address in later step
        deliveries: 0,//TODO: not yet available from broker
        lastUpdated: Date.now()
    };
}

function index_by(list, field) {
    return list.reduce(function (map, item) { map[item[field]] = item; return map; }, {});
}

function collect_by_connection(conns, links, name) {
    links.forEach(function (link) {
        var conn = conns[link.connection_id];
        if (conn) {
            conn[name].push(link);
        }
    });
}

function collect_by_address(addresses, links, name, count) {
    links.forEach(function (link) {
        var address = addresses[link.address];
        if (address) {
            address.outcomes[name].links.push(link);
            if (count) address[count] = address.outcomes[name].links.length;
        }
    });
}

function is_not_internal(conn) {
    return conn.user !== undefined && conn.user.match('^agent.[a-z0-9]+$') == null; //Can't get properties or anything else on which to base decision yet
}

BrokerController.prototype.retrieve_stats = function () {
    var self = this;
    if (this.broker !== undefined) {
        return Promise.all([
            this.broker.getAllAddressData(),
            this.broker.listConnectionsWithSessions(),
            this.broker.listProducers(),
            this.broker.listConsumers()]).then(function (results) {
                var address_stats = {};
                for (var name in results[0].index) {
                    address_stats[name] = transform_address_stats(results[0].index[name]);
                }
                var connection_stats = index_by(results[1].map(transform_connection_stats).filter(is_not_internal), 'id');
                var senders = results[2].map(transform_producer_stats);
                var receivers = results[3].map(transform_consumer_stats);
                receivers.forEach(function (r) {
                    var q = results[0].reverse_index[r.address];
                    if (q) {
                        r.address = q.address;
                    }
                });

                collect_by_connection(connection_stats, senders, 'senders');
                collect_by_connection(connection_stats, receivers, 'receivers');

                collect_by_address(address_stats, senders, 'ingress', 'senders');
                collect_by_address(address_stats, receivers, 'egress');

                for(var c in connection_stats) {
                    connection_stats[c].messages_in = connection_stats[c].senders.reduce(function (total, sender) { return total + sender.deliveries; }, 0);
                }

                self.retrieve_count++;
                var now = Date.now();
                if (self.last_retrieval === undefined) {
                    log.info('[%s] broker stats retrieved (%d)', self.id, self.retrieve_count);
                } else {
                    var interval = now - self.last_retrieval;
                    if (interval >= 60000) {
                        log.info('[%s] broker stats retrieved %d times in last %d secs', self.id, self.retrieve_count, Math.round(interval/1000));
                        self.retrieve_count = 0;
                    }
                }
                self.emit('address_stats_retrieved', address_stats);
                self.emit('connection_stats_retrieved', connection_stats);
            }).catch(function (error) {
                log.error('[%s] error retrieving stats: %s', self.id, error);
            });
    } else {
        log.info('Unable to retrieve stats, no broker object');
        return Promise.resolve();
    }
};

function same_address(a, b) {
    return b !== undefined && a.address === b.address && a.type === b.type;
}

function difference(a, b, equivalent) {
    var diff = {};
    for (var k in a) {
	if (!equivalent(a[k], b[k])) {
	    diff[k] = a[k];
	}
    }
    return diff;
}

function address_and_type(object) {
    return {address: object.address, type: object.type};
}

function values(map) {
    return Object.keys(map).map(function (key) { return map[key]; });
}

function exclude_subscriptions(type) {
    return type === 'subscription';
}

function excluded_addresses(address) {
    return address === 'DLQ' || address === 'ExpiryQueue' || address === 'activemq.notifications';
}

function is_temp_queue(a) {
    return a.type === 'queue' && a.temporary;
}

function is_durable_subscription(a) {
    return a.durable && !a.purgeOnNoConsumers;
}

/**
 * Translate from the address details we get back from artemis to the
 * structure used for the definition, for easier comparison.
 */
function translate(addresses_in, excluded_names, excluded_types) {
    var addresses_out = {};
    for (var name in addresses_in) {
        if (excluded_names && excluded_names(name)) continue;
        var a = addresses_in[name];
        if (excluded_types && excluded_types(a.type)) continue;
        if (is_temp_queue(a)) {
            log.debug('ignoring temp queue %s', a.name);
            continue;
        }
        if (a.name) {
            addresses_out[name] = {address:a.name, type: a.type};
        } else {
            log.warn('Skipping address with no name: %j', a);
        }
    }
    return addresses_out;
}

BrokerController.prototype.delete_address = function (a) {
    var self = this;
    if (a.type === 'queue') {
        log.info('[%s] Deleting queue "%s"...', self.id, a.address);
        return self.broker.destroyQueue(a.address).then(function () {
            log.info('[%s] Deleted queue "%s"', self.id, a.address);
            self.post_event(myevents.address_delete(a));
        }).catch(function (error) {
            log.error('[%s] Failed to delete queue %s: %s', self.id, a.address, error);
            return self.broker.deleteAddress(a.address).then(function () {
                log.info('[%s] Deleted anycast address %s', self.id, a.address);
            }).catch(function (error) {
                log.error('[%s] Failed to delete queue address %s: %s', self.id, a.address, error);
                self.post_event(myevents.address_failed_delete(a, error));
            });
        });
    } else if (a.type === 'topic') {
        log.info('[%s] Deleting topic "%s"...', self.id, a.address);
        return self.broker.deleteAddressAndBindings(a.address).then(function () {
            log.info('[%s] Deleted topic "%s"', self.id, a.address);
            self.post_event(myevents.address_delete(a));
        }).catch(function (error) {
            log.error('[%s] Failed to delete topic %s: %s', self.id, a.address, error);
            self.post_event(myevents.address_failed_delete(a, error));
        });
    } else if (a.type === 'subscription') {
        log.info('[%s] Deleting subscription "%s"...', self.id, a.address);
        return self.broker.destroyQueue(a.address).then(function () {
            log.info('[%s] Deleted subscription "%s"', self.id, a.address);
            self.post_event(myevents.address_delete(a));
        }).catch(function (error) {
            log.error('[%s] Failed to delete subscription %s: %s', self.id, a.address, error);
        });
    }
};

BrokerController.prototype.delete_address_and_settings = function (a) {
    var self = this;
    log.info('[%s] Deleting address-settings for "%s"...', self.id, a.address);
    return self.broker.removeAddressSettings(a.address).then(function() {
        log.info('[%s] Deleted address-settings for "%s"', self.id, a.address);
        return self.delete_address(a);
    }).catch(function (error) {
        log.error('[%s] Failed to delete address settings for "%s": %s', self.id, a.address, error);
        return self.delete_address(a);
    });
};

BrokerController.prototype.delete_addresses = function (addresses) {
    var self = this;
    return Promise.all(addresses.map(function (a) { return self.delete_address(a); }));
};

BrokerController.prototype.create_address = function (a) {
    var self = this;
    if (a.type === 'queue') {
        log.info('[%s] Creating queue "%s"...', self.id, a.address);
        return self.broker.createQueue(a.address).then(function () {
            log.info('[%s] Created queue "%s"', self.id, a.address);
            self.post_event(myevents.address_create(a));
        }).catch(function (error) {
            log.error('[%s] Failed to create queue "%s": %s', self.id, a.address, error);
            self.post_event(myevents.address_failed_create(a, error));
        });
    } else if (a.type === 'topic') {
        log.info('[%s] Creating topic "%s"', self.id, a.address);
        return self.broker.createAddress(a.address, {multicast:true}).then(function () {
            log.info('[%s] Created topic "%s"', self.id, a.address);
            self.post_event(myevents.address_create(a));
        }).catch(function (error) {
            log.error('[%s] Failed to create topic "%s": %s', self.id, a.address, error);
            self.post_event(myevents.address_failed_create(a, error));
        });
    } else if (a.type === 'subscription') {
        log.info('[%s] Creating subscription "%s" on "%s"...', self.id, a.address, a.topic);
        return self.broker.createSubscription(a.address, a.topic).then(function () {
            log.info('[%s] Created subscription "%s" on "%s"', self.id, a.address, a.topic);
            self.post_event(myevents.address_create(a));
        }).catch(function (error) {
            log.error('[%s] Failed to create subscription "%s" on "%s": %s', self.id, a.address, a.topic, error);
            self.post_event(myevents.address_failed_create(a, error));
        });
    }
};

BrokerController.prototype.create_address_and_settings = function (a, settings) {
    var self = this;
    log.info('[%s] Creating address-settings for "%s": %j...', self.id, a.address, settings);
    return self.broker.addAddressSettings(a.address, settings).then(function() {
        log.info('[%s] Created address-settings for "%s": %j', self.id, a.address, settings);
        return self.create_address(a);
    }).catch(function (error) {
        log.error('[%s] Failed to create address settings for "%s": %s', self.id, a.address, error);
        return self.create_address(a);
    });
};

BrokerController.prototype.create_addresses = function (addresses) {
    var self = this;
    return Promise.all(addresses.map(function (a) { return self.create_address(a); }));
};

BrokerController.prototype.check_broker_addresses = function () {
    if (this.broker !== undefined && this.addresses !== undefined) {
        if (!this.check_in_progress) {
            this.busy_count = 0;
            this.check_in_progress = true;
            var self = this;
            return this._sync_broker_addresses().then(function () {
                return self.retrieve_stats().then(function () {
                    self.check_in_progress = false;
                }).catch( function (error) {
                    log.error('[%s] error retrieving stats: %s', self.id, error);
                    self.check_in_progress = false;
                });
            }).catch (function (error) {
                log.error('[%s] error syncing addresses: %s', self.id, error);
                self.check_in_progress = false;
            });
        } else {
            if (++(this.busy_count) % 10 === 0) {
                log.info('Unable to check broker, check already in progress (%d)', this.busy_count);
            }
            return Promise.resolve();
        }
    } else {
        log.info('Unable to check broker (broker %s defined, addresses %s defined)', this.broker === undefined ? 'is not' : 'is', this.addresses === undefined ? 'is not' : 'is');
        return Promise.resolve();
    }
};

BrokerController.prototype._sync_addresses = function (addresses) {
    this.addresses = addresses.reduce(function (map, a) { map[a.address] = a; return map; }, {});
    return this._sync_broker_addresses();
};

BrokerController.prototype._set_sync_status = function (stale, missing) {
    var b = (stale === 0 && missing === 0);
    if (this.addresses_synchronized !== b) {
        if (b) {
            log.info('[%s] addresses are synchronized', this.id);
            this.emit('synchronized');
        } else {
            log.info('[%s] addresses synchronizing (%d to delete, %d to create)', this.id, stale, missing);
        }
    }
    this.addresses_synchronized = b;
}

BrokerController.prototype._sync_broker_addresses = function (retry) {
    var self = this;
    return this.broker.listAddresses().then(function (results) {
        var actual = translate(results, excluded_addresses, self.excluded_types);
        var stale = values(difference(actual, self.addresses, same_address));
        var missing = values(difference(self.addresses, actual, same_address));
        log.debug('[%s] checking addresses, desired=%j, actual=%j => delete %j and create %j', self.id, values(self.addresses).map(address_and_type), values(actual),
                  stale.map(address_and_type), missing.map(address_and_type));
        self._set_sync_status(stale.length, missing.length);
        return self.delete_addresses(stale).then(
            function () {
                return self.create_addresses(missing.filter(function (o) { return o.type !== 'subscription' })).then(function () {
                    return self.create_addresses(missing.filter(function (o) { return o.type === 'subscription' })).then(function () {
                        return retry ? true : self._sync_broker_addresses(true);
                    });
                });
            });
    }).catch(function (e) {
        log.error('[%s] failed to retrieve addresses: %s', self.id, e);
    });
};

BrokerController.prototype.destroy_connector = function (address) {
    var self = this;
    log.info('[%s] Deleting connector for "%s"...', self.id, address);
    return self.broker.destroyConnectorService(address).then(function() {
        log.info('[%s] Deleted connector for "%s"', self.id, address);
    }).catch(function (error) {
        log.error('[%s] Failed to delete connector for "%s": %s', self.id, address, error);
    });
};

BrokerController.prototype.create_connector = function (address) {
    var self = this;
    log.info('[%s] Creating connector for "%s"...', self.id, address);
    return self.broker.createConnectorService(address).then(function() {
        log.info('[%s] Created connector for "%s"', self.id, address);
    }).catch(function (error) {
        log.error('[%s] Failed to create connector for "%s": %s', self.id, address, error);
    });
};

BrokerController.prototype.destroy_connectors = function (addresses) {
    return Promise.all(addresses.map(this.destroy_connector.bind(this)));
};

BrokerController.prototype.create_connectors = function (addresses) {
    return Promise.all(addresses.map(this.create_connector.bind(this)));
};

function connectors_of_interest(name) {
    return name !== 'amqp-connector' && name !== 'router-connector';
}

BrokerController.prototype._ensure_connectors = function (desired) {
    var self = this;
    return this.broker.getConnectorServices().then(function (results) {
        var actual = results.filter(connectors_of_interest);
        actual.sort();

        var difference = myutils.changes(actual, desired, myutils.string_compare);
        if (difference) {
            log.info('[%s] %d connectors missing, %d to be removed', self.id, difference.added.length, difference.removed.length);
            return self.destroy_connectors(difference.removed).then(function () {
                return self.create_connectors(difference.added);
            });
        } else {
            log.info('[%s] all connectors exist', self.id);
        }
    }).catch(function (e) {
        log.error('[%s] failed to retrieve connectors: %s', self.id, e);
    });
};

BrokerController.prototype._sync_addresses_and_connectors = function () {
    var desired = Object.keys(this.addresses);
    desired.sort();
    return this._sync_broker_addresses().then(this._ensure_connectors.bind(this, desired));
}

module.exports.create_controller = function (connection, event_sink) {
    var rcg = connection.properties['qd.route-container-group'];
    var bc = new BrokerController(event_sink);
    if (rcg === 'sharded-topic') {
        //only control subscriptions
        bc.excluded_types = type_filter(['queue', 'topic']);
        log.info('excluding types %j controller for %s (%s)', bc.excluded_types, connection.container_id, rcg);
    }
    bc.set_connection(connection);
    return bc;
};

module.exports.create_agent = function (event_sink, polling_frequency) {
    var bc = new BrokerController(event_sink);
    bc.start_polling(polling_frequency);
    return bc;
};
