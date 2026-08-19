package com.springclaw.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子进程环境清洗契约测试(dsh defensive-patterns: "Never hand untrusted output
 * the ambient environment"——spawn 命令必须剥离凭证类变量,防止 harness 凭证
 * 经 env/输出/泄漏进模型上下文)。
 */
class SubprocessEnvironmentSanitizerTest {

    @Test
    void stripsCredentialShapedVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin");
        env.put("HOME", "/Users/test");
        env.put("SPRINGCLAW_PRIMARY_API_KEY", "sk-secret");
        env.put("SPRINGCLAW_DEEPSEEK_API_KEY", "sk-ds");
        env.put("OPENCLAW_PRIMARY_API_KEY", "sk-legacy");
        env.put("MYSQL_PASSWORD", "db-pass");
        env.put("SPRINGCLAW_WEBHOOK_SECRET", "wh-secret");
        env.put("SPRINGCLAW_FEISHU_APP_SECRET", "fs-secret");
        env.put("GITHUB_TOKEN", "gh-token");
        env.put("MY_API_TOKEN", "t");
        env.put("SOME_SECRET_VALUE", "s");
        env.put("AWS_ACCESS_KEY_ID", "aws");

        Map<String, String> cleaned = SubprocessEnvironmentSanitizer.sanitize(env);

        assertThat(cleaned).containsEntry("PATH", "/usr/bin").containsEntry("HOME", "/Users/test");
        assertThat(cleaned)
                .doesNotContainKeys(
                        "SPRINGCLAW_PRIMARY_API_KEY",
                        "SPRINGCLAW_DEEPSEEK_API_KEY",
                        "OPENCLAW_PRIMARY_API_KEY",
                        "MYSQL_PASSWORD",
                        "SPRINGCLAW_WEBHOOK_SECRET",
                        "SPRINGCLAW_FEISHU_APP_SECRET",
                        "GITHUB_TOKEN",
                        "MY_API_TOKEN",
                        "SOME_SECRET_VALUE",
                        "AWS_ACCESS_KEY_ID");
    }

    @Test
    void allowsSpringclawScriptRootAndBenignRuntimeVars() {
        Map<String, String> env = new HashMap<>();
        env.put("SPRINGCLAW_WORKSPACE_ROOT", "/ws");
        env.put("SPRINGCLAW_SKILL_NAME", "code-analysis");
        env.put("OPENCLAW_SKILL_ROOT", "/skills");
        env.put("PYTHONPATH", "/lib");
        env.put("LANG", "en_US.UTF-8");

        Map<String, String> cleaned = SubprocessEnvironmentSanitizer.sanitize(env);

        // 脚本技能运行时变量(ROOT/SCRIPT/SKILL)是白名单语义,保留
        assertThat(cleaned)
                .containsEntry("SPRINGCLAW_WORKSPACE_ROOT", "/ws")
                .containsEntry("SPRINGCLAW_SKILL_NAME", "code-analysis")
                .containsEntry("OPENCLAW_SKILL_ROOT", "/skills")
                .containsEntry("PYTHONPATH", "/lib")
                .containsEntry("LANG", "en_US.UTF-8");
    }

    @Test
    void appliedToProcessBuilderClearsCredentialEntries() {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "echo hi");
        pb.environment().put("SPRINGCLAW_PRIMARY_API_KEY", "sk-secret");
        pb.environment().put("PATH", "/usr/bin");

        SubprocessEnvironmentSanitizer.applyTo(pb);

        assertThat(pb.environment()).doesNotContainKey("SPRINGCLAW_PRIMARY_API_KEY");
        assertThat(pb.environment()).containsKey("PATH");
    }

    @Test
    void nullSafeOnMissingEnvironment() {
        Map<String, String> cleaned = SubprocessEnvironmentSanitizer.sanitize(null);
        assertThat(cleaned).isEmpty();
    }
}
