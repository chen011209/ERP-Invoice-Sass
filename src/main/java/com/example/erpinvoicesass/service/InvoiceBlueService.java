package com.example.erpinvoicesass.service;

import com.example.erpinvoicesass.entity.InvoiceRecord;
import com.example.erpinvoicesass.enums.InvoiceStatus;
import com.example.erpinvoicesass.mapper.InvoiceRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Service
@Slf4j
public class InvoiceBlueService {

    @Resource
    private InvoiceRecordMapper invoiceRecordMapper;
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Transactional(rollbackFor = Exception.class)
    public String submitBlueInvoice(String orderId, String payload) {
        InvoiceRecord record = invoiceRecordMapper.selectByOrderId(orderId);

        if (record != null) {
            switch (record.getStatus()) {
                case SUCCESS:
                    return "单据已开票成功，严禁修改重开！请先红冲原单据，生成新单据号重新发起。";

                case WAIT_CALLBACK:
                case RED_WAIT_CALLBACK:
                    return "单据已提交税局，正在等待税局异步返回结果，请耐心等待，严禁此时修改或重试！";

                case PROCESSING:
                case RED_PROCESSING:
                case INIT:
                case RED_INIT:
                    return "单据正在队列排队或提交中，请勿重复操作。";

                case RED_SUCCESS:
                case RED_FAIL:
                    return "单据已进入红冲生命周期，严禁再次发起正向开票。";

                case FAIL:
                    int affected = invoiceRecordMapper.updateForUpstreamRetry(
                            orderId, payload, InvoiceStatus.FAIL.name(), InvoiceStatus.INIT.name());
                    if (affected == 1) {
                        rocketMQTemplate.convertAndSend("NuonuoInvoiceTopic:BLUE", orderId);
                        return "修改单据并重新发起开票成功";
                    }
                    return "单据状态已变更，请勿频繁操作";
            }
        }

        try {
            record = new InvoiceRecord();
            record.setOrderId(orderId);
            record.setStatus(InvoiceStatus.INIT);
            record.setReqPayload(payload);
            record.setRetryCount(0);
            invoiceRecordMapper.insert(record);

            rocketMQTemplate.convertAndSend("NuonuoInvoiceTopic:BLUE", orderId);
            return "开票请求受理成功";
        } catch (DuplicateKeyException e) {
            return "请求正在受理中，请勿频繁点击";
        }
    }
}
