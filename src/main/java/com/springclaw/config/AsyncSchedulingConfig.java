package com.springclaw.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

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

    @Bean("memoryExtractionExecutor")
    public TaskExecutor memoryExtractionExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(128);
        exec.setThreadNamePrefix("memory-extract-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(20);
        exec.initialize();
        return exec;
    }

    /**
     * 聊天流式执行器：ChatServiceImpl.stream() 把整个 SSE 生命周期（最长 30min）提交到这里。
     * 不能走 ForkJoinPool.commonPool()——2C2G 部署 parallelism=1，多个多步阻塞 LLM 循环会串行化饥饿。
     * core=8/max=32 在 2C2G 上并发足够且栈内存可接受（~32MB）；queue=64 防瞬时洪峰；
     * AbortPolicy 满了显式抛 RejectedExecutionException（由 ChatServiceImpl 转 50301）而非无限排队；
     * WaitForTasksToCompleteOnShutdown=false——流最长 30min，不阻塞关闭，让客户端重连。
     */
    @Bean("chatStreamExecutor")
    public TaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(8);
        exec.setMaxPoolSize(32);
        exec.setQueueCapacity(64);
        exec.setThreadNamePrefix("chat-stream-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.setAwaitTerminationSeconds(5);
        exec.initialize();
        return exec;
    }
}
