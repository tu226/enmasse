/*
 * Copyright 2016-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.systemtest;

import io.enmasse.systemtest.apiclients.AddressApiClient;
import io.enmasse.systemtest.apiclients.OSBApiClient;
import io.enmasse.systemtest.resources.*;
import io.enmasse.systemtest.timemeasuring.Operation;
import io.enmasse.systemtest.timemeasuring.TimeMeasuringSystem;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.DeploymentBuilder;
import io.vertx.core.VertxException;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TestUtils {
    private static Logger log = CustomLogger.getLogger();
    private static Set<String> validDestinationTypes = new HashSet<>(Arrays.asList("queue", "topic", "anycast", "multicast", "subscription"));

    /**
     * scale up/down specific Destination (type: StatefulSet) in address space
     */
    public static void setReplicas(Kubernetes kubernetes, AddressSpace addressSpace, Destination destination, int numReplicas, TimeoutBudget budget, long checkInterval) throws InterruptedException {
        kubernetes.setStatefulSetReplicas(destination.getDeployment(), numReplicas);
        waitForNBrokerReplicas(kubernetes, addressSpace.getNamespace(), numReplicas, true, destination, budget, checkInterval);
    }

    /**
     * scale up/down specific Destination (type: StatefulSet) in address space WITHOUT WAIT
     */
    public static void setReplicas(Kubernetes kubernetes, AddressSpace addressSpace, Destination destination, int numReplicas) throws InterruptedException {
        kubernetes.setStatefulSetReplicas(destination.getDeployment(), numReplicas);
    }

    public static void setReplicas(Kubernetes kubernetes, AddressSpace addressSpace, Destination destination, int numReplicas, TimeoutBudget budget) throws InterruptedException {
        setReplicas(kubernetes, addressSpace, destination, numReplicas, budget, 5000);
    }

    /**
     * scale up/down specific pod (type: Deployment) in address space
     */
    public static void setReplicas(Kubernetes kubernetes, String infraUuid, String deployment, int numReplicas, TimeoutBudget budget) throws InterruptedException {
        kubernetes.setDeploymentReplicas(deployment, numReplicas);
        Map<String, String> labels = new HashMap<>();
        labels.put("name", deployment);
        if (infraUuid != null) {
            labels.put("infraUuid", infraUuid);
        }
        waitForNReplicas(
                kubernetes,
                numReplicas,
                labels,
                budget);
    }

    public static void waitForNReplicas(Kubernetes kubernetes, int expectedReplicas, Map<String, String> labelSelector, TimeoutBudget budget) throws InterruptedException {
        waitForNReplicas(kubernetes, expectedReplicas, labelSelector, Collections.emptyMap(), budget);
    }

    /**
     * wait for expected count of Destination replicas in address space
     */
    public static void waitForNBrokerReplicas(Kubernetes kubernetes, String infraUuid, int expectedReplicas, boolean readyRequired,
                                              Destination destination, TimeoutBudget budget, long checkInterval) throws InterruptedException {
        Map<String, String> labels = new HashMap<>();
        labels.put("role", "broker");
        labels.put("infraUuid", infraUuid);
        waitForNReplicas(kubernetes,
                expectedReplicas,
                readyRequired,
                labels,
                Collections.singletonMap("cluster_id", destination.getDeployment()),
                budget,
                checkInterval);
    }

    public static void waitForNBrokerReplicas(Kubernetes kubernetes, String infraUuid, int expectedReplicas, Destination destination, TimeoutBudget budget) throws InterruptedException {
        waitForNBrokerReplicas(kubernetes, infraUuid, expectedReplicas, true, destination, budget, 5000);
    }


    /**
     * Wait for expected count of replicas
     *
     * @param kubernetes         client for manipulation with kubernetes cluster
     * @param expectedReplicas   count of expected replicas
     * @param labelSelector      labels on scaled pod
     * @param annotationSelector annotations on sclaed pod
     * @param budget             timeout budget - throws Exception when timeout is reached
     * @throws InterruptedException
     */
    public static void waitForNReplicas(Kubernetes kubernetes, int expectedReplicas, boolean readyRequired,
                                        Map<String, String> labelSelector, Map<String, String> annotationSelector, TimeoutBudget budget, long checkInterval) throws InterruptedException {
        boolean done = false;
        int actualReplicas = 0;
        List<Pod> pods;
        do {
            if (annotationSelector.isEmpty()) {
                pods = kubernetes.listPods(labelSelector);
            } else {
                pods = kubernetes.listPods(labelSelector, annotationSelector);
            }
            if (!readyRequired) {
                actualReplicas = pods.size();
            } else {
                actualReplicas = numReady(pods);
            }
            log.info("Have {} out of {} replicas. Expecting={}, ReadyRequired={}",
                    actualReplicas, pods.size(), expectedReplicas, readyRequired);
            if (actualReplicas != expectedReplicas) {
                Thread.sleep(checkInterval);
            } else if (!readyRequired || actualReplicas == pods.size()) {
                done = true;
            }
        } while (!budget.timeoutExpired() && !done);

        if (!done) {
            String message = String.format("Only '%s' out of '%s' in state 'Running' before timeout %s", actualReplicas, expectedReplicas,
                    String.join(",", pods.stream().map(pod -> pod.getMetadata().getName()).collect(Collectors.toList())));
            throw new RuntimeException(message);
        }
    }

    public static void waitForNReplicas(Kubernetes kubernetes, int expectedReplicas, Map<String, String> labelSelector, Map<String, String> annotationSelector, TimeoutBudget budget, long checkInterval) throws InterruptedException {
        waitForNReplicas(kubernetes, expectedReplicas, true, labelSelector, annotationSelector, budget, checkInterval);
    }

    public static void waitForNReplicas(Kubernetes kubernetes, int expectedReplicas, Map<String, String> labelSelector, Map<String, String> annotationSelector, TimeoutBudget budget) throws InterruptedException {
        waitForNReplicas(kubernetes, expectedReplicas, labelSelector, annotationSelector, budget, 5000);
    }

    /**
     * Check ready status of all pods in list
     *
     * @param pods list of pods
     * @return
     */
    private static int numReady(List<Pod> pods) {
        int numReady = 0;
        for (Pod pod : pods) {
            if ("Running".equals(pod.getStatus().getPhase())) {
                numReady++;
            } else {
                log.info("POD " + pod.getMetadata().getName() + " in status : " + pod.getStatus().getPhase());
            }
        }
        return numReady;
    }

    /**
     * Wait for expected count of pods within AddressSpace
     *
     * @param client       client for manipulation with kubernetes cluster
     * @param addressSpace
     * @param numExpected  count of expected pods
     * @param budget       timeout budget - this method throws Exception when timeout is reached
     * @throws InterruptedException
     */
    public static void waitForExpectedPods(Kubernetes client, AddressSpace addressSpace, int numExpected, TimeoutBudget budget) throws InterruptedException {
        List<Pod> pods = listRunningPods(client, addressSpace);
        while (budget.timeLeft() >= 0 && pods.size() != numExpected) {
            Thread.sleep(2000);
            pods = listRunningPods(client, addressSpace);
        }
        if (pods.size() != numExpected) {
            throw new IllegalStateException("Unable to find " + numExpected + " pods. Found : " + printPods(pods));
        }
    }

    /**
     * Print name of all pods in list
     *
     * @param pods list of pods that should be printed
     * @return
     */
    public static String printPods(List<Pod> pods) {
        return pods.stream()
                .map(pod -> "{" + pod.getMetadata().getName() + ", " + pod.getStatus().getPhase() + "}")
                .collect(Collectors.joining(","));
    }

    /**
     * Get list of all running pods from specific AddressSpace
     *
     * @param kubernetes   client for manipulation with kubernetes cluster
     * @param addressSpace
     * @return
     */
    public static List<Pod> listRunningPods(Kubernetes kubernetes, AddressSpace addressSpace) {
        return kubernetes.listPods(Collections.singletonMap("infraUuid", addressSpace.getInfraUuid())).stream()
                .filter(pod -> pod.getStatus().getPhase().equals("Running")
                        && !pod.getMetadata().getName().startsWith(SystemtestsOpenshiftApp.MESSAGING_CLIENTS.toString()))
                .collect(Collectors.toList());
    }

    /**
     * Delete requested destinations(Addresses) from AddressSpace
     *
     * @param apiClient    client for http requests on api server
     * @param addressSpace from this AddressSpace will be removed required destinations
     * @param destinations destinations requested to be removed
     * @throws Exception
     */
    public static void delete(AddressApiClient apiClient, AddressSpace addressSpace, Destination... destinations) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.DELETE_ADDRESS);
        apiClient.deleteAddresses(addressSpace, destinations);
        TimeMeasuringSystem.stopOperation(operationID);
    }

    /**
     * Deploy one or more destinations into requested AddressSpace
     *
     * @param apiClient    client for http requests on api server
     * @param kubernetes   client for manipulation with kubernetes cluster
     * @param budget       timeout for deploy
     * @param addressSpace AddressSpace for deploy destinations
     * @param destinations
     * @throws Exception
     */
    public static void setAddresses(AddressApiClient apiClient, Kubernetes kubernetes, TimeoutBudget budget, AddressSpace addressSpace, boolean wait, int expectedCode, Destination... destinations) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(destinations.length > 0 ? Operation.CREATE_ADDRESS : Operation.DELETE_ADDRESS);
        apiClient.setAddresses(addressSpace, expectedCode, destinations);
        if (wait) {
            JsonObject addrSpaceObj = apiClient.getAddressSpace(addressSpace.getName());
            if (getAddressSpaceType(addrSpaceObj).equals("standard")) {
                if (destinations.length == 0) {
                    waitForExpectedPods(kubernetes, addressSpace, kubernetes.getExpectedPods(addressSpace.getPlan()), budget);
                }
            }
            waitForDestinationsReady(apiClient, addressSpace, budget, destinations);
        }
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void setAddresses(AddressApiClient apiClient, Kubernetes kubernetes, TimeoutBudget budget, AddressSpace addressSpace, boolean wait, Destination... destinations) throws Exception {
        setAddresses(apiClient, kubernetes, budget, addressSpace, wait, HttpURLConnection.HTTP_CREATED, destinations);
    }

    public static void appendAddresses(AddressApiClient apiClient, Kubernetes kubernetes, TimeoutBudget budget, AddressSpace addressSpace, boolean wait, Destination... destinations) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.APPEND_ADDRESS);
        apiClient.appendAddresses(addressSpace, destinations);
        if (wait) {
            JsonObject addrSpaceObj = apiClient.getAddressSpace(addressSpace.getName());
            if (getAddressSpaceType(addrSpaceObj).equals("standard")) {
                if (destinations.length == 0) {
                    waitForExpectedPods(kubernetes, addressSpace, kubernetes.getExpectedPods(addressSpace.getPlan()), budget);
                }
            }
            waitForDestinationsReady(apiClient, addressSpace, budget, destinations);
        }
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void appendAddresses(AddressApiClient apiClient, Kubernetes kubernetes, TimeoutBudget budget, AddressSpace addressSpace, boolean wait, int batchSize, Destination... destinations) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.APPEND_ADDRESS);
        apiClient.appendAddresses(addressSpace, batchSize, destinations);
        if (wait) {
            JsonObject addrSpaceObj = apiClient.getAddressSpace(addressSpace.getName());
            if (getAddressSpaceType(addrSpaceObj).equals("standard")) {
                if (destinations.length == 0) {
                    waitForExpectedPods(kubernetes, addressSpace, kubernetes.getExpectedPods(addressSpace.getPlan()), budget);
                }
            }
            waitForDestinationsReady(apiClient, addressSpace, budget, destinations);
        }
        TimeMeasuringSystem.stopOperation(operationID);
    }

    /**
     * send whatever request to restapi route
     *
     * @param apiClient client for sending http requests to api server
     * @param method    http method PUT, POST, DELETE, GET
     * @param url       api route
     * @param payload   JsonObject as a payload
     * @return JsonObject
     * @throws Exception
     */
    public static JsonObject sendRestApiRequest(AddressApiClient apiClient, HttpMethod method, URL url, int expectedCode,
                                                Optional<JsonObject> payload) throws Exception {
        return apiClient.sendRequest(method, url, expectedCode, payload);
    }


    /**
     * Check if AddressSpace exists
     *
     * @param apiClient        client for http requests on api server
     * @param addressSpaceName name of AddressSpace
     * @return true if AddressSpace exists, false otherwise
     * @throws Exception
     */
    public static boolean existAddressSpace(AddressApiClient apiClient, String addressSpaceName) throws Exception {
        return apiClient.listAddressSpaces().contains(addressSpaceName);
    }

    /**
     * Check if isReady attribute of AddressSpace(JsonObject) is set to true
     *
     * @param addressSpace address space object (usually received from AddressApiClient)
     * @return true if AddressSpace is ready, false otherwise
     */
    public static boolean isAddressSpaceReady(JsonObject addressSpace) {
        boolean isReady = false;
        if (addressSpace != null) {
            isReady = addressSpace.getJsonObject("status").getBoolean("isReady");
        }
        return isReady;
    }

    /**
     * Check if state and description values equals required values
     *
     * @param status answer from service broker on 'last_operation'
     * @return true->state=succeeded / otherwise else
     */
    public static boolean isServiceInstanceReady(JsonObject status) {
        boolean isReady = false;
        if (status != null) {
            String state = status.getString("state");
            String description = status.getString("description");
            isReady = (state.equals("succeeded") && description.equals("All required pods are ready."));
        }
        return isReady;
    }

    /**
     * Get address space type from received AddressSpace(JsonObject) (usually received from AddressApiClient)
     *
     * @param addressSpace address space JsonObject
     * @return
     */
    public static String getAddressSpaceType(JsonObject addressSpace) {
        String addrSpaceType = "";
        if (addressSpace != null) {
            addrSpaceType = addressSpace.getJsonObject("spec").getString("type");
        }
        return addrSpaceType;
    }

    /**
     * wait until isReady parameter of Address Space is set to true within timeout
     *
     * @param apiClient    instance of AddressApiClient
     * @param addressSpace name of addressSpace
     * @throws Exception IllegalStateException if address space is not ready within timeout
     */
    public static AddressSpace waitForAddressSpaceReady(AddressApiClient apiClient, String addressSpace) throws Exception {
        JsonObject addressSpaceObject = null;
        TimeoutBudget budget = null;

        boolean isReady = false;
        budget = new TimeoutBudget(3, TimeUnit.MINUTES);
        while (budget.timeLeft() >= 0 && !isReady) {
            addressSpaceObject = apiClient.getAddressSpace(addressSpace);
            isReady = isAddressSpaceReady(addressSpaceObject);
            if (!isReady) {
                Thread.sleep(10000);
            }
            log.info("Waiting until Address space: '{}' will be in ready state", addressSpace);
        }
        if (!isReady) {
            throw new IllegalStateException("Address Space " + addressSpace + " is not in Ready state within timeout.");
        }
        waitUntilEndpointsPresent(apiClient, addressSpace);
        return convertToAddressSpaceObject(apiClient.listAddressSpacesObjects()).stream().filter(addrSpaceI ->
                addrSpaceI.getName().equals(addressSpace)).findFirst().get();
    }

    /**
     * Waiting until service instance will be in ready state
     *
     * @param apiClient  open service broker api client for sending requests
     * @param instanceId id of service instance
     * @throws Exception
     */
    public static void waitForServiceInstanceReady(OSBApiClient apiClient, String username, String instanceId) throws Exception {
        TimeoutBudget budget = new TimeoutBudget(3, TimeUnit.MINUTES);
        boolean isReady = false;
        while (budget.timeLeft() >= 0 && !isReady) {
            isReady = isServiceInstanceReady(apiClient.getLastOperation(username, instanceId));
            if (!isReady) {
                Thread.sleep(10000);
            }
            log.info("Waiting until service instance '{}' will be in ready state", instanceId);
        }
        if (!isReady) {
            throw new IllegalStateException(String.format("Service instance '%s' is not in Ready state within timeout.", instanceId));
        }
        log.info("Service instance '{}' is in ready state", instanceId);
    }

    private static void waitUntilEndpointsPresent(AddressApiClient apiClient, String name) throws Exception {
        waitUntilEndpointsPresent(apiClient, name, new TimeoutBudget(15, TimeUnit.SECONDS));
    }

    /**
     * Periodically check address space endpoints and wait until endpoints will be present
     */
    private static void waitUntilEndpointsPresent(AddressApiClient apiClient, String name, TimeoutBudget budget) throws Exception {
        log.info(String.format("Waiting until endpoints for address space '%s' will be present...", name));
        JsonObject addressSpaceJson = apiClient.getAddressSpace(name);
        boolean endpointsReady = false;
        while (budget.timeLeft() >= 0) {
            if (convertJsonToAddressSpace(addressSpaceJson).getEndpoints() != null) {
                endpointsReady = true;
                break;
            }
            addressSpaceJson = apiClient.getAddressSpace(name);
            Thread.sleep(1000);
        }
        if (!endpointsReady) {
            throw new IllegalStateException(String.format("Address-space '%s' have no ready endpoints!", name));
        }
    }

    public static AddressSpace getAddressSpaceObject(AddressApiClient apiClient, String addressSpaceName) throws Exception {
        JsonObject addressSpaceJson = apiClient.getAddressSpace(addressSpaceName);
        log.info("address space received {}", addressSpaceJson.toString());
        return convertToAddressSpaceObject(addressSpaceJson).get(0);
    }

    public static List<AddressSpace> getAddressSpacesObjects(AddressApiClient apiClient) throws Exception {
        JsonObject addressSpaceJson = apiClient.listAddressSpacesObjects();
        return convertToAddressSpaceObject(addressSpaceJson);
    }

    /**
     * get list of Address names by REST API
     */
    public static Future<List<String>> getAddresses(AddressApiClient apiClient, AddressSpace addressSpace,
                                                    Optional<String> addressName, List<String> skipAddresses) throws Exception {
        JsonObject response = apiClient.getAddresses(addressSpace, addressName);
        CompletableFuture<List<String>> listOfAddresses = new CompletableFuture<>();
        listOfAddresses.complete(convertToListAddress(response, String.class, object -> !skipAddresses.contains(object.getJsonObject("spec").getString("address"))));
        return listOfAddresses;
    }

    /**
     * get list of Address objects by REST API
     */
    public static Future<List<Address>> getAddressesObjects(AddressApiClient apiClient, AddressSpace addressSpace,
                                                            Optional<String> addressName, Optional<HashMap<String, String>> queryParams,
                                                            List<String> skipAddresses) throws Exception {
        JsonObject response = apiClient.getAddresses(addressSpace, addressName, queryParams);
        CompletableFuture<List<Address>> listOfAddresses = new CompletableFuture<>();
        listOfAddresses.complete(convertToListAddress(response, Address.class, object -> !skipAddresses.contains(object.getJsonObject("spec").getString("address"))));
        return listOfAddresses;
    }

    /**
     * get list of Address objects
     */
    public static Future<List<Destination>> getDestinationsObjects(AddressApiClient apiClient, AddressSpace addressSpace,
                                                                   Optional<String> addressName, List<String> skipAddresses) throws Exception {
        JsonObject response = apiClient.getAddresses(addressSpace, addressName);
        CompletableFuture<List<Destination>> listOfAddresses = new CompletableFuture<>();
        listOfAddresses.complete(convertToListAddress(response, Destination.class, object -> !skipAddresses.contains(object.getJsonObject("spec").getString("address"))));
        return listOfAddresses;
    }

    /**
     * get schema object by REST API
     */
    public static Future<SchemaData> getSchema(AddressApiClient apiClient) throws Exception {
        JsonObject response = apiClient.getSchema();
        CompletableFuture<SchemaData> schema = new CompletableFuture<>();
        schema.complete(getSchemaObject(response));
        return schema;
    }

    /**
     * Check if isReady attribute is set to true
     *
     * @param address JsonObject with address
     * @return
     */
    public static boolean isAddressReady(JsonObject address) {
        boolean isReady = false;
        if (address != null) {
            isReady = address.getJsonObject("status").getBoolean("isReady");
        }
        return isReady;
    }

    /**
     * Pulling out name,type and plan of addresses from json object
     *
     * @param htmlResponse JsonObject with specified structure returned from rest api
     * @return list of addresses
     */
    public static <T> List<T> convertToListAddress(JsonObject htmlResponse, Class<T> clazz, Predicate<JsonObject> filter) {
        if (htmlResponse != null) {
            String kind = htmlResponse.getString("kind");
            List<T> addresses = new ArrayList<>();
            switch (kind) {
                case "Address":
                    if (filter.test(htmlResponse)) {
                        if (clazz.equals(String.class)) {
                            addresses.add((T) htmlResponse.getJsonObject("spec").getString("address"));
                        } else if (clazz.equals(Address.class)) {
                            addresses.add((T) getAddressObject(htmlResponse));
                        } else if (clazz.equals(Destination.class)) {
                            addresses.add((T) getDestinationObject(htmlResponse));
                        }
                    }
                    break;
                case "AddressList":
                    JsonArray items = htmlResponse.getJsonArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.size(); i++) {
                            if (filter.test(items.getJsonObject(i))) {
                                if (clazz.equals(String.class)) {
                                    addresses.add((T) items.getJsonObject(i).getJsonObject("spec").getString("address"));
                                } else if (clazz.equals(Address.class)) {
                                    addresses.add((T) getAddressObject(items.getJsonObject(i)));
                                } else if (clazz.equals(Destination.class)) {
                                    addresses.add((T) getDestinationObject(items.getJsonObject(i)));
                                }
                            }
                        }
                    }
                    break;
                default:
                    throw new IllegalArgumentException(String.format("Unknown kind: '%s'", kind));
            }
            return addresses;
        }
        throw new IllegalArgumentException("htmlResponse can't be null");
    }


    /**
     * Convert restapi json response(kind: AddressSpace or AddressSpaceList) from api server to AddressSpace object
     *
     * @param addressSpaceJson
     * @return
     */
    private static List<AddressSpace> convertToAddressSpaceObject(JsonObject addressSpaceJson) {
        if (addressSpaceJson == null) {
            throw new IllegalArgumentException("null response can't be converted to AddressSpace");
        }
        String kind = addressSpaceJson.getString("kind");
        List<AddressSpace> resultAddrSpace = new ArrayList<>();
        switch (kind) {
            case "AddressSpace":
                resultAddrSpace.add(convertJsonToAddressSpace(addressSpaceJson));
                break;
            case "AddressSpaceList":
                JsonArray items = addressSpaceJson.getJsonArray("items");
                for (int i = 0; i < items.size(); i++) {
                    resultAddrSpace.add(convertJsonToAddressSpace(items.getJsonObject(i)));
                }
                break;
            default:
                throw new IllegalArgumentException(String.format("Unknown kind: '%s'", kind));
        }
        return resultAddrSpace;
    }

    /**
     * Convert single JsonObject (kind: AddressSpace) to AddressSpace
     *
     * @param addressSpaceJson
     * @return
     */
    private static AddressSpace convertJsonToAddressSpace(JsonObject addressSpaceJson) {
        String name = addressSpaceJson.getJsonObject("metadata").getString("name");
        String namespace = addressSpaceJson.getJsonObject("metadata").getString("namespace");
        String infraUuid = addressSpaceJson.getJsonObject("metadata").getJsonObject("annotations").getString("enmasse.io/infra-uuid");
        AddressSpaceType type = AddressSpaceType.valueOf(
                addressSpaceJson.getJsonObject("spec")
                        .getString("type").toUpperCase());
        String plan = addressSpaceJson.getJsonObject("spec").getString("plan");

        AuthService authService = AuthService.valueOf(
                addressSpaceJson.getJsonObject("spec")
                        .getJsonObject("authenticationService")
                        .getString("type").toUpperCase());

        List<AddressSpaceEndpoint> endpoints = new ArrayList<>();
        JsonArray endpointsJson = addressSpaceJson.getJsonObject("spec").getJsonArray("endpoints");
        if (endpointsJson != null) {
            for (int i = 0; i < endpointsJson.size(); i++) {
                JsonObject endpointJson = endpointsJson.getJsonObject(i);
                endpoints.add(new AddressSpaceEndpoint(endpointJson.getString("name"),
                        endpointJson.getString("service"), endpointJson.getString("servicePort")));
            }
        }

        JsonArray endpointsStatusJson = addressSpaceJson.getJsonObject("status").getJsonArray("endpointStatuses");
        if (endpointsStatusJson != null) {
            for (int i = 0; i < endpointsStatusJson.size(); i++) {
                JsonObject endpointJson = endpointsStatusJson.getJsonObject(i);
                String ename = endpointJson.getString("name");

                String host = null;
                int port = 0;

                if (endpointJson.containsKey("host")) {
                    host = endpointJson.getString("host");
                }

                if (endpointJson.containsKey("port")) {
                    port = endpointJson.getInteger("port");
                }


                for (AddressSpaceEndpoint endpoint : endpoints) {
                    if (endpoint.getName().equals(ename)) {
                        endpoint.setHost(host);
                        endpoint.setPort(port);
                    }
                }
            }
        }
        AddressSpace addrSpace = new AddressSpace(name, namespace, type, plan, authService);
        addrSpace.setEndpoints(endpoints);
        addrSpace.setInfraUuid(infraUuid);
        return addrSpace;
    }

    /**
     * Create object of Address class from JsonObject
     *
     * @param addressJsonObject
     * @return
     */
    private static Address getAddressObject(JsonObject addressJsonObject) {
        log.info("Got address object: {}", addressJsonObject.toString());
        JsonObject spec = addressJsonObject.getJsonObject("spec");
        String address = spec.getString("address");
        String type = spec.getString("type");
        String plan = spec.getString("plan");

        JsonObject metadata = addressJsonObject.getJsonObject("metadata");
        String name = metadata.getString("name");
        String uid = metadata.getString("uid");
        String addressSpaceName = metadata.getString("addressSpace");

        JsonObject status = addressJsonObject.getJsonObject("status");
        boolean isReady = status.getBoolean("isReady");
        String phase = status.getString("phase");
        List<String> messages = new ArrayList<>();
        try {
            JsonArray jsonMessages = status.getJsonArray("messages");
            for (int i = 0; i < jsonMessages.size(); i++) {
                messages.add(jsonMessages.getValue(i).toString());
            }
        } catch (Exception ignored) {
        }
        return new Address(addressSpaceName, address, name, type, plan, phase, isReady, messages, uid);
    }

    /**
     * Create object of SchemaData class from JsonObject
     *
     * @param addressJsonObject
     * @return
     */
    private static SchemaData getSchemaObject(JsonObject addressJsonObject) {
        log.info("Got Schema object: {}", addressJsonObject.toString());
        List<AddressSpaceTypeData> data = new ArrayList<>();
        JsonObject spec = addressJsonObject.getJsonObject("spec");
        JsonArray addressSpaceTypes = spec.getJsonArray("addressSpaceTypes");
        for (int i = 0; i < addressSpaceTypes.size(); i++) {
            data.add(new AddressSpaceTypeData(addressSpaceTypes.getJsonObject(i)));
        }

        return new SchemaData(data);
    }

    /**
     * Create object of Destination class from JsonObject
     *
     * @param addressJsonObject
     * @return
     */
    private static Destination getDestinationObject(JsonObject addressJsonObject) {
        log.info("Got address object: {}", addressJsonObject.toString());
        JsonObject metadata = addressJsonObject.getJsonObject("metadata");
        String name = metadata.getString("name");
        String uid = metadata.getString("uid");
        String addressSpace = metadata.getString("addressSpace");
        JsonObject spec = addressJsonObject.getJsonObject("spec");
        String address = spec.getString("address");
        String type = spec.getString("type");
        String plan = spec.getString("plan");
        if (!validDestinationTypes.contains(type)) {
            throw new IllegalStateException(String.format("Unknown Destination type'%s'", type));
        }
        return new Destination(name, uid, addressSpace, address, type, plan);
    }

    /**
     * Wait until destinations isReady parameter is set to true with 1 MINUTE timeout for each destination
     *
     * @param apiClient    instance of AddressApiClient
     * @param addressSpace name of addressSpace
     * @param budget       the timeout budget for this operation
     * @param destinations variable count of destinations
     * @throws Exception IllegalStateException if destinations are not ready within timeout
     */
    public static void waitForDestinationsReady(AddressApiClient apiClient, AddressSpace addressSpace, TimeoutBudget budget, Destination... destinations) throws Exception {
        Map<String, JsonObject> notReadyAddresses = new HashMap<>();

        while (budget.timeLeft() >= 0) {
            JsonObject addressList = apiClient.getAddresses(addressSpace, Optional.empty());
            notReadyAddresses = checkAddressesReady(addressList, destinations);
            if (notReadyAddresses.isEmpty()) {
                Thread.sleep(5000); //TODO: remove this sleep after fix for ready check will be available
                break;
            }
            Thread.sleep(5000);
        }

        if (!notReadyAddresses.isEmpty()) {
            JsonObject addressList = apiClient.getAddresses(addressSpace, Optional.empty());
            notReadyAddresses = checkAddressesReady(addressList, destinations);
            throw new IllegalStateException(notReadyAddresses.size() + " out of " + destinations.length
                    + " addresses are not ready: " + notReadyAddresses.values());
        }
    }

    /**
     * Go through all addresses in AddressList in JsonObject and check if all of them are in ready state
     *
     * @param addressList  received from AddressApiClient
     * @param destinations required destinations which should be ready
     * @return
     */
    private static Map<String, JsonObject> checkAddressesReady(JsonObject addressList, Destination... destinations) {
        log.info("Checking {} for ready state", destinations);
        Map<String, JsonObject> notReadyAddresses = new HashMap<>();
        for (Destination destination : destinations) {
            JsonObject addressObject = lookupAddress(addressList, destination.getAddress());
            if (addressObject == null) {
                notReadyAddresses.put(destination.getAddress(), null);
            } else if (!isAddressReady(addressObject)) {
                notReadyAddresses.put(destination.getAddress(), addressObject);
            }
        }
        return notReadyAddresses;
    }

    /**
     * Get address(JsonObject) from AddressList(JsonObject) by address name
     *
     * @param addressList JsonObject received from AddressApiClient
     * @param address     address name
     * @return
     */
    private static JsonObject lookupAddress(JsonObject addressList, String address) {
        JsonArray items = addressList.getJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            JsonObject addressObject = items.getJsonObject(i);
            if (addressObject.getJsonObject("spec").getString("address").equals(address)) {
                return addressObject;
            }
        }
        return null;
    }

    /**
     * Generate message body with prefix
     */
    public static List<String> generateMessages(String prefix, int numMessages) {
        return IntStream.range(0, numMessages).mapToObj(i -> prefix + i).collect(Collectors.toList());
    }

    /**
     * Generate message body with "testmessage" content and without prefix
     */
    public static List<String> generateMessages(int numMessages) {
        return generateMessages("testmessage", numMessages);
    }

    /**
     * Check if endpoint is accessible
     */
    public static boolean resolvable(Endpoint endpoint) {
        for (int i = 0; i < 10; i++) {
            try {
                InetAddress[] addresses = Inet4Address.getAllByName(endpoint.getHost());
                Thread.sleep(1000);
                return addresses.length > 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (UnknownHostException ignore) {
            }
        }
        return false;
    }

    /**
     * Wait until AddressSpace will be removed
     *
     * @param kubernetes   client for manipulation with kubernetes cluster
     * @param addressSpace AddressSpace that should be removed
     */
    public static void waitForAddressSpaceDeleted(Kubernetes kubernetes, AddressSpace addressSpace) throws Exception {
        TimeoutBudget budget = new TimeoutBudget(5, TimeUnit.MINUTES);
        while (budget.timeLeft() >= 0 && kubernetes.listPods(Collections.singletonMap("infraUuid", addressSpace.getInfraUuid())).size() > 0) {
            Thread.sleep(1000);
        }
        if (kubernetes.listPods(Collections.singletonMap("infraUuid", addressSpace.getInfraUuid())).size() > 0) {
            throw new TimeoutException("Timed out waiting for namespace " + addressSpace.getName() + " to disappear");
        }
    }

    /**
     * Wait until Namespace will be removed
     *
     * @param kubernetes client for manipulation with kubernetes cluster
     * @param namespace  project/namespace to remove
     */
    public static void waitForNamespaceDeleted(Kubernetes kubernetes, String namespace) throws Exception {
        TimeoutBudget budget = new TimeoutBudget(5, TimeUnit.MINUTES);
        while (budget.timeLeft() >= 0 && kubernetes.listNamespaces().contains(namespace)) {
            Thread.sleep(1000);
        }
        if (kubernetes.listNamespaces().contains(namespace)) {
            throw new TimeoutException("Timed out waiting for namespace " + namespace + " to disappear");
        }
    }

    /**
     * Repeat request n-times in a row
     *
     * @param retry count of remaining retries
     * @param fn    request function
     * @return
     */
    public static <T> T doRequestNTimes(int retry, Callable<T> fn, Optional<Runnable> reconnect) throws Exception {
        try {
            return fn.call();
        } catch (Exception ex) {
            if (ex.getCause() instanceof VertxException && ex.getCause().getMessage().contains("Connection was closed")) {
                if (reconnect.isPresent()) {
                    log.warn("connection was closed, trying to reconnect...");
                    reconnect.get().run();
                }
            }
            if (ex.getCause() instanceof UnknownHostException && retry > 0) {
                try {
                    log.info("{} remaining iterations", retry);
                    return doRequestNTimes(retry - 1, fn, reconnect);
                } catch (Exception ex2) {
                    throw ex2;
                }
            } else {
                if (ex.getCause() != null) {
                    ex.getCause().printStackTrace();
                } else {
                    ex.printStackTrace();
                }
                throw ex;
            }
        }
    }

    /**
     * Replace address plan in ConfigMap of already existing address
     *
     * @param kubernetes client for manipulation with kubernetes cluster
     * @param addrSpace  address space which contains ConfigMap
     * @param dest       destination which will be modified
     * @param plan       definition of AddressPlan
     */
    public static void replaceAddressConfig(Kubernetes kubernetes, AddressSpace addrSpace, Destination dest, AddressPlan plan) {
        String mapKey = "config.json";
        ConfigMap destConfigMap = kubernetes.getConfigMap(addrSpace.getNamespace(), dest.getAddress());

        JsonObject data = new JsonObject(destConfigMap.getData().get(mapKey));
        log.info(data.toString());
        data.getJsonObject("spec").remove("plan");
        data.getJsonObject("spec").put("plan", plan.getName());

        Map<String, String> modifiedData = new LinkedHashMap<>();
        modifiedData.put(mapKey, data.toString());
        destConfigMap.setData(modifiedData);
        kubernetes.replaceConfigMap(addrSpace.getNamespace(), destConfigMap);
    }

    /**
     * create new AddressPlanConfig
     *
     * @param kubernetes  client for manipulation with kubernetes cluster
     * @param addressPlan definition of AddressPlan
     */
    public static void createAddressPlanConfig(Kubernetes kubernetes, AddressPlan addressPlan, boolean replaceExisting) {
        kubernetes.createAddressPlanConfig(addressPlan, replaceExisting);
    }

    /**
     * Get AddressPlan definition by name of the config file
     *
     * @param configName name attribute within ConfigMap object
     * @return AddressPlan definition
     */
    public static AddressPlan getAddressPlanConfig(String configName) throws RuntimeException {
        throw new RuntimeException("This method is not implemented!");
    }

    /**
     * Remove AddressPlan definition by name of the config file
     *
     * @param kubernetes  client for manipulation with kubernetes cluster
     * @param addressPlan AddressPlan object
     * @return true if AddressPlan was removed successfully
     */
    public static boolean removeAddressPlanConfig(Kubernetes kubernetes, AddressPlan addressPlan) {
        return kubernetes.removeAddressPlanConfig(addressPlan);
    }

    /**
     * Append AddressPlan definition into already existing AddressSpacePlan config
     *
     * @param kubernetes       client for manipulation with kubernetes cluster
     * @param addressPlan      AddressPlan definition
     * @param addressSpacePlan AddressSpacePlan definition
     */
    public static void appendAddressPlan(Kubernetes kubernetes, AddressPlan addressPlan, AddressSpacePlan addressSpacePlan) {
        kubernetes.appendAddressPlan(addressPlan, addressSpacePlan);
    }

    /**
     * Remove AddressPlan definition from already existing AddressSpacePlan config
     *
     * @param kubernetes       client for manipulation with kubernetes cluster
     * @param addressPlan      AddressPlan definition
     * @param addressSpacePlan AddressSpacePlan definition
     * @return true if AddressPlan was removed successfully
     */
    public static boolean removeAddressPlan(Kubernetes kubernetes, AddressPlan addressPlan, AddressSpacePlan addressSpacePlan) {
        return kubernetes.removeAddressPlan(addressPlan, addressSpacePlan);
    }

    /**
     * create new AddressSpacePlanConfig
     *
     * @param kubernetes       client for manipulation with kubernetes cluster
     * @param addressSpacePlan definition of AddressSpacePlan
     */
    public static void createAddressSpacePlanConfig(Kubernetes kubernetes, AddressSpacePlan addressSpacePlan, boolean replaceExisting) {
        kubernetes.createAddressSpacePlanConfig(addressSpacePlan, replaceExisting);
    }

    /**
     * Get AddressSpacePlan definition by name of the config file
     *
     * @param config     name attribute within ConfigMap object
     * @param kubernetes client for manipulation with kubernetes cluster
     * @return AddressPlan definition
     */
    public static AddressSpacePlan getAddressSpacePlanConfig(Kubernetes kubernetes, String config) {
        return kubernetes.getAddressSpacePlanConfig(config);
    }

    /**
     * Remove AddressSpacePlan definition by name of the config file
     *
     * @param kubernetes       client for manipulation with kubernetes cluster
     * @param addressSpacePlan AddressSpacePlan object
     * @return true if AddressSpacePlan was removed successfully
     */
    public static boolean removeAddressSpacePlanConfig(Kubernetes kubernetes, AddressSpacePlan addressSpacePlan) {
        return kubernetes.removeAddressSpacePlanConfig(addressSpacePlan);
    }

    /**
     * create new ResourceDefinition
     *
     * @param kubernetes         client for manipulation with kubernetes cluster
     * @param resourceDefinition ResourceDefinition
     */
    public static void createResourceDefinitionConfig(Kubernetes kubernetes, ResourceDefinition resourceDefinition, boolean replaceExisting) {
        kubernetes.createResourceDefinitionConfig(resourceDefinition, replaceExisting);
    }

    /**
     * Get ResourceDefinition by name of the config file
     *
     * @param config     name attribute within ConfigMap object
     * @param kubernetes client for manipulation with kubernetes cluster
     * @return ResourceDefinition
     */
    public static ResourceDefinition getResourceDefinitionConfig(Kubernetes kubernetes, String config) {
        return kubernetes.getResourceDefinitionConfig(config);
    }

    /**
     * Remove ResourceDefinition by name of the config file
     *
     * @param kubernetes         client for manipulation with kubernetes cluster
     * @param resourceDefinition ResourceDefinition object
     * @return true if AddressSpacePlan was removed successfully
     */
    public static boolean removeResourceDefinitionConfig(Kubernetes kubernetes, ResourceDefinition resourceDefinition) {
        return kubernetes.removeResourceDefinitionConfig(resourceDefinition);
    }

    public static String sanitizeAddress(String address) {
        return address != null ? address.toLowerCase().replaceAll("[^a-z0-9.\\-]", "") : address;
    }

    public static String getExternalEndpointName(AddressSpace addressSpace, String service) {
        for (AddressSpaceEndpoint endpoint : addressSpace.getEndpoints()) {
            if (endpoint.getService().equals(service) && endpoint.getName() != null && !endpoint.getName().isEmpty()) {
                return endpoint.getName();
            }
        }
        return service;
    }

    public static void deleteAddressSpace(AddressApiClient addressApiClient, AddressSpace addressSpace, GlobalLogCollector logCollector) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.DELETE_ADDRESS_SPACE);
        logCollector.collectEvents(addressSpace.getNamespace());
        logCollector.collectLogsTerminatedPods(addressSpace.getNamespace());
        logCollector.collectConfigMaps(addressSpace.getNamespace());
        addressApiClient.deleteAddressSpace(addressSpace);
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void deleteAllAddressSpaces(AddressApiClient addressApiClient) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.DELETE_ADDRESS_SPACE);
        addressApiClient.deleteAddressSpaces(200);
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void deleteAddressSpaceCreatedBySC(Kubernetes kubernetes, AddressSpace addressSpace, String namespace, GlobalLogCollector logCollector) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.DELETE_ADDRESS_SPACE);
        logCollector.collectEvents(addressSpace.getNamespace());
        logCollector.collectLogsTerminatedPods(addressSpace.getNamespace());
        logCollector.collectConfigMaps(addressSpace.getNamespace());
        kubernetes.deleteNamespace(namespace);
        waitForNamespaceDeleted(kubernetes, namespace);
        waitForAddressSpaceDeleted(kubernetes, addressSpace);
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static RemoteWebDriver getFirefoxDriver() throws Exception {
        return getRemoteDriver("localhost", 4444, new FirefoxOptions());
    }

    public static RemoteWebDriver getChromeDriver() throws Exception {
        return getRemoteDriver("localhost", 4443, new ChromeOptions());
    }

    private static RemoteWebDriver getRemoteDriver(String host, int port, Capabilities options) throws Exception {
        int attempts = 30;
        for (int i = 0; i < attempts; i++) {
            if (pingHost(host, port, 500)) {
                return new RemoteWebDriver(new URL(String.format("http://%s:%s/wd/hub", host, port)), options);
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Selenium webdriver cannot connect to selenium container");
    }

    private static boolean pingHost(String host, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static void waitUntilCondition(Callable<String> fn, String expected, TimeoutBudget budget) throws Exception {
        String actual = "Too small time out budget!!";
        while (!budget.timeoutExpired()) {
            actual = fn.call();
            log.debug(actual);
            if (actual.contains(expected)) {
                return;
            }
            log.debug("next iteration, remaining time: {}", budget.timeLeft());
            Thread.sleep(1000);
        }
        Assertions.fail(String.format("Expected: '%s' in content, but was: '%s'", expected, actual));
    }

    public static Endpoint deployMessagingClientApp(String namespace, Kubernetes kubeClient) throws Exception {
        Endpoint endpoint = kubeClient.createServiceFromResource(namespace, getMessagingClientServiceResource());
        kubeClient.createDeploymentFromResource(namespace, getMessagingClientDeploymentResource());
        Thread.sleep(5000);
        return endpoint;
    }

    public static void deleteMessagingClientApp(String namespace, Kubernetes kubeClient) {
        kubeClient.deleteDeployment(namespace, SystemtestsOpenshiftApp.MESSAGING_CLIENTS.toString());
        kubeClient.deleteService(namespace, SystemtestsOpenshiftApp.MESSAGING_CLIENTS.toString());
    }

    public static String getTopicPrefix(boolean topicSwitch) {
        return topicSwitch ? "topic://" : "";
    }

    private static Deployment getMessagingClientDeploymentResource() {
        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(SystemtestsOpenshiftApp.MESSAGING_CLIENTS.toString())
                .endMetadata()
                .withNewSpec()
                .withNewSelector()
                .addToMatchLabels("app", SystemtestsOpenshiftApp.MESSAGING_CLIENTS.toString())
                .endSelector()
                .withReplicas(1)
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", SystemtestsOpenshiftApp.MESSAGING_CLIENTS.toString())
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName(SystemtestsOpenshiftApp.MESSAGING_CLIENTS.toString())
                .withImage("docker.io/kornysd/docker-clients:latest")
                .addNewPort()
                .withContainerPort(4242)
                .endPort()
                .withNewLivenessProbe()
                .withNewTcpSocket()
                .withNewPort(4242)
                .endTcpSocket()
                .withInitialDelaySeconds(10)
                .withPeriodSeconds(5)
                .endLivenessProbe()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private static Service getMessagingClientServiceResource() {
        return new ServiceBuilder()
                .withNewMetadata()
                .withName(SystemtestsOpenshiftApp.MESSAGING_CLIENTS.toString())
                .addToLabels("run", SystemtestsOpenshiftApp.MESSAGING_CLIENTS.toString())
                .endMetadata()
                .withNewSpec()
                .withSelector(Collections.singletonMap("app", SystemtestsOpenshiftApp.MESSAGING_CLIENTS.toString()))
                .addNewPort()
                .withName("http")
                .withPort(4242)
                .withProtocol("TCP")
                .endPort()
                .endSpec()
                .build();
    }
}
