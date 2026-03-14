package com.example.erpinvoicesass.service;

import com.example.erpinvoicesass.entity.InvoiceRecord;
import com.example.erpinvoicesass.enums.InvoiceStatus;
import com.example.erpinvoicesass.mapper.InvoiceRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Service
@Slf4j
public class InvoiceRedService {

    @Resource
    private InvoiceRecordMapper invoiceRecordMapper;
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Transactional(rollbackFor = Exception.class)
    public String submitRedInvoice(String orderId, String redPayload) {
        InvoiceRecord record = invoiceRecordMapper.selectByOrderId(orderId);

        if (record == null) {
            return "单据不存在，无法发起红冲";
        }

        switch (record.getStatus()) {
            case SUCCESS:
                int affected = invoiceRecordMapper.updateForUpstreamRetry(
                        orderId, redPayload, InvoiceStatus.SUCCESS.name(), InvoiceStatus.RED_INIT.name());
                
                if (affected == 1) {
                    sendToMq(orderId, "RED");
                    return "红冲请求受理成功";
                } else {
                    log.warn("并发发起红冲被拦截, orderId: {}", orderId);
                    return "单据正在发起红冲中，请勿频繁操作";
                }
            case WAIT_CALLBACK:
            case RED_WAIT_CALLBACK:
                return "单据已提交税局，正在等待税局异步返回结果，请耐心等待，严禁此时修改或重试！";
                
            case RED_FAIL:
                int affected2 = invoiceRecordMapper.updateForUpstreamRetry(
                        orderId, redPayload, InvoiceStatus.RED_FAIL.name(), InvoiceStatus.RED_INIT.name());
                
                if (affected2 == 1) {
                    sendToMq(orderId, "RED");
                    return "重新发起红冲受理成功";
                } else {
                    return "该单据正在被自动重试或手工重试处理中，请勿频繁操作";
                }
                
            case RED_INIT:
                return "单据已在队列排队中，请勿重复提交";
            case RED_PROCESSING:
                return "该单据正在红冲处理中，请勿重复提交";
                
            case RED_SUCCESS:
                return "该单据已红冲成功(已作废)，请勿重复发起红冲";
                
            default:
                return "当前单据状态为[" + record.getStatus() + "]，不满足红冲条件（仅 SUCCESS 或 RED_FAIL 可红冲）";
        }
    }

    private void sendToMq(String orderId, String tag) {
        rocketMQTemplate.convertAndSend("NuonuoInvoiceTopic:" + tag, orderId);
    }
}
