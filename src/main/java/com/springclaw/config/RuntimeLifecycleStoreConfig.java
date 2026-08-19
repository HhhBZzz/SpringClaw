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

    /**
     * db-enabled=true 而 lifecycle.store≠mysql 的半持久组合在启动时 fail-fast
     * (业务表落库但 run 事件只存内存,重启即失)。
     */
    @Bean
    public RuntimeStoreConsistencyGuard runtimeStoreConsistencyGuard(
            @org.springframework.beans.factory.annotation.Value(
                    "${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
            @org.springframework.beans.factory.annotation.Value(
                    "${springclaw.runtime.lifecycle.store:memory}") String lifecycleStore
    ) {
        return new RuntimeStoreConsistencyGuard(dbEnabled, lifecycleStore);
    }
}
