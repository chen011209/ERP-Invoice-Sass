package com.example.erpinvoicesass.controller;

import com.example.erpinvoicesass.service.ConsumerContainerManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/api/consumer")
@Slf4j
public class ConsumerController {

    @Resource
    private ConsumerContainerManager consumerContainerManager;

    @PostMapping("/suspend")
    public String suspendConsumer(@RequestParam String consumerGroup) {
        try {
            consumerContainerManager.suspendConsumer(consumerGroup);
            log.info("暂停消费成功，消费者组：{}", consumerGroup);
            return "暂停消费成功";
        } catch (Exception e) {
            log.error("暂停消费失败，消费者组：{}", consumerGroup, e);
            return "暂停消费失败：" + e.getMessage();
        }
    }

    @PostMapping("/resume")
    public String resumeConsumer(@RequestParam String consumerGroup) {
        try {
            consumerContainerManager.resumeConsumer(consumerGroup);
            log.info("恢复消费成功，消费者组：{}", consumerGroup);
            return "恢复消费成功";
        } catch (Exception e) {
            log.error("恢复消费失败，消费者组：{}", consumerGroup, e);
            return "恢复消费失败：" + e.getMessage();
        }
    }

    @GetMapping("/status")
    public String getConsumerStatus(@RequestParam String consumerGroup) {
        try {
            boolean isRunning = consumerContainerManager.isConsumerRunning(consumerGroup);
            log.info("查询消费者状态，消费者组：{}，状态：{}", consumerGroup, isRunning ? "运行中" : "已暂停");
            return isRunning ? "运行中" : "已暂停";
        } catch (Exception e) {
            log.error("查询消费者状态失败，消费者组：{}", consumerGroup, e);
            return "查询失败：" + e.getMessage();
        }
    }
}
