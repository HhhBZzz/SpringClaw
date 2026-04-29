package com.springclaw.job;

import com.springclaw.service.task.executor.TaskExecutionService;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ScheduledTaskDispatchJob {

    private final TaskExecutionService taskExecutionService;
    private final boolean taskEnabled;
    private final int dispatcherBatchSize;
    private final int scanIntervalSeconds;
    private volatile long lastDispatchAt = 0L;

    public ScheduledTaskDispatchJob(TaskExecutionService taskExecutionService,
                                    @Value("${springclaw.task.enabled:true}") boolean taskEnabled,
                                    @Value("${springclaw.task.dispatcher.batch-size:20}") int dispatcherBatchSize,
                                    @Value("${springclaw.task.dispatcher.scan-interval-seconds:60}") int scanIntervalSeconds) {
        this.taskExecutionService = taskExecutionService;
        this.taskEnabled = taskEnabled;
        this.dispatcherBatchSize = Math.max(1, dispatcherBatchSize);
        this.scanIntervalSeconds = Math.max(1, scanIntervalSeconds);
    }

    @XxlJob("scheduledTaskDispatchJob")
    public ReturnT<String> scheduledTaskDispatchJob(String param) {
        if (!taskEnabled) {
            XxlJobHelper.log("scheduled task disabled, skip dispatch");
            return ReturnT.SUCCESS;
        }
        long now = System.currentTimeMillis();
        if (lastDispatchAt > 0 && now - lastDispatchAt < scanIntervalSeconds * 1000L) {
            XxlJobHelper.log("scheduled task dispatch skipped, interval={0}s not reached", scanIntervalSeconds);
            return ReturnT.SUCCESS;
        }
        lastDispatchAt = now;
        int executed = taskExecutionService.dispatchDueTasks(dispatcherBatchSize);
        XxlJobHelper.log("scheduled-task-dispatch executed={0}", executed);
        return ReturnT.SUCCESS;
    }
}
