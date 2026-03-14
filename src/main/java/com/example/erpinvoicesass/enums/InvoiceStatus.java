package com.example.erpinvoicesass.enums;

public enum InvoiceStatus {
    INIT("初始化"),
    PROCESSING("处理中"),
    WAIT_CALLBACK("等待回调"),
    SUCCESS("成功"),
    FAIL("失败"),
    RED_INIT("红冲初始化"),
    RED_PROCESSING("红冲处理中"),
    RED_WAIT_CALLBACK("红冲等待回调"),
    RED_SUCCESS("红冲成功"),
    RED_FAIL("红冲失败");

    private final String desc;

    InvoiceStatus(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
