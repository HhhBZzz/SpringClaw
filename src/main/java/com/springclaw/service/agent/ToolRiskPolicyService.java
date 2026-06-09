package com.springclaw.service.agent;

import com.springclaw.common.util.TextUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Classifies whether a request can run automatically or must ask for confirmation first.
 */
@Service
public class ToolRiskPolicyService {

    public String classifyRisk(String text) {
        String lower = TextUtils.normalize(text);
        if (!StringUtils.hasText(lower)) {
            return "read";
        }
        if (TextUtils.containsAny(lower,
                "rm -rf", "格式化", "清空磁盘", "删除所有", "删库", "drop table", "shutdown", "kill -9",
                "执行命令", "shell", "终端命令", "run command", "system.exit")) {
            return "dangerous";
        }
        if (TextUtils.containsAny(lower,
                "发到飞书", "推送", "发送邮件", "发邮件", "外部发送", "创建定时", "定时任务", "每天", "每周", "每月", "cron")) {
            return "side_effect";
        }
        if (TextUtils.containsAny(lower,
                "写入", "保存到", "修改", "覆盖", "新增文件", "创建文件", "删除文件", "移动文件", "重命名")) {
            return "write";
        }
        return "read";
    }

    public boolean requiresConfirmation(String riskLevel) {
        return "write".equalsIgnoreCase(riskLevel)
                || "side_effect".equalsIgnoreCase(riskLevel)
                || "dangerous".equalsIgnoreCase(riskLevel);
    }

    public boolean canConfirmDangerous(String roleCode) {
        String role = roleCode == null ? "" : roleCode.trim().toUpperCase(Locale.ROOT);
        return "ADMIN".equals(role) || "DEVELOPER".equals(role);
    }

}
