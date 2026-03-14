package com.example.erpinvoicesass.controller;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.example.erpinvoicesass.dto.ErpNotifyMsg;
import com.example.erpinvoicesass.dto.NuonuoCallbackMsg;
import com.example.erpinvoicesass.dto.NuonuoQueryResponse;
import com.example.erpinvoicesass.entity.InvoiceRecord;
import com.example.erpinvoicesass.entity.TenantConfig;
import com.example.erpinvoicesass.enums.InvoiceStatus;
import com.example.erpinvoicesass.mapper.InvoiceRecordMapper;
import com.example.erpinvoicesass.mapper.TenantConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/invoice/callback")
@Slf4j
public class NuonuoAsyncProcessor {

    @Resource
    private InvoiceRecordMapper invoiceRecordMapper;
    @Resource
    private TenantConfigMapper tenantConfigMapper;
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 接收诺诺异步回调
     */
    @PostMapping("/notify")
    public String receiveNuonuoCallback(HttpServletRequest request, @RequestBody String rawBody) {
        log.info("收到诺诺异步回调: {}", rawBody);
        
        String nuonuoSign = request.getHeader("X-Nuonuo-Sign");
        if (nuonuoSign == null) {
            return "FAIL";
        }

        try {
            JSONObject json = JSON.parseObject(rawBody);
            String serialNo = json.getString("nuonuoSerialNo");

            InvoiceRecord record = invoiceRecordMapper.selectByNuonuoSerialNo(serialNo);
            if (record == null) {
                return "SUCCESS";
            }

            TenantConfig tenant = tenantConfigMapper.selectByTenantId(record.getTenantId());
            if (tenant == null) {
                return "FAIL";
            }

            String expectedSign = DigestUtil.md5Hex(rawBody + tenant.getAppSecret());
            
            if (!expectedSign.equalsIgnoreCase(nuonuoSign)) {
                log.error("诺诺回调验签失败！疑似被伪造！流水号: {}", serialNo);
                return "FAIL"; 
            }
            
            NuonuoCallbackMsg msg = JSON.parseObject(rawBody, NuonuoCallbackMsg.class);
            processFinalResult(record, msg.isSuccess(), msg.getInvoiceData(), msg.getRejectReason());
            return "SUCCESS";

        } catch (Exception e) {
            log.error("处理诺诺回调异常", e);
            return "FAIL";
        }
    }

    /**
     * 定时任务：主动查单补偿 (防回调丢失) 查询状态长时间 ，进行主动查询IN ('WAIT_CALLBACK', 'RED_WAIT_CALLBACK')
     */
    @Scheduled(cron = "0 0/5 * * * ?")
    public void queryStuckNuonuoResult() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        List<InvoiceRecord> stuckRecords = invoiceRecordMapper.selectStuckWaitCallbackRecords(threshold, 100);

        for (InvoiceRecord record : stuckRecords) {
            try {
                NuonuoQueryResponse queryRes = queryInvoiceResult(record.getNuonuoSerialNo());

                if (queryRes.isProcessing()) {
                    continue;
                }
                processFinalResult(record, queryRes.isSuccess(), queryRes.getInvoiceData(), queryRes.getRejectReason());
            } catch (Exception e) {
                log.error("主动查单异常, serialNo:{}", record.getNuonuoSerialNo(), e);
            }
        }
    }

    /**
     * 查询发票结果（模拟实现）
     */
    private NuonuoQueryResponse queryInvoiceResult(String nuonuoSerialNo) {
        NuonuoQueryResponse response = new NuonuoQueryResponse();
        response.setNuonuoSerialNo(nuonuoSerialNo);
        response.setProcessing(false);
        response.setSuccess(true);
        response.setInvoiceData("{\"pdfUrl\":\"https://example.com/invoice.pdf\"}");
        return response;
    }

    /**
     * 处理最终结果，并回调通知 ERP
     */
    private void processFinalResult(InvoiceRecord record, boolean isSuccess, String resPayload, String rejectReason) {
        String oldStatus = record.getStatus().name();
    
        if (!oldStatus.equals(InvoiceStatus.WAIT_CALLBACK.name()) && !oldStatus.equals(InvoiceStatus.RED_WAIT_CALLBACK.name())) {
            return;
        }
    
        boolean isBlue = oldStatus.equals(InvoiceStatus.WAIT_CALLBACK.name());
        String newStatus = isSuccess ? 
                           (isBlue ? InvoiceStatus.SUCCESS.name() : InvoiceStatus.RED_SUCCESS.name()) : 
                           (isBlue ? InvoiceStatus.FAIL.name() : InvoiceStatus.RED_FAIL.name());
    
        int affected = invoiceRecordMapper.updateCallbackResult(
                record.getNuonuoSerialNo(), oldStatus, newStatus, resPayload, rejectReason);

        // 2. 只有抢到更新权的线程，才负责通知 ERP
        if (affected == 1) {
            log.info("单据生命周期结束! orderId:{}, serialNo:{}, 最终状态:{}", record.getOrderId(), record.getNuonuoSerialNo(), newStatus);

            // 3. 构建发给 ERP 的结果报文
            ErpNotifyMsg notifyMsg = new ErpNotifyMsg();
            notifyMsg.setOrderId(record.getOrderId());
            notifyMsg.setNuonuoSerialNo(record.getNuonuoSerialNo());
            notifyMsg.setStatus(newStatus);
            notifyMsg.setSuccess(isSuccess);
            notifyMsg.setInvoiceData(resPayload);
            notifyMsg.setRejectReason(rejectReason);
            notifyMsg.setInvoiceType(isBlue ? "BLUE" : "RED");

            // 有三种做法。
            // 1、发送 MQ 给自己的消费者，自己的消费者再调用南北系统 (ERP)
            // 2、直接同步调用
            // 3、南北系统自己监听这个 Topic，根据 orderId 更新他们自己订单表的开票状态
            rocketMQTemplate.convertAndSend("WebhookPushTopic", notifyMsg);
            log.info("已通过 MQ 下发开票结果给 ERP 系统. orderId:{}", record.getOrderId());
        }
    }
}
