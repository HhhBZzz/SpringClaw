package com.springclaw.config.job;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class XxlJobConfig {

    @Bean(initMethod = "start", destroyMethod = "destroy")
    @ConditionalOnProperty(prefix = "springclaw.xxl-job", name = "enabled", havingValue = "true")
    public XxlJobSpringExecutor xxlJobSpringExecutor(
            @Value("${springclaw.xxl-job.admin-addresses:}") String adminAddresses,
            @Value("${springclaw.xxl-job.access-token:}") String accessToken,
            @Value("${springclaw.xxl-job.app-name:springclaw}") String appName,
            @Value("${springclaw.xxl-job.ip:}") String ip,
            @Value("${springclaw.xxl-job.port:9999}") int port,
            @Value("${springclaw.xxl-job.log-path:/tmp/xxl-job}") String logPath,
            @Value("${springclaw.xxl-job.log-retention-days:30}") int logRetentionDays) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAccessToken(accessToken);
        executor.setAppname(appName);
        executor.setAddress("");
        executor.setIp(ip);
        executor.setPort(port);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}
