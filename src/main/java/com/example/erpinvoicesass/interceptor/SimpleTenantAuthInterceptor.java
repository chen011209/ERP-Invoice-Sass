package com.example.erpinvoicesass.interceptor;

import cn.hutool.crypto.digest.DigestUtil;
import com.example.erpinvoicesass.context.TenantContext;
import com.example.erpinvoicesass.entity.TenantConfig;
import com.example.erpinvoicesass.mapper.TenantConfigMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

@Component
public class SimpleTenantAuthInterceptor implements HandlerInterceptor {

    @Resource
    private TenantConfigMapper tenantConfigMapper;

    @Resource
    private StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientId = request.getHeader("X-Client-Id");
        String timestamp = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");
        String sign = request.getHeader("X-Sign");

        //调用方先用appId secret 时间戳 nonce随机值计算一次签名，把签名传过来后
        //我用appId查出对应secret也按同样哈希计算一次签名，比对签名是否一致

        if (clientId == null || timestamp == null || nonce == null || sign == null) {
            throw new RuntimeException("拒绝访问：缺少鉴权参数");
        }

        long reqTime = Long.parseLong(timestamp);
        if (Math.abs(System.currentTimeMillis() - reqTime) > 5 * 60 * 1000) {
            throw new RuntimeException("拒绝访问：请求已过期");
        }

        //nonce是随机值，防止重放
        String redisKey = "auth:nonce:" + clientId + ":" + nonce;
        Boolean absent = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", 5, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(absent)) {
            throw new RuntimeException("拒绝访问：请求重复提交");
        }

        TenantConfig tenant = tenantConfigMapper.selectByClientId(clientId);
        if (tenant == null || tenant.getStatus() == 0) {
            throw new RuntimeException("拒绝访问：非法ClientId");
        }

        String expectSign = DigestUtil.md5Hex(clientId + timestamp + nonce + tenant.getClientSecret());
        if (!expectSign.equalsIgnoreCase(sign)) {
            throw new RuntimeException("拒绝访问：签名错误");
        }

        TenantContext.setTenantId(tenant.getTenantId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
