package com.example.erpinvoicesass.dto;

import lombok.Data;

/**
 * 诺诺回调消息
 */
@Data
public class NuonuoCallbackMsg {
    
    /**
     * 是否成功
     */
    private boolean success;
    
    /**
     * 诺诺流水号
     */
    private String nuonuoSerialNo;
    
    /**
     * 发票数据（PDF链接等）
     */
    private String invoiceData;
    
    /**
     * 拒绝原因（失败时）
     */
    private String rejectReason;
    
    /**
     * 订单ID
     */
    private String orderId;
    
    /**
     * 发票代码
     */
    private String invoiceCode;
    
    /**
     * 发票号码
     */
    private String invoiceNo;
    
    /**
     * 开票日期
     */
    private String invoiceDate;
    
    /**
     * 发票状态
     */
    private String invoiceStatus;
    
    /**
     * 校验码
     */
    private String checkCode;
    
    /**
     * PDF下载地址
     */
    private String pdfUrl;
    
    /**
     * 图片下载地址
     */
    private String imageUrl;
}
