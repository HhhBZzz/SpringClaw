package com.springclaw.config.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "springclaw.redisson", name = "enabled", havingValue = "true")
    public RedissonClient redissonClient(@Value("${springclaw.redisson.address:redis://127.0.0.1:6379}") String address,
                                         @Value("${springclaw.redisson.password:}") String password) {
        Config config = new Config();
        config.setCodec(new JsonJacksonCodec());
        config.useSingleServer().setAddress(address);
        if (StringUtils.hasText(password)) {
            config.useSingleServer().setPassword(password.trim());
        }
        return Redisson.create(config);
    }
}
