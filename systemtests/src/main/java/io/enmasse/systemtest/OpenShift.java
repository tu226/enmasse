/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import org.slf4j.Logger;

import java.net.MalformedURLException;

/**
 * Handles interaction with openshift cluster
 */
public class OpenShift extends Kubernetes {
    private static Logger log = CustomLogger.getLogger();

    public OpenShift(Environment environment, String globalNamespace) {
        super(environment, new DefaultOpenShiftClient(new ConfigBuilder().withMasterUrl(environment.openShiftUrl())
                .withOauthToken(environment.openShiftToken())
                .withUsername(environment.openShiftUser()).build()), globalNamespace);
    }

    public Endpoint getRestEndpoint() throws MalformedURLException {
        OpenShiftClient openShift = client.adapt(OpenShiftClient.class);
        Endpoint endpoint;
        if (environment.registerApiServer()) {
            endpoint = new Endpoint(environment.openShiftUrl());
        } else {
            Route route = openShift.routes().inNamespace(globalNamespace).withName("restapi").get();
            endpoint = new Endpoint(route.getSpec().getHost(), 443);
        }

        if (TestUtils.resolvable(endpoint)) {
            return endpoint;
        } else {
            log.info("Endpoint didn't resolve, falling back to service endpoint");
            return getEndpoint("address-space-controller", "https");
        }
    }

    public Endpoint getKeycloakEndpoint() {
        OpenShiftClient openShift = client.adapt(OpenShiftClient.class);
        Route route = openShift.routes().inNamespace(globalNamespace).withName("keycloak").get();
        Endpoint endpoint = new Endpoint(route.getSpec().getHost(), 443);
        log.info("Testing endpoint : " + endpoint);
        if (TestUtils.resolvable(endpoint)) {
            return endpoint;
        } else {
            log.info("Endpoint didn't resolve, falling back to service endpoint");
            return getEndpoint("standard-authservice", "https");
        }
    }

    @Override
    public Endpoint getExternalEndpoint(String endpointName) {
        OpenShiftClient openShift = client.adapt(OpenShiftClient.class);
        Route route = openShift.routes().inNamespace(globalNamespace).withName(endpointName).get();
        Endpoint endpoint = new Endpoint(route.getSpec().getHost(), 443);
        log.info("Testing endpoint : " + endpoint);
        if (TestUtils.resolvable(endpoint)) {
            return endpoint;
        } else {
            log.info("Endpoint didn't resolve, falling back to service endpoint");
            String port;
            switch (endpointName) {
                case "messaging":
                    port = "amqps";
                    break;
                case "console":
                    port = "https";
                    break;
                case "mqtt":
                    port = "secure-mqtt";
                    break;
                default:
                    throw new IllegalStateException(String.format("Endpoint '%s' doesn't exist.",
                            endpointName));
            }
            return getEndpoint(endpointName, port);
        }
    }
}
