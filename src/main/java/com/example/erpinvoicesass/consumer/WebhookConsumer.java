package com.example.erpinvoicesass.consumer;

import com.example.erpinvoicesass.dto.ErpNotifyMsg;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import javax.annotation.Resource;

/**
 * 中间件消费者监听回调任务，调用ERP
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "WebhookPushTopic", consumerGroup = "webhook-consumer-group")
public class WebhookConsumer implements RocketMQListener<ErpNotifyMsg> {

    private final RestTemplate restTemplate;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    public WebhookConsumer() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public void onMessage(ErpNotifyMsg notifyMsg) {
        log.info("收到回调消息，准备通知ERP: {}", notifyMsg);
        
        try {
            // 调用ERP的回调接口
            boolean success = callErpCallback(notifyMsg);
            if (success) {
                log.info("通知ERP成功，订单ID：{}", notifyMsg.getOrderId());
            } else {
                log.warn("通知ERP失败，订单ID：{}，准备放入MQ重试", notifyMsg.getOrderId());
                // 回调失败，放入MQ重试
                throw new RuntimeException("ERP回调失败，需要重试");
            }
        } catch (Exception e) {
            log.error("通知ERP异常，订单ID：{}，准备放入MQ重试", notifyMsg.getOrderId(), e);
            // 抛出异常让MQ自动重试
            throw e;
        }
    }

    /**
     * 调用ERP回调接口
     * @param notifyMsg 回调消息
     * @return 是否成功
     */
    private boolean callErpCallback(ErpNotifyMsg notifyMsg) {
        String orderId = notifyMsg.getOrderId();
        String erpUrl = getErpCallbackUrl(notifyMsg);
        
        if (erpUrl == null || erpUrl.isEmpty()) {
            log.error("ERP回调地址为空，订单ID：{}", orderId);
            return false;
        }

        log.info("调用ERP回调接口，订单ID：{}，URL：{}", orderId, erpUrl);

        try {
            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Request-Id", orderId);
            headers.set("X-Timestamp", String.valueOf(System.currentTimeMillis()));

            // 构建请求实体
            HttpEntity<ErpNotifyMsg> requestEntity = new HttpEntity<>(notifyMsg, headers);

            // 发送请求
            ResponseEntity<String> response = restTemplate.exchange(
                    erpUrl,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            // 检查响应状态
            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("ERP回调响应：{}", response.getBody());
                return true;
            } else {
                log.error("ERP回调返回非200状态码：{}，订单ID：{}", response.getStatusCode(), orderId);
                return false;
            }
        } catch (HttpClientErrorException e) {
            log.error("ERP回调客户端错误，订单ID：{}", orderId, e);
            return false;
        } catch (HttpServerErrorException e) {
            log.error("ERP回调服务端错误，订单ID：{}", orderId, e);
            return false;
        } catch (Exception e) {
            log.error("ERP回调网络异常，订单ID：{}", orderId, e);
            return false;
        }
    }

    /**
     * 获取ERP回调地址
     * 可以从配置或数据库中获取
     */
    private String getErpCallbackUrl(ErpNotifyMsg notifyMsg) {
        // TODO: 根据实际情况获取ERP回调地址 从租户配置表获取
        // 这里可以从配置文件、数据库或其他方式获取
        return "http://erp-system/api/invoice/callback";
    }
}
