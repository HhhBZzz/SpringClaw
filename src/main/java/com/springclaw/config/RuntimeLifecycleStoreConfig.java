package com.springclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.MySqlRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunLifecycleStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class RuntimeLifecycleStoreConfig {

    @Bean
    @ConditionalOnProperty(
            prefix = "springclaw.runtime.lifecycle",
            name = "store",
            havingValue = "mysql"
    )
    public MySqlRunLifecycleStore mySqlRunLifecycleStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        return new MySqlRunLifecycleStore(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(RunLifecycleStore.class)
    public RunLifecycleStore inMemoryRunLifecycleStore() {
        return new InMemoryRunLifecycleStore();
    }
}
