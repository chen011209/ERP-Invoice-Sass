package com.example.erpinvoicesass.job;

import com.example.erpinvoicesass.entity.InvoiceRecord;
import com.example.erpinvoicesass.enums.InvoiceStatus;
import com.example.erpinvoicesass.mapper.InvoiceRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 定时扫描失败任务，自动重试
 * 不用mq自动重试是不好把握状态迁移，重试多了还会进死信队列
 * todo 这里还可以加很多种重试
 */
@Slf4j
@Component
public class RetryFailedInvoiceJob {

    @Resource
    private InvoiceRecordMapper invoiceRecordMapper;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private RedissonClient redissonClient;

    /**
     * 定时扫描重试失败任务
     * 使用阶梯式重试策略，优先重试最近失败的任务
     */
    @Scheduled(cron = "0 0/1 * * * ?") // 每1分钟扫描一次
    public void autoRetryFailInvoices() {
        int maxRetry = 3;
        List<InvoiceRecord> failRecords = invoiceRecordMapper.selectSmartAutoRetryRecords(100);

        for (InvoiceRecord record : failRecords) {
            // 分布式锁，防止多个实例同时处理同一个任务
            String lockKey = "invoice:retry:" + record.getId();
            RLock lock = redissonClient.getLock(lockKey);
            
            try {
                // 尝试获取锁，最多等待1秒，持有锁最多5秒
                if (lock.tryLock(1, 5, TimeUnit.SECONDS)) {
                    boolean isBlue = record.getStatus() == InvoiceStatus.FAIL;
                    String oldStatus = isBlue ? InvoiceStatus.FAIL.name() : InvoiceStatus.RED_FAIL.name();
                    String newStatus = isBlue ? InvoiceStatus.INIT.name() : InvoiceStatus.RED_INIT.name();

                    // 使用乐观锁更新，确保状态和重试次数正确
                    int affected = invoiceRecordMapper.updateForAutoRetry(record.getId(), oldStatus, newStatus, maxRetry);

                    if (affected == 1) {
                        String tag = isBlue ? "BLUE" : "RED";
                        rocketMQTemplate.convertAndSend("NuonuoInvoiceTopic:" + tag, record.getOrderId());
                        log.info("触发阶梯自动重试. OrderId:{}, 当前重试次数:{}/{}", record.getOrderId(), record.getRetryCount() + 1, maxRetry);
                    }
                } else {
                    log.debug("获取锁失败，跳过任务. OrderId:{}", record.getOrderId());
                }
            } catch (Exception e) {
                log.error("自动重试失败. OrderId:{}", record.getOrderId(), e);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * 定时扫描长时间处于INIT/RED_INIT状态的任务进行重试
     * 
     * 注意：长期在INIT状态可能是因为一直限流过不去，导致消息进入了死信队列
     * 该任务用于监控和重试这些被限流阻塞的任务，避免任务永久卡住
     * 重试3次后不再重试，防止无限重试消耗资源
     *
     *
     * 进入死信
     */
    @Scheduled(cron = "0 0/10 * * * ?") // 每10分钟扫描一次
    public void autoRetryStuckInitInvoices() {
        int maxRetry = 3;
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(30); // 超过30分钟未处理的任务

        List<InvoiceRecord> stuckRecords = invoiceRecordMapper.selectStuckInitRecords(threshold, 100);

        for (InvoiceRecord record : stuckRecords) {
            // 分布式锁，防止多个实例同时处理同一个任务
            String lockKey = "invoice:retry:" + record.getId();
            RLock lock = redissonClient.getLock(lockKey);
            
            try {
                // 尝试获取锁，最多等待1秒，持有锁最多5秒
                if (lock.tryLock(1, 5, TimeUnit.SECONDS)) {
                    boolean isBlue = record.getStatus() == InvoiceStatus.INIT;
                    String oldStatus = isBlue ? InvoiceStatus.INIT.name() : InvoiceStatus.RED_INIT.name();
                    String newStatus = isBlue ? InvoiceStatus.INIT.name() : InvoiceStatus.RED_INIT.name();

                    //这里更新了重试次数+1
                    int affected = invoiceRecordMapper.updateForAutoRetry(record.getId(), oldStatus, newStatus, maxRetry);

                    if (affected == 1) {
                        String tag = isBlue ? "BLUE" : "RED";
                        rocketMQTemplate.convertAndSend("NuonuoInvoiceTopic:" + tag, record.getOrderId());


                        log.warn("检测到长时间INIT状态任务，触发重试. OrderId:{}, 当前重试次数:{}/{}. 状态:{}",
                                record.getOrderId(), record.getRetryCount()+1, maxRetry, record.getStatus());
                    }
                } else {
                    log.debug("获取锁失败，跳过任务. OrderId:{}", record.getOrderId());
                }
            } catch (Exception e) {
                log.error("自动重试失败. OrderId:{}", record.getOrderId(), e);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}
