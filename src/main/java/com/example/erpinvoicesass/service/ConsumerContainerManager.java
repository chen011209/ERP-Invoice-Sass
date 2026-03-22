package com.example.erpinvoicesass.service;


import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * 用于控制消费者的启停，其实可以直接用mq的控制台进行启停
 */
@Slf4j
@Component
public class ConsumerContainerManager {

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 根据消费组名称获取消费者实例
     */
    public DefaultMQPushConsumer getConsumerByGroup(String consumerGroup) {
        if (consumerGroup == null || consumerGroup.trim().isEmpty()) {
            return null;
        }
        Map<String, DefaultMQPushConsumer> consumerMap = applicationContext.getBeansOfType(DefaultMQPushConsumer.class);
        for (DefaultMQPushConsumer consumer : consumerMap.values()) {
            if (consumerGroup.equals(consumer.getConsumerGroup())) {
                return consumer;
            }
        }
        log.warn("未找到消费组: [{}] 对应的消费者", consumerGroup);
        return null;
    }

    // ===================== 你需要的：临时暂停消费 =====================
    public void suspendConsumer(String consumerGroup) {
        DefaultMQPushConsumer consumer = getConsumerByGroup(consumerGroup);
        if (Objects.nonNull(consumer)) {
            try {
                consumer.suspend();
                log.info("消费组 [{}] 已【临时暂停】", consumerGroup);
            } catch (Exception e) {
                log.error("暂停消费组 [{}] 异常", consumerGroup, e);
            }
        }
    }

    // ===================== 你需要的：恢复消费 =====================
    public void resumeConsumer(String consumerGroup) {
        DefaultMQPushConsumer consumer = getConsumerByGroup(consumerGroup);
        if (Objects.nonNull(consumer)) {
            try {
                consumer.resume();
                log.info("消费组 [{}] 已【恢复消费】", consumerGroup);
            } catch (Exception e) {
                log.error("恢复消费组 [{}] 异常", consumerGroup, e);
            }
        }
    }
    public boolean isConsumerRunning(String consumerGroup) {
        return getConsumerByGroup(consumerGroup) != null;
    }
}
