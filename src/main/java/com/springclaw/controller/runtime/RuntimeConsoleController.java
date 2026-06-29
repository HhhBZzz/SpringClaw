package com.springclaw.controller.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.domain.entity.LlmUsageRecord;
import com.springclaw.domain.entity.ScheduledTask;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.memory.evaluation.MemoryUsageTraceReader;
import com.springclaw.service.memory.evaluation.MemoryUsageTraceView;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.task.ScheduledTaskService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import com.springclaw.web.auth.RequestUserContext;
import com.springclaw.web.auth.RequestUserContextHolder;
import com.springclaw.web.auth.RequireRole;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/runtime-console")
public class RuntimeConsoleController {

    private final SkillRegistryService skillRegistryService;
    private final SkillService skillService;
    private final ScheduledTaskService scheduledTaskService;
    private final AiProviderService aiProviderService;
    private final LlmUsageRecordService llmUsageRecordService;
    private final AgentRunTraceService agentRunTraceService;
    private final CapabilityRegistry capabilityRegistry;
    private final MemoryUsageTraceReader memoryUsageTraceReader;

    public RuntimeConsoleController(SkillRegistryService skillRegistryService,
                                    SkillService skillService,
                                    ScheduledTaskService scheduledTaskService,
                                    AiProviderService aiProviderService,
                                    LlmUsageRecordService llmUsageRecordService,
                                    AgentRunTraceService agentRunTraceService,
                                    CapabilityRegistry capabilityRegistry,
                                    MemoryUsageTraceReader memoryUsageTraceReader) {
        this.skillRegistryService = skillRegistryService;
        this.skillService = skillService;
        this.scheduledTaskService = scheduledTaskService;
        this.aiProviderService = aiProviderService;
        this.llmUsageRecordService = llmUsageRecordService;
        this.agentRunTraceService = agentRunTraceService;
        this.capabilityRegistry = capabilityRegistry;
        this.memoryUsageTraceReader = memoryUsageTraceReader;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        RequestUserContext context = requireContext();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", userPayload(context));
        payload.put("runtimeSkills", buildSkills(context));
        payload.put("tools", buildTools(context));
        payload.put("tasks", buildTasks(context, null, 12));
        payload.put("llmUsage", buildUsage(context, 20));
        payload.put("providers", buildModelProviders(context));
        payload.put("agentRuns", agentRunTraceService.recentRuns(isAdmin(context) ? null : context.username(), 20));
        return ApiResponse.success(payload);
    }

    @GetMapping("/skills")
    public ApiResponse<Map<String, Object>> skills() {
        return ApiResponse.success(buildSkills(requireContext()));
    }

    @GetMapping("/tools")
    public ApiResponse<List<Map<String, Object>>> tools() {
        return ApiResponse.success(buildTools(requireContext()));
    }

    @GetMapping("/tasks")
    public ApiResponse<Map<String, Object>> tasks(@RequestParam(required = false) String ownerUserId,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(buildTasks(requireContext(), ownerUserId, limit));
    }

    @GetMapping("/usage")
    public ApiResponse<Map<String, Object>> usage(@RequestParam(defaultValue = "20") int recentLimit) {
        return ApiResponse.success(buildUsage(requireContext(), recentLimit));
    }

    @GetMapping("/model-providers")
    public ApiResponse<Map<String, Object>> modelProviders() {
        return ApiResponse.success(buildModelProviders(requireContext()));
    }

    @PostMapping("/model-providers/switch")
    @RequireRole({"ADMIN"})
    public ApiResponse<Map<String, Object>> switchModelProvider(@RequestBody SwitchModelProviderRequest request) {
        if (request == null || !StringUtils.hasText(request.providerId())) {
            throw new BusinessException(40103, "providerId 不能为空");
        }
        if (StringUtils.hasText(request.model())) {
            aiProviderService.switchActiveModel(request.providerId(), request.model(), "runtime-console");
        } else {
            aiProviderService.switchActiveProvider(request.providerId(), "runtime-console");
        }
        return ApiResponse.success(buildModelProviders(requireContext()));
    }

    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(@RequestParam(defaultValue = "20") int limit) {
        RequestUserContext context = requireContext();
        return ApiResponse.success(agentRunTraceService.recentRuns(isAdmin(context) ? null : context.username(), limit));
    }

    @GetMapping("/runs/memory-usage")
    public ApiResponse<MemoryUsageTraceView> runMemoryUsage(@RequestParam String requestId) {
        RequestUserContext context = requireContext();
        if (!StringUtils.hasText(requestId)) {
            throw new BusinessException(40103, "requestId 不能为空");
        }
        return ApiResponse.success(memoryUsageTraceReader.readLatest(
                requestId,
                isAdmin(context) ? null : context.username()
        ));
    }

    private Map<String, Object> buildSkills(RequestUserContext context) {
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks("api", context.username());
        List<SkillDefinition> definitions = isAdmin(context)
                ? skillRegistryService.listAllDefinitions()
                : skillRegistryService.listAgentVisibleDefinitions(allowedToolPacks);
        Map<String, Long> sourceCounts = definitions.stream()
                .collect(Collectors.groupingBy(
                        definition -> defaultText(definition.sourceType(), "UNKNOWN"),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", definitions.size());
        payload.put("scope", isAdmin(context) ? "all-installed" : "agent-visible");
        payload.put("allowedToolPacks", allowedToolPacks);
        payload.put("sourceCounts", sourceCounts);
        payload.put("definitions", definitions);
        return payload;
    }

    private List<Map<String, Object>> buildTools(RequestUserContext context) {
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks("api", context.username());
        return capabilityRegistry.listToolViews().stream()
                .map(tool -> withToolAllowance(tool, allowedToolPacks))
                .toList();
    }

    private Map<String, Object> buildTasks(RequestUserContext context, String ownerUserId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String ownerFilter = isAdmin(context) ? ownerUserId : null;
        List<ScheduledTask> tasks = scheduledTaskService.listTasks(
                context.username(),
                context.roleCode(),
                ownerFilter,
                null,
                safeLimit
        );
        long enabledCount = tasks.stream().filter(task -> task.getEnabled() == null || task.getEnabled() == 1).count();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", tasks.size());
        payload.put("enabled", enabledCount);
        payload.put("disabled", tasks.size() - enabledCount);
        payload.put("scope", isAdmin(context) ? "admin" : "owner");
        payload.put("tasks", tasks);
        return payload;
    }

    private Map<String, Object> buildUsage(RequestUserContext context, int recentLimit) {
        int safeLimit = Math.max(1, Math.min(recentLimit, 100));
        if (isAdmin(context)) {
            Map<String, Object> payload = new LinkedHashMap<>(llmUsageRecordService.summary(300));
            payload.put("scope", "global");
            payload.put("recent", llmUsageRecordService.listRecent(safeLimit));
            return payload;
        }

        List<LlmUsageRecord> recent = llmUsageRecordService.listRecent(300).stream()
                .filter(record -> context.username().equalsIgnoreCase(defaultText(record.getUserId(), "")))
                .limit(safeLimit)
                .toList();
        long promptTokens = recent.stream().mapToLong(record -> record.getPromptTokens() == null ? 0L : record.getPromptTokens()).sum();
        long completionTokens = recent.stream().mapToLong(record -> record.getCompletionTokens() == null ? 0L : record.getCompletionTokens()).sum();
        long totalTokens = recent.stream().mapToLong(record -> record.getTotalTokens() == null ? 0L : record.getTotalTokens()).sum();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "owner-recent");
        payload.put("totalCalls", recent.size());
        payload.put("totalPromptTokens", promptTokens);
        payload.put("totalCompletionTokens", completionTokens);
        payload.put("totalTokens", totalTokens);
        payload.put("topProvider", topByCount(recent, "provider"));
        payload.put("topModel", topByCount(recent, "model"));
        payload.put("recent", recent);
        return payload;
    }

    private Map<String, Object> buildModelProviders(RequestUserContext context) {
        Map<String, Object> payload = new LinkedHashMap<>(aiProviderService.summary());
        payload.put("canSwitch", isAdmin(context));
        payload.put("scope", isAdmin(context) ? "admin" : "read-only");
        return payload;
    }

    private Map<String, Object> userPayload(RequestUserContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", context.username());
        payload.put("roleCode", context.roleCode());
        payload.put("admin", isAdmin(context));
        return payload;
    }

    private Map<String, Object> withToolAllowance(Map<String, Object> tool, Set<String> allowedToolPacks) {
        Map<String, Object> item = new LinkedHashMap<>(tool);
        item.put("allow", isToolAllowed(
                String.valueOf(item.getOrDefault("toolset", "")),
                String.valueOf(item.getOrDefault("packId", item.getOrDefault("name", ""))),
                allowedToolPacks));
        return item;
    }

    private String topByCount(List<LlmUsageRecord> records, String field) {
        return records.stream()
                .map(record -> "provider".equals(field) ? record.getProviderId() : record.getModel())
                .map(value -> defaultText(value, "unknown"))
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()))
                .entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private RequestUserContext requireContext() {
        RequestUserContext context = RequestUserContextHolder.get();
        if (context == null) {
            throw new BusinessException(40112, "当前接口需要登录后访问");
        }
        return context;
    }

    private boolean isAdmin(RequestUserContext context) {
        return context != null && context.hasAnyRole("ADMIN");
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private boolean isToolAllowed(String toolset, String packId, Set<String> allowedToolPacks) {
        if (allowedToolPacks == null || allowedToolPacks.isEmpty()) {
            return false;
        }
        return allowedToolPacks.contains(toolset) || allowedToolPacks.contains(packId);
    }

    public record SwitchModelProviderRequest(String providerId, String model) {
    }
}
