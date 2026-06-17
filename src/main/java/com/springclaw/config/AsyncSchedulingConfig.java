package com.springclaw.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 全局开启 @Async + @Scheduled，并提供 P0 proposal 执行器线程池。
 * Task 8 的 ToolProposalCleanupTask 也依赖 @EnableScheduling。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncSchedulingConfig {

    /**
     * Proposal 异步执行器：confirm 后由 ToolProposalExecutionService 提交。
     * 写工具调用频率低，2-4 worker 足够；queue 64 防止短时间多用户同时确认时丢任务。
     */
    @Bean("proposalExecutor")
    public TaskExecutor proposalExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(64);
        exec.setThreadNamePrefix("proposal-exec-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        return exec;
    }
}