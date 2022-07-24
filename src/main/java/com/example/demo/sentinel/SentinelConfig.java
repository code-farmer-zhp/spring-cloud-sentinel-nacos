package com.example.demo.sentinel;

import com.alibaba.cloud.nacos.registry.NacosRegistrationCustomizer;
import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class SentinelConfig {

    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    @Bean
    public NacosRegistrationCustomizer nacosRegistrationCustomizer() {
        return registration -> {
            Map<String, String> metadata = registration.getMetadata();
            if (metadata != null) {
                metadata.put("lastUpTime", System.currentTimeMillis() + "");
                metadata.put("sentinelVersion", Constants.SENTINEL_VERSION);
            }
        };
    }
}
