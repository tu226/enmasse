/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.bases.clients;

import io.enmasse.systemtest.*;
import io.enmasse.systemtest.bases.TestBaseWithShared;
import io.enmasse.systemtest.messagingclients.AbstractClient;
import io.enmasse.systemtest.messagingclients.ClientArgument;
import io.enmasse.systemtest.messagingclients.ClientArgumentMap;
import io.enmasse.systemtest.messagingclients.ClientType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

public abstract class ClientTestBase extends TestBaseWithShared {
    private ClientArgumentMap arguments = new ClientArgumentMap();
    private List<AbstractClient> clients;
    protected Path logPath = null;

    @BeforeEach
    public void setUpClientBase(TestInfo info) {
        clients = new ArrayList<>();
        String clientFolder = "clients_tests";
        logPath = Paths.get(
                environment.testLogDir(),
                clientFolder,
                info.getTestClass().get().getName(),
                info.getTestMethod().get().getName());

        arguments.put(ClientArgument.USERNAME, defaultCredentials.getUsername());
        arguments.put(ClientArgument.PASSWORD, defaultCredentials.getPassword());
        arguments.put(ClientArgument.LOG_MESSAGES, "json");
        arguments.put(ClientArgument.CONN_SSL, "true");
    }

    @AfterEach
    public void teardownClient() {
        arguments.clear();
        clients.forEach(AbstractClient::stop);
        clients.clear();
    }

    private Endpoint getMessagingRoute(AddressSpace addressSpace, boolean websocket) {
        if (addressSpace.getType().equals(AddressSpaceType.STANDARD) && websocket) {
            Endpoint messagingEndpoint = addressSpace.getEndpointByName("amqp-wss");
            if (TestUtils.resolvable(messagingEndpoint)) {
                return messagingEndpoint;
            } else {
                return kubernetes.getEndpoint("messaging", "https");
            }
        } else {
            return getMessagingRoute(addressSpace);
        }
    }

    protected void doBasicMessageTest(AbstractClient sender, AbstractClient receiver) throws Exception {
        doBasicMessageTest(sender, receiver, false);
    }

    protected void doBasicMessageTest(AbstractClient sender, AbstractClient receiver, boolean websocket) throws Exception {
        clients.addAll(Arrays.asList(sender, receiver));
        int expectedMsgCount = 10;

        Destination dest = Destination.queue("message-basic" + ClientType.getAddressName(sender),
                getDefaultPlan(AddressType.QUEUE));
        setAddresses(dest);

        arguments.put(ClientArgument.BROKER, getMessagingRoute(sharedAddressSpace, websocket).toString());
        arguments.put(ClientArgument.ADDRESS, dest.getAddress());
        arguments.put(ClientArgument.COUNT, Integer.toString(expectedMsgCount));
        arguments.put(ClientArgument.MSG_CONTENT, "msg no. %d");
        if (websocket) {
            arguments.put(ClientArgument.CONN_WEB_SOCKET, "true");
            if (sharedAddressSpace.getType() == AddressSpaceType.STANDARD) {
                arguments.put(ClientArgument.CONN_WEB_SOCKET_PROTOCOLS, "binary");
            }
        }

        sender.setArguments(arguments);
        arguments.remove(ClientArgument.MSG_CONTENT);
        receiver.setArguments(arguments);

        assertTrue(sender.run(), "Sender failed, expected return code 0");
        assertTrue(receiver.run(), "Receiver failed, expected return code 0");

        assertEquals(expectedMsgCount, sender.getMessages().size(),
                String.format("Expected %d sent messages", expectedMsgCount));
        assertEquals(expectedMsgCount, receiver.getMessages().size(),
                String.format("Expected %d received messages", expectedMsgCount));
    }

    protected void doRoundRobinReceiverTest(ArtemisManagement artemisManagement, AbstractClient sender, AbstractClient receiver, AbstractClient receiver2)
            throws Exception {
        clients.addAll(Arrays.asList(sender, receiver, receiver2));
        int expectedMsgCount = 10;

        Destination dest = Destination.queue("receiver-round-robin" + ClientType.getAddressName(sender),
                getDefaultPlan(AddressType.QUEUE));
        setAddresses(dest);

        arguments.put(ClientArgument.BROKER, getMessagingRoute(sharedAddressSpace).toString());
        arguments.put(ClientArgument.ADDRESS, dest.getAddress());
        arguments.put(ClientArgument.COUNT, Integer.toString(expectedMsgCount / 2));
        arguments.put(ClientArgument.TIMEOUT, "100");


        receiver.setArguments(arguments);
        receiver2.setArguments(arguments);

        Future<Boolean> recResult = receiver.runAsync();
        Future<Boolean> rec2Result = receiver2.runAsync();

        if (isBrokered(sharedAddressSpace)) {
            waitForSubscribers(artemisManagement, sharedAddressSpace, dest.getAddress(), 2);
        } else {
            waitForSubscribersConsole(sharedAddressSpace, dest, 2);
        }

        arguments.put(ClientArgument.COUNT, Integer.toString(expectedMsgCount));
        arguments.put(ClientArgument.MSG_CONTENT, "msg no. %d");

        sender.setArguments(arguments);

        assertTrue(sender.run(), "Sender failed, expected return code 0");
        assertTrue(recResult.get(), "Receiver failed, expected return code 0");
        assertTrue(rec2Result.get(), "Receiver failed, expected return code 0");

        assertEquals(expectedMsgCount, sender.getMessages().size(),
                String.format("Expected %d sent messages", expectedMsgCount));

        assertAll(
                () -> assertEquals(expectedMsgCount / 2, receiver.getMessages().size(),
                        String.format("Expected %d received messages", expectedMsgCount / 2)),
                () -> assertEquals(expectedMsgCount / 2, receiver2.getMessages().size(),
                        String.format("Expected %d sent messages", expectedMsgCount / 2)));
    }

    protected void doTopicSubscribeTest(ArtemisManagement artemisManagement, AbstractClient sender, AbstractClient subscriber, AbstractClient subscriber2,
                                        boolean hasTopicPrefix) throws Exception {
        clients.addAll(Arrays.asList(sender, subscriber, subscriber2));
        int expectedMsgCount = 10;

        Destination dest = Destination.topic("topic-subscribe" + ClientType.getAddressName(sender),
                getDefaultPlan(AddressType.TOPIC));
        setAddresses(dest);

        arguments.put(ClientArgument.BROKER, getMessagingRoute(sharedAddressSpace).toString());
        arguments.put(ClientArgument.ADDRESS, TestUtils.getTopicPrefix(hasTopicPrefix) + dest.getAddress());
        arguments.put(ClientArgument.COUNT, Integer.toString(expectedMsgCount));
        arguments.put(ClientArgument.MSG_CONTENT, "msg no. %d");
        arguments.put(ClientArgument.TIMEOUT, "100");

        sender.setArguments(arguments);
        arguments.remove(ClientArgument.MSG_CONTENT);
        subscriber.setArguments(arguments);
        subscriber2.setArguments(arguments);

        Future<Boolean> recResult = subscriber.runAsync();
        Future<Boolean> recResult2 = subscriber2.runAsync();

        if (isBrokered(sharedAddressSpace)) {
            waitForSubscribers(artemisManagement, sharedAddressSpace, dest.getAddress(), 2);
        } else {
            waitForSubscribersConsole(sharedAddressSpace, dest, 2);
        }

        assertAll(
                () -> assertTrue(sender.run(), "Producer failed, expected return code 0"),
                () -> assertEquals(expectedMsgCount, sender.getMessages().size(),
                        String.format("Expected %d sent messages", expectedMsgCount)));
        assertAll(
                () -> assertTrue(recResult.get(), "Subscriber failed, expected return code 0"),
                () -> assertTrue(recResult2.get(), "Subscriber failed, expected return code 0"),
                () -> assertEquals(expectedMsgCount, subscriber.getMessages().size(),
                        String.format("Expected %d received messages", expectedMsgCount)),
                () -> assertEquals(expectedMsgCount, subscriber2.getMessages().size(),
                        String.format("Expected %d received messages", expectedMsgCount)));
    }

    protected void doMessageBrowseTest(AbstractClient sender, AbstractClient receiver_browse, AbstractClient receiver_receive)
            throws Exception {
        clients.addAll(Arrays.asList(sender, receiver_browse, receiver_receive));
        int expectedMsgCount = 10;

        Destination dest = Destination.queue("message-browse" + ClientType.getAddressName(sender),
                getDefaultPlan(AddressType.QUEUE));
        setAddresses(dest);

        arguments.put(ClientArgument.BROKER, getMessagingRoute(sharedAddressSpace).toString());
        arguments.put(ClientArgument.ADDRESS, dest.getAddress());
        arguments.put(ClientArgument.COUNT, Integer.toString(expectedMsgCount));
        arguments.put(ClientArgument.MSG_CONTENT, "msg no. %d");

        sender.setArguments(arguments);
        arguments.remove(ClientArgument.MSG_CONTENT);

        arguments.put(ClientArgument.RECV_BROWSE, "true");
        receiver_browse.setArguments(arguments);

        arguments.put(ClientArgument.RECV_BROWSE, "false");
        receiver_receive.setArguments(arguments);

        assertAll(
                () -> assertTrue(sender.run(), "Sender failed, expected return code 0"),
                () -> assertEquals(expectedMsgCount, sender.getMessages().size(),
                        String.format("Expected %d sent messages", expectedMsgCount)));
        assertAll(
                () -> assertTrue(receiver_browse.run(), "Browse receiver failed, expected return code 0"),
                () -> assertTrue(receiver_receive.run(), "Receiver failed, expected return code 0"),
                () -> assertEquals(expectedMsgCount, receiver_browse.getMessages().size(),
                        String.format("Expected %d browsed messages", expectedMsgCount)),
                () -> assertEquals(expectedMsgCount, receiver_receive.getMessages().size(),
                        String.format("Expected %d received messages", expectedMsgCount)));
    }

    protected void doDrainQueueTest(AbstractClient sender, AbstractClient receiver) throws Exception {
        Destination dest = Destination.queue("drain-queue" + ClientType.getAddressName(sender),
                getDefaultPlan(AddressType.QUEUE));
        setAddresses(dest);

        clients.addAll(Arrays.asList(sender, receiver));
        int expectedMsgCount = 50;

        arguments.put(ClientArgument.BROKER, getMessagingRoute(sharedAddressSpace).toString());
        arguments.put(ClientArgument.ADDRESS, dest.getAddress());
        arguments.put(ClientArgument.COUNT, Integer.toString(expectedMsgCount));
        arguments.put(ClientArgument.MSG_CONTENT, "msg no. %d");

        sender.setArguments(arguments);
        arguments.remove(ClientArgument.MSG_CONTENT);

        arguments.put(ClientArgument.COUNT, "0");
        receiver.setArguments(arguments);

        assertTrue(sender.run(), "Sender failed, expected return code 0");
        assertTrue(receiver.run(), "Drain receiver failed, expected return code 0");

        assertEquals(expectedMsgCount, sender.getMessages().size(),
                String.format("Expected %d sent messages", expectedMsgCount));
        assertEquals(expectedMsgCount, receiver.getMessages().size(),
                String.format("Expected %d received messages", expectedMsgCount));
    }

    protected void doMessageSelectorQueueTest(AbstractClient sender, AbstractClient receiver) throws Exception {
        int expectedMsgCount = 10;

        clients.addAll(Arrays.asList(sender, receiver));
        Destination queue = Destination.queue("selector-queue" + ClientType.getAddressName(sender),
                getDefaultPlan(AddressType.QUEUE));
        setAddresses(queue);

        arguments.put(ClientArgument.BROKER, getMessagingRoute(sharedAddressSpace).toString());
        arguments.put(ClientArgument.COUNT, Integer.toString(expectedMsgCount));
        arguments.put(ClientArgument.ADDRESS, queue.getAddress());
        arguments.put(ClientArgument.MSG_PROPERTY, "colour~red");
        arguments.put(ClientArgument.MSG_PROPERTY, "number~12.65");
        arguments.put(ClientArgument.MSG_PROPERTY, "a~true");
        arguments.put(ClientArgument.MSG_PROPERTY, "b~false");
        arguments.put(ClientArgument.MSG_CONTENT, "msg no. %d");

        //send messages
        sender.setArguments(arguments);
        assertTrue(sender.run(), "Sender failed, expected return code 0");
        assertEquals(expectedMsgCount, sender.getMessages().size(),
                String.format("Expected %d sent messages", expectedMsgCount));

        arguments.remove(ClientArgument.MSG_PROPERTY);
        arguments.remove(ClientArgument.MSG_CONTENT);
        arguments.put(ClientArgument.RECV_BROWSE, "true");
        arguments.put(ClientArgument.COUNT, "0");

        //receiver with selector colour = red
        arguments.put(ClientArgument.SELECTOR, "colour = 'red'");
        receiver.setArguments(arguments);
        assertAll(
                () -> assertTrue(receiver.run(), "Receiver 'colour = red' failed, expected return code 0"),
                () -> assertEquals(expectedMsgCount, receiver.getMessages().size(),
                        String.format("Expected %d received messages 'colour = red'", expectedMsgCount)));

        //receiver with selector number > 12.5
        arguments.put(ClientArgument.SELECTOR, "number > 12.5");
        receiver.setArguments(arguments);
        assertAll(
                () -> assertTrue(receiver.run(), "Receiver 'number > 12.5' failed, expected return code 0"),
                () -> assertEquals(expectedMsgCount, receiver.getMessages().size(),
                        String.format("Expected %d received messages 'colour = red'", expectedMsgCount)));


        //receiver with selector a AND b
        arguments.put(ClientArgument.SELECTOR, "a AND b");
        receiver.setArguments(arguments);
        assertAll(
                () -> assertTrue(receiver.run(), "Receiver 'a AND b' failed, expected return code 0"),
                () -> assertEquals(0, receiver.getMessages().size(),
                        String.format("Expected %d received messages 'a AND b'", 0)));

        //receiver with selector a OR b
        arguments.put(ClientArgument.RECV_BROWSE, "false");
        arguments.put(ClientArgument.SELECTOR, "a OR b");
        receiver.setArguments(arguments);
        assertAll(
                () -> assertTrue(receiver.run(), "Receiver 'a OR b' failed, expected return code 0"),
                () -> assertEquals(expectedMsgCount, receiver.getMessages().size(),
                        String.format("Expected %d received messages 'a OR b'", expectedMsgCount)));
    }

    protected void doMessageSelectorTopicTest(ArtemisManagement artemisManagement, AbstractClient sender, AbstractClient sender2,
                                              AbstractClient subscriber, AbstractClient subscriber2, boolean hasTopicPrefix) throws Exception {
        clients.addAll(Arrays.asList(sender, sender2, subscriber, subscriber2));
        int expectedMsgCount = 5;

        Destination topic = Destination.topic("selector-topic" + ClientType.getAddressName(sender),
                getDefaultPlan(AddressType.TOPIC));
        setAddresses(topic);

        arguments.put(ClientArgument.BROKER, getMessagingRoute(sharedAddressSpace).toString());
        arguments.put(ClientArgument.COUNT, Integer.toString(expectedMsgCount));
        arguments.put(ClientArgument.ADDRESS, TestUtils.getTopicPrefix(hasTopicPrefix) + topic.getAddress());
        arguments.put(ClientArgument.MSG_PROPERTY, "colour~red");
        arguments.put(ClientArgument.MSG_PROPERTY, "number~12.65");
        arguments.put(ClientArgument.MSG_PROPERTY, "a~true");
        arguments.put(ClientArgument.MSG_PROPERTY, "b~false");
        arguments.put(ClientArgument.TIMEOUT, "100");
        arguments.put(ClientArgument.MSG_CONTENT, "msg no. %d");

        //set up senders
        sender.setArguments(arguments);

        arguments.remove(ClientArgument.MSG_PROPERTY);
        arguments.put(ClientArgument.MSG_PROPERTY, "colour~blue");
        arguments.put(ClientArgument.MSG_PROPERTY, "number~11.65");

        sender2.setArguments(arguments);

        arguments.remove(ClientArgument.MSG_PROPERTY);
        arguments.remove(ClientArgument.MSG_CONTENT);

        //set up subscriber1
        arguments.put(ClientArgument.SELECTOR, "colour = 'red' AND a");
        subscriber.setArguments(arguments);

        //set up subscriber2
        arguments.put(ClientArgument.SELECTOR, "number < 12.5");
        subscriber2.setArguments(arguments);

        Future<Boolean> result1 = subscriber.runAsync();
        Future<Boolean> result2 = subscriber2.runAsync();

        if (isBrokered(sharedAddressSpace)) {
            waitForSubscribers(artemisManagement, sharedAddressSpace, topic.getAddress(), 2);
        } else {
            waitForSubscribersConsole(sharedAddressSpace, topic, 2);
        }

        assertTrue(sender.run(), "Sender failed, expected return code 0");
        assertTrue(sender2.run(), "Sender2 failed, expected return code 0");
        assertTrue(result1.get(), "Receiver 'colour = red' failed, expected return code 0");
        assertTrue(result2.get(), "Receiver 'number < 12.5' failed, expected return code 0");

        assertEquals(expectedMsgCount, sender.getMessages().size(),
                String.format("Expected %d sent messages", expectedMsgCount));
        assertEquals(expectedMsgCount, sender2.getMessages().size(),
                String.format("Expected %d sent messages", expectedMsgCount));
        assertEquals(expectedMsgCount, subscriber.getMessages().size(),
                String.format("Expected %d received messages 'colour = red' AND a", expectedMsgCount));
        assertEquals(expectedMsgCount, subscriber2.getMessages().size(),
                String.format("Expected %d received messages 'number < 12.5'", expectedMsgCount));
    }
}
