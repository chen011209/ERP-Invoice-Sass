package com.example.erpinvoicesass.job;

import com.example.erpinvoicesass.entity.InvoiceRecord;
import com.example.erpinvoicesass.enums.InvoiceStatus;
import com.example.erpinvoicesass.mapper.InvoiceRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时扫描失败任务，自动重试
 */
@Slf4j
@Component
public class RetryFailedInvoiceJob {

    @Resource
    private InvoiceRecordMapper invoiceRecordMapper;
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    //定时扫描重试失败任务
    @Scheduled(cron = "0 0/1 * * * ?") // 每1分钟扫描一次
    public void autoRetryFailInvoices() {

        int maxRetry = 3;
        // 使用新写的阶梯式 SQL 捞取数据
        List<InvoiceRecord> failRecords = invoiceRecordMapper.selectSmartAutoRetryRecords(100);

        for (InvoiceRecord record : failRecords) {
            boolean isBlue = record.getStatus() == InvoiceStatus.FAIL;
            String oldStatus = isBlue ? InvoiceStatus.FAIL.name() : InvoiceStatus.RED_FAIL.name();
            String newStatus = isBlue ? InvoiceStatus.INIT.name() : InvoiceStatus.RED_INIT.name();

            // 乐观锁：状态改 INIT，retry_count + 1
            int affected = invoiceRecordMapper.updateForAutoRetry(record.getId(), oldStatus, newStatus, maxRetry);

            if (affected == 1) {
                String tag = isBlue ? "BLUE" : "RED";
                rocketMQTemplate.convertAndSend("NuonuoInvoiceTopic:" + tag, record.getOrderId());
                log.info("触发阶梯自动重试. OrderId:{}, 当前重试次数:{}/{}", record.getOrderId(), record.getRetryCount() + 1, maxRetry);
            }
        }
    }


}
