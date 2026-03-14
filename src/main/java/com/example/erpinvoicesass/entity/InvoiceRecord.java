package com.example.erpinvoicesass.entity;

import com.example.erpinvoicesass.enums.InvoiceStatus;

import java.util.Date;

public class InvoiceRecord {
    private Long id;
    private String orderId;
    private String nuonuoSerialNo;
    private String tenantId;
    private InvoiceStatus status;
    private String reqPayload;
    private String resPayload;
    private String errorMsg;
    private Integer retryCount;
    private Date createdAt;
    private Date updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getNuonuoSerialNo() {
        return nuonuoSerialNo;
    }

    public void setNuonuoSerialNo(String nuonuoSerialNo) {
        this.nuonuoSerialNo = nuonuoSerialNo;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }

    public String getReqPayload() {
        return reqPayload;
    }

    public void setReqPayload(String reqPayload) {
        this.reqPayload = reqPayload;
    }

    public String getResPayload() {
        return resPayload;
    }

    public void setResPayload(String resPayload) {
        this.resPayload = resPayload;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}
