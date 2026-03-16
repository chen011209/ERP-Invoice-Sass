package com.example.erpinvoicesass.service;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ConsumerContainerManager {

    @Autowired
    private ApplicationContext applicationContext;

    public DefaultMQPushConsumer getConsumerByGroup(String consumerGroup) {
        Map<String, DefaultMQPushConsumer> consumers = applicationContext.getBeansOfType(DefaultMQPushConsumer.class);
        for (DefaultMQPushConsumer consumer : consumers.values()) {
            if (consumerGroup.equals(consumer.getConsumerGroup())) {
                return consumer;
            }
        }
        return null;
    }

    public void stopConsumer(String consumerGroup) {
        DefaultMQPushConsumer consumer = getConsumerByGroup(consumerGroup);
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    public void startConsumer(String consumerGroup) {
        DefaultMQPushConsumer consumer = getConsumerByGroup(consumerGroup);
        if (consumer != null) {
            try {
                consumer.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public boolean isConsumerRunning(String consumerGroup) {
        DefaultMQPushConsumer consumer = getConsumerByGroup(consumerGroup);
        return consumer != null;
    }
}
