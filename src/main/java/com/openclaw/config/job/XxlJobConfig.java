package com.openclaw.config.job;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class XxlJobConfig {

    @Bean(initMethod = "start", destroyMethod = "destroy")
    @ConditionalOnProperty(prefix = "openclaw.xxl-job", name = "enabled", havingValue = "true")
    public XxlJobSpringExecutor xxlJobSpringExecutor(
            @Value("${openclaw.xxl-job.admin-addresses:}") String adminAddresses,
            @Value("${openclaw.xxl-job.access-token:}") String accessToken,
            @Value("${openclaw.xxl-job.app-name:openclaw}") String appName,
            @Value("${openclaw.xxl-job.ip:}") String ip,
            @Value("${openclaw.xxl-job.port:9999}") int port,
            @Value("${openclaw.xxl-job.log-path:/tmp/xxl-job}") String logPath,
            @Value("${openclaw.xxl-job.log-retention-days:30}") int logRetentionDays) {
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
