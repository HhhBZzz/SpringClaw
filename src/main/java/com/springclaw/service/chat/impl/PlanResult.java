package com.springclaw.service.chat.impl;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 结构化计划输出。
 */
public class PlanResult {

    private String status = "CONTINUE";
    private String summary = "";
    private List<String> steps = new ArrayList<>();
    private List<String> toolHints = new ArrayList<>();

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getSteps() {
        return steps;
    }

    public void setSteps(List<String> steps) {
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
    }

    public List<String> getToolHints() {
        return toolHints;
    }

    public void setToolHints(List<String> toolHints) {
        this.toolHints = toolHints == null ? new ArrayList<>() : new ArrayList<>(toolHints);
    }

    public boolean ready() {
        return "READY".equals(normalizedStatus());
    }

    public boolean degraded() {
        return false;
    }

    public String planText() {
        List<String> lines = new ArrayList<>();
        if (StringUtils.hasText(summary)) {
            lines.add(summary.trim());
        }
        if (steps != null && !steps.isEmpty()) {
            for (int i = 0; i < steps.size(); i++) {
                String step = steps.get(i);
                if (StringUtils.hasText(step)) {
                    lines.add((i + 1) + ". " + step.trim());
                }
            }
        }
        if (toolHints != null && !toolHints.isEmpty()) {
            lines.add("工具说明:");
            for (String toolHint : toolHints) {
                if (StringUtils.hasText(toolHint)) {
                    lines.add("- " + toolHint.trim());
                }
            }
        }
        if (lines.isEmpty()) {
            return ready() ? "当前信息已足够，总结回答即可。" : "继续围绕用户问题收集信息并行动。";
        }
        return String.join("\n", lines).trim();
    }

    private String normalizedStatus() {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "CONTINUE";
    }
}
