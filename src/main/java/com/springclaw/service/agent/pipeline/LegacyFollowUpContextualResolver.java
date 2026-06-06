package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.exception.ContextResolutionException;
import com.springclaw.service.agent.lifecycle.ResolutionType;
import com.springclaw.service.agent.lifecycle.ResolvedInput;
import com.springclaw.service.agent.lifecycle.TurnContext;
import com.springclaw.service.chat.impl.ContextualFollowUpQuestionResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
public class LegacyFollowUpContextualResolver implements ContextualResolver {

    private final ContextualFollowUpQuestionResolver legacyResolver;
    private final ContextResolutionPolicy resolutionPolicy;

    public LegacyFollowUpContextualResolver(ContextualFollowUpQuestionResolver legacyResolver,
                                            ContextResolutionPolicy resolutionPolicy) {
        this.legacyResolver = legacyResolver;
        this.resolutionPolicy = resolutionPolicy;
    }

    @Override
    public ResolvedInput resolve(TurnContext context) throws ContextResolutionException {
        String original = context == null || context.rawInput() == null ? "" : context.rawInput().text();
        if (context == null || !resolutionPolicy.canResolve(context.utterance(), context.intentDecision())) {
            String type = context == null || context.utterance() == null ? "UNKNOWN" : context.utterance().type().name();
            return ResolvedInput.bypassed(original, "话语类型=" + type + "，跳过追问消解。");
        }
        try {
            String resolved = legacyResolver.resolve(context.turnId().sessionKey(), original);
            if (!StringUtils.hasText(resolved) || original.equals(resolved)) {
                return ResolvedInput.unchanged(original, "追问消解未改写输入。");
            }
            return new ResolvedInput(
                    resolved,
                    ResolutionType.CONTEXTUAL_REWRITE,
                    context.utterance().confidence(),
                    "通过话语类型和置信度校验后执行追问消解。",
                    true
            );
        } catch (Exception ex) {
            String requestId = context.runState() == null ? "" : context.runState().requestId();
            throw new ContextResolutionException(
                    requestId,
                    "追问消解失败",
                    Map.of("rawInput", original),
                    ex
            );
        }
    }
}
