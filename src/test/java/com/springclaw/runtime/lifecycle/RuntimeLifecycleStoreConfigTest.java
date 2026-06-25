package com.springclaw.runtime.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.config.RuntimeLifecycleStoreConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RuntimeLifecycleStoreConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(RuntimeLifecycleStoreConfig.class);

    @Test
    void defaultsToInMemoryLifecycleStore() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RunLifecycleStore.class);
            assertThat(context.getBean(RunLifecycleStore.class))
                    .isInstanceOf(InMemoryRunLifecycleStore.class);
        });
    }

    @Test
    void mysqlPropertySelectsMySqlLifecycleStore() {
        contextRunner
                .withPropertyValues("springclaw.runtime.lifecycle.store=mysql")
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(RunLifecycleStore.class);
                    assertThat(context).hasSingleBean(MySqlRunLifecycleStore.class);
                    assertThat(context.getBean(RunLifecycleStore.class))
                            .isInstanceOf(MySqlRunLifecycleStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryRunLifecycleStore.class);
                });
    }
}
