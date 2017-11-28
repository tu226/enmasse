package io.enmasse.systemtest;

import io.enmasse.systemtest.amqp.AmqpClient;
import io.vertx.core.json.JsonObject;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.message.Message;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class ArtemisManagement extends BrokerManagement {

    public ArtemisManagement() {
        managementAddress = "activemq.management";
    }

    @Override
    public List<String> getQueueNames(AmqpClient queueClient, Destination replyQueue, Destination dest) throws Exception {
        Message requestMessage = Message.Factory.create();
        Map<String, String> appProperties = new HashMap<>();
        appProperties.put("_AMQ_ResourceName", "address." + dest.getAddress());
        appProperties.put("_AMQ_OperationName", "getQueueNames");
        requestMessage.setAddress(managementAddress);
        requestMessage.setApplicationProperties(new ApplicationProperties(appProperties));
        requestMessage.setReplyTo(replyQueue.getAddress());
        requestMessage.setBody(new AmqpValue("[]"));

        Future<Integer> sent = queueClient.sendMessages(managementAddress, requestMessage);
        assertThat(sent.get(30, TimeUnit.SECONDS), is(1));
        Logging.log.info("request sent");

        Future<List<Message>> received = queueClient.recvMessages(replyQueue.getAddress(), 1);
        assertThat(received.get(30, TimeUnit.SECONDS).size(), is(1));
        Logging.log.info("answer received");

        AmqpValue val = (AmqpValue) received.get().get(0).getBody();
        String queues = val.getValue().toString();
        queues = queues.replaceAll("\\[|]|\"", "");

        return Arrays.asList(queues.split(","));
    }

    @Override
    public int getSubscriberCount(AmqpClient queueClient, Destination replyQueue, String queue) throws Exception {
        Message requestMessage = Message.Factory.create();
        Map<String, String> appProperties = new HashMap<>();
        appProperties.put("_AMQ_ResourceName", "queue." + queue);
        appProperties.put("_AMQ_OperationName", "getConsumerCount");
        requestMessage.setAddress(managementAddress);
        requestMessage.setApplicationProperties(new ApplicationProperties(appProperties));
        requestMessage.setReplyTo(replyQueue.getAddress());
        requestMessage.setBody(new AmqpValue("[]"));

        Future<Integer> sent = queueClient.sendMessages(managementAddress, requestMessage);
        assertThat(sent.get(30, TimeUnit.SECONDS), is(1));
        Logging.log.info("request sent");

        Future<List<Message>> received = queueClient.recvMessages(replyQueue.getAddress(), 1);
        assertThat(received.get(30, TimeUnit.SECONDS).size(), is(1));
        Logging.log.info("answer received");

        AmqpValue val = (AmqpValue) received.get().get(0).getBody();
        String count = val.getValue().toString().replaceAll("\\[|]|\"", "");

        return Integer.valueOf(count);
    }
}
