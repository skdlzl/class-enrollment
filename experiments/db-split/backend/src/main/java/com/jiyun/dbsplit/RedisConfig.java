package com.jiyun.dbsplit;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;

@Configuration
@ConditionalOnExpression("'${experiment.mode:A}' == 'C' or '${experiment.mode:A}' == 'D' or '${experiment.mode:A}' == 'E' or '${experiment.lock-api.strategy:OFF}' == 'REDIS' or '${experiment.lock-api.strategy:OFF}' == 'HYBRID' or ('${experiment.notifications.enabled:false}' == 'true' and '${experiment.notifications.strategy:DB_CLAIM}' == 'REDIS_OUTBOX')")
public class RedisConfig {
    @Bean(destroyMethod="shutdown") RedissonClient redisson(Environment env) {
        var config=new Config();
        config.setThreads(4).setNettyThreads(4).setLockWatchdogTimeout(30000);
        config.useSingleServer().setAddress(env.getRequiredProperty("experiment.redis.address"))
            .setConnectionMinimumIdleSize(2).setConnectionPoolSize(16)
            .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(16)
            .setConnectTimeout(3000).setTimeout(3000).setRetryAttempts(0);
        return Redisson.create(config);
    }
}
