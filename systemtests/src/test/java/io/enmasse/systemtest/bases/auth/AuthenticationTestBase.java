/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.bases.auth;

import io.enmasse.systemtest.*;
import io.enmasse.systemtest.bases.TestBase;
import org.junit.jupiter.api.Tag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.enmasse.systemtest.TestTag.isolated;

@Tag(isolated)
public abstract class AuthenticationTestBase extends TestBase {

    protected static final String mqttAddress = "t1";
    protected static final String anonymousUser = "anonymous";
    protected static final String anonymousPswd = "anonymous";
    protected final List<Destination> amqpAddressList = Arrays.asList(
            Destination.queue("auth-queue", getDefaultPlan(AddressType.QUEUE)),
            Destination.topic("auth-topic", getDefaultPlan(AddressType.TOPIC)),
            Destination.anycast("auth-anycast"),
            Destination.multicast("auth-multicast"));

    @Override
    protected AddressSpace createAddressSpace(AddressSpace addressSpace) throws Exception {
        AddressSpace response = super.createAddressSpace(addressSpace);
        List<Destination> brokeredAddressList = new ArrayList<>(amqpAddressList);
        if (addressSpace.getType().equals(AddressSpaceType.BROKERED)) {
            brokeredAddressList = amqpAddressList.subList(0, 2);
        }
        setAddresses(addressSpace, brokeredAddressList.toArray(new Destination[0]));
        //        setAddresses(name, Destination.queue(amqpAddress)); //, Destination.topic(mqttAddress)); #TODO! for MQTT
        return response;
    }

    @Override
    protected List<AddressSpace> createAddressSpaceList(AddressSpace... addressSpaces) throws Exception {
        List<AddressSpace> result = super.createAddressSpaceList(addressSpaces);
        List<Destination> brokeredAddressList = new ArrayList<>(amqpAddressList);
        for (AddressSpace addressSpace : addressSpaces) {
            if (addressSpace.getType().equals(AddressSpaceType.BROKERED)) {
                brokeredAddressList = amqpAddressList.subList(0, 2);
            }
            setAddresses(addressSpace, brokeredAddressList.toArray(new Destination[0]));
            //        setAddresses(name, Destination.queue(amqpAddress)); //, Destination.topic(mqttAddress)); #TODO! for MQTT
        }
        return result;
    }

    protected void testNoneAuthenticationServiceGeneral(AddressSpaceType type, String emptyUser, String emptyPassword) throws Exception {
        AddressSpace s3standard = new AddressSpace(type.toString().toLowerCase() + "-s3", type);
        AddressSpace s4standard = new AddressSpace(type.toString().toLowerCase() + "-s4", type, AuthService.STANDARD);
        createAddressSpaceList(s3standard, s4standard);

        assertCanConnect(s3standard, new UserCredentials(emptyUser, emptyPassword), amqpAddressList);
        assertCanConnect(s3standard, new UserCredentials("bob", "pass"), amqpAddressList);

        assertCanConnect(s3standard, new UserCredentials(emptyUser, emptyPassword), amqpAddressList);
        assertCanConnect(s3standard, new UserCredentials("bob", "pass"), amqpAddressList);
        assertCannotConnect(s4standard, new UserCredentials(emptyUser, emptyPassword), amqpAddressList);
        assertCannotConnect(s4standard, new UserCredentials("bob", "pass"), amqpAddressList);
    }

    protected void testStandardAuthenticationServiceGeneral(AddressSpaceType type) throws Exception {
        AddressSpace s1brokered = new AddressSpace(type.toString().toLowerCase() + "-s1", type, AuthService.STANDARD);
        AddressSpace s2brokered = new AddressSpace(type.toString().toLowerCase() + "-s2", type, AuthService.STANDARD);
        createAddressSpaceList(s1brokered, s2brokered);

        // Validate unsuccessful authentication with enmasse authentication service with no credentials
        assertCannotConnect(s1brokered, new UserCredentials(null, null), amqpAddressList);
        assertCannotConnect(s1brokered, new UserCredentials("bob", "s1pass"), amqpAddressList);

        UserCredentials s1Bob = new UserCredentials("bob", "s1pass");
        createUser(s1brokered, s1Bob);

        UserCredentials s1Carol = new UserCredentials("carol", "s2pass");
        createUser(s1brokered, s1Carol);

        assertCannotConnect(s1brokered, new UserCredentials(null, null), amqpAddressList);

        // Validate successful authentication with enmasse authentication service and valid credentials
        assertCanConnect(s1brokered, s1Bob, amqpAddressList);
        assertCanConnect(s1brokered, s1Carol, amqpAddressList);

        // Validate unsuccessful authentication with enmasse authentication service with incorrect credentials
        UserCredentials bobWrongPassword = new UserCredentials(s1Bob.getUsername(), "s2pass");
        assertCannotConnect(s1brokered, bobWrongPassword, amqpAddressList);
        assertCannotConnect(s1brokered, new UserCredentials("alice", "s1pass"), amqpAddressList);


        UserCredentials s2Bob = new UserCredentials("bob", "s2pass");
        createUser(s2brokered, s2Bob);

        //create user with the same credentials in different address spaces
        UserCredentials s2Carol = new UserCredentials("carol", "s2pass");
        createUser(s2brokered, s1Carol);

        assertCanConnect(s1brokered, s1Bob, amqpAddressList);
        assertCanConnect(s1brokered, s2Carol, amqpAddressList);
        assertCanConnect(s2brokered, s2Bob, amqpAddressList);
        assertCanConnect(s2brokered, s2Carol, amqpAddressList);

        assertCannotConnect(s2brokered, s1Bob, amqpAddressList);
        assertCannotConnect(s1brokered, s2Bob, amqpAddressList);
    }

}
