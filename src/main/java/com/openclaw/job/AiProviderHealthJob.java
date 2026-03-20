package com.openclaw.job;

import com.openclaw.service.ai.AiProviderService;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

@Component
public class AiProviderHealthJob {

    private final AiProviderService aiProviderService;

    public AiProviderHealthJob(AiProviderService aiProviderService) {
        this.aiProviderService = aiProviderService;
    }

    @XxlJob("aiProviderHealthCheckJob")
    public ReturnT<String> aiProviderHealthCheckJob(String param) {
        String summary = String.valueOf(aiProviderService.summary());
        XxlJobHelper.log("provider-summary={0}", summary);
        return ReturnT.SUCCESS;
    }
}
