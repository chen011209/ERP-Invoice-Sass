package com.example.erpinvoicesass.enums;

/**
 * 10个状态量
 * SUCCESS RED_SUCCESS是终态，其他状态放太久都会进行重试
 * FAIL 和 RED_FAIL 会进行最多3次重试，重新发起请求
 * INIT和RED_INIT代表已经插入数据库消息表，并放入队列
 *
 *
 *
 *
 */
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
