package com.example.erpinvoicesass.client;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson.JSON;
import com.example.erpinvoicesass.context.TenantContext;
import com.example.erpinvoicesass.entity.TenantConfig;
import com.example.erpinvoicesass.mapper.TenantConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 诺诺API客户端
 * 实现基于appId和appSecret的签名加密请求
 */
@Slf4j
@Component
public class NuonuoApiClient {

    private static final String NNONUO_API_URL = "https://sdk.nuonuo.com/open/v1/services";

    @Resource
    private TenantConfigMapper tenantConfigMapper;

    @Resource
    private RestTemplate restTemplate;

    /**
     * 提交蓝票
     */
    public void submitBlueInvoice(String orderId) {
        // 1. 获取租户配置
        String tenantId = TenantContext.getTenantId();
        TenantConfig tenantConfig = tenantConfigMapper.selectByTenantId(tenantId);
        if (tenantConfig == null) {
            throw new RuntimeException("租户配置不存在");
        }

        // 2. 构建请求参数
        Map<String, Object> params = new HashMap<>();
        params.put("orderId", orderId);
        params.put("invoiceType", "blue");
        params.put("buyerName", "测试购买方");
        params.put("buyerTaxNum", "91110000XXXXXXXX");
        params.put("amount", 100.00);
        params.put("taxAmount", 13.00);
        params.put("totalAmount", 113.00);

        // 3. 发送加密请求
        String response = sendRequest(tenantConfig.getAppId(), tenantConfig.getAppSecret(), 
                "nuonuo.ElectronInvoice.createInvoice", params);

//        todo 开票成功后需要更新流水号
        
        log.info("蓝票提交成功，订单ID：{}，响应：{}", orderId, response);
    }

    /**
     * 提交红票
     */
    public void submitRedInvoice(String orderId) {
        // 1. 获取租户配置
        String tenantId = TenantContext.getTenantId();
        TenantConfig tenantConfig = tenantConfigMapper.selectByTenantId(tenantId);
        if (tenantConfig == null) {
            throw new RuntimeException("租户配置不存在");
        }

        // 2. 构建请求参数
        Map<String, Object> params = new HashMap<>();
        params.put("orderId", orderId);
        params.put("invoiceType", "red");
        params.put("originalInvoiceCode", "011001900111");
        params.put("originalInvoiceNo", "12345678");
        params.put("redReason", "销货退回");

        // 3. 发送加密请求
        String response = sendRequest(tenantConfig.getAppId(), tenantConfig.getAppSecret(), 
                "nuonuo.ElectronInvoice.createRedInvoice", params);
        
        log.info("红票提交成功，订单ID：{}，响应：{}", orderId, response);
    }

    /**
     * 发送加密请求
     * 
     * @param appId     应用ID
     * @param appSecret 应用密钥
     * @param method    API方法名
     * @param params    业务参数
     * @return 响应结果
     */
    private String sendRequest(String appId, String appSecret, String method, Map<String, Object> params) {
        try {
            // 1. 构建基础参数
            Map<String, String> baseParams = new TreeMap<>();
            baseParams.put("appId", appId);
            baseParams.put("method", method);
            baseParams.put("timestamp", String.valueOf(System.currentTimeMillis()));
            baseParams.put("nonce", generateNonce());
            baseParams.put("version", "1.0");

            // 2. 构建业务参数JSON
            String content = JSON.toJSONString(params);
            baseParams.put("content", content);

            // 3. 生成签名
            String sign = generateSign(baseParams, appSecret);
            baseParams.put("sign", sign);

            // 4. 发送HTTP请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(baseParams, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                    NNONUO_API_URL,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            return response.getBody();
        } catch (Exception e) {
            log.error("调用诺诺API失败", e);
            throw new RuntimeException("调用诺诺API失败: " + e.getMessage());
        }
    }

    /**
     * 生成签名
     * 签名规则：将参数按key排序后拼接成字符串，最后加上appSecret，进行MD5加密
     * 
     * @param params    参数Map
     * @param appSecret 应用密钥
     * @return 签名结果
     */
    private String generateSign(Map<String, String> params, String appSecret) {
        // 使用TreeMap已经按key排序
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            // 跳过sign参数本身
            if ("sign".equals(entry.getKey())) {
                continue;
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
        }
        // 去掉最后一个&
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }
        // 追加appSecret
        sb.append(appSecret);

        // MD5加密并转大写
        return DigestUtil.md5Hex(sb.toString()).toUpperCase();
    }

    /**
     * 生成随机数
     */
    private String generateNonce() {
        return String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
    }
}
