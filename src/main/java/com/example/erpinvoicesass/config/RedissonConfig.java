package com.example.erpinvoicesass.config;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    /**
     * 初始化诺诺开票接口的全局分布式限流器
     */
    @Bean
    public RRateLimiter nuonuoRateLimiter(RedissonClient redissonClient) {
        // 1. 获取限流器实例
        RRateLimiter rateLimiter = redissonClient.getRateLimiter("nuonuo:invoice:ratelimiter");
        
        // 2. 初始化限流规则
        // RateType.OVERALL: 全局限流（所有分布式应用节点共享这个 QPS）
        // rate: 50 (产生令牌的数量)
        // rateInterval: 1 (时间间隔)
        // RateIntervalUnit.SECONDS: 秒
        // 综合含义：全局每 1 秒钟产生 50 个令牌，即最高 50 QPS
        rateLimiter.trySetRate(RateType.OVERALL, 50, 1, RateIntervalUnit.SECONDS);
        
        return rateLimiter;
    }
}
