package com.example.erpinvoicesass.consumer;

import com.example.erpinvoicesass.client.NuonuoApiClient;
import com.example.erpinvoicesass.enums.InvoiceStatus;
import com.example.erpinvoicesass.mapper.InvoiceRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.common.message.MessageExt;
import org.redisson.api.RRateLimiter;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
@RocketMQMessageListener(topic = "NuonuoInvoiceTopic", consumerGroup = "invoice-consumer-group", selectorExpression = "BLUE || RED")
public class InvoiceConsumer implements RocketMQListener<MessageExt> {

    @Resource
    private InvoiceRecordMapper invoiceRecordMapper;
    @Resource
    private RRateLimiter nuonuoRateLimiter;
    @Resource
    private NuonuoApiClient nuonuoApiClient;
    //        todo 怎么配置暂停消费？ container.stop
    // todo         // 这里可以抛异常让 RocketMQ 重试
    //            throw new RuntimeException("触发限流，稍后重试"); 重试多了会不会进死信队列？进了怎么办
    @Override
    public void onMessage(MessageExt messageExt) {
        String tag = messageExt.getTags();
        String orderId = new String(messageExt.getBody());
        boolean isBlue = "BLUE".equals(tag);

        // 1. 限流处理
        if (!nuonuoRateLimiter.tryAcquire()) {
            log.warn("触发限流，稍后重试，订单ID：{}", orderId);
            // 这里可以抛异常让 RocketMQ 重试
            throw new RuntimeException("触发限流，稍后重试");
        }


        // 2. 消费端防重：INIT -> PROCESSING
        String oldStatus = isBlue ? InvoiceStatus.INIT.name() : InvoiceStatus.RED_INIT.name();
        String procStatus = isBlue ? InvoiceStatus.PROCESSING.name() : InvoiceStatus.RED_PROCESSING.name();
        
        //数据已经被处理了
        if (invoiceRecordMapper.updateStatusToProcessing(orderId, oldStatus, procStatus) == 0) {
            log.info("发票已被处理，无需重复处理，订单ID：{}", orderId);
            //返回后会自动 ACK
            return;
        }


        // 3. 调用诺诺API
        try {
            if (isBlue) {
                // 蓝票处理
                nuonuoApiClient.submitBlueInvoice(orderId);
            } else {
                // 红票处理
                nuonuoApiClient.submitRedInvoice(orderId);
            }
        } catch (Exception e) {
            log.error("调用诺诺API失败，订单ID：{}", orderId, e);
            // 处理失败逻辑
        }
    }
}


/**
 *
 *@Service
 * @RocketMQMessageListener(topic = "TEST_TOPIC", consumerGroup = "TEST_GROUP")
 * public class MyConsumer implements RocketMQListener<String> {
 *
 *     @Autowired
 *     private RocketMQListenerContainer container;
 *
 *     @Override
 *     public void onMessage(String message) {
 *         // 你的消费逻辑
 *     }
 *
 *     // 暴露给外部调用
 *     public void pause() {
 *         container.stop();
 *     }
 *
 *     public void resume() {
 *         container.start();
 *     }
 *
 *     public boolean isRunning() {
 *         return container.isRunning();
 *     }
 * }
 *
 * @RestController
 * @RequestMapping("/mq/consumer")
 * public class RocketMQConsumerController {
 *
 *     @Autowired
 *     private MyConsumer myConsumer;
 *
 *     // 暂停消费
 *     @GetMapping("/pause")
 *     public String pause() {
 *         myConsumer.pause();
 *         return "RocketMQ 消费者已暂停";
 *     }
 *
 *     // 恢复消费
 *     @GetMapping("/resume")
 *     public String resume() {
 *         myConsumer.resume();
 *         return "RocketMQ 消费者已恢复";
 *     }
 *
 *     // 查看状态
 *     @GetMapping("/status")
 *     public String status() {
 *         return myConsumer.isRunning() ? "运行中" : "已暂停";
 *     }
 * }
 *
 *
 */
