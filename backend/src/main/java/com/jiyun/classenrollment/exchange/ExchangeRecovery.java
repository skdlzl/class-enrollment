package com.jiyun.classenrollment.exchange;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="enrollment.exchange-recovery",havingValue="true",matchIfMissing=true)
public class ExchangeRecovery {
    private final ExchangeService service;
    public ExchangeRecovery(ExchangeService service) { this.service=service; }
    @Scheduled(fixedDelay=2000) public void recover() { service.recover(); }
}
