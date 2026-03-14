package com.example.erpinvoicesass.dto;

import lombok.Data;

/**
 * ERP通知消息
 */
@Data
public class ErpNotifyMsg {

    /**
     * 订单ID
     */
    private String orderId;

    /**
     * 诺诺流水号
     */
    private String nuonuoSerialNo;

    /**
     * 状态
     */
    private String status;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 发票数据（PDF链接等）
     */
    private String invoiceData;

    /**
     * 拒绝原因（失败时）
     */
    private String rejectReason;

    /**
     * 发票类型（BLUE/RED）
     */
    private String invoiceType;

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
