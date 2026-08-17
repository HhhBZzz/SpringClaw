package com.springclaw.common.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 子进程环境清洗(dsh defensive-patterns: "Never hand untrusted output the
 * ambient environment")——所有经 ProcessBuilder/Runtime spawn 的子进程
 * 必须先清洗环境,防止 harness 凭证(API key/密码/secret/token)经
 * {@code env} 命令、报错输出、临时文件进入模型上下文。
 *
 * <p>规则:名称命中 KEY/SECRET/TOKEN/PASSWORD 任意词(大小写不敏感,
 * 下划线/连字符分隔)的环境变量一律剥离。白名单语义的运行时变量
 * (SPRINGCLAW_/OPENCLAW_ 前缀的 ROOT/SCRIPT/SKILL/NAME)不含这些词,天然保留。</p>
 */
public final class SubprocessEnvironmentSanitizer {

    private static final Pattern CREDENTIAL_SHAPED =
            Pattern.compile("(?i)(?:^|[_-])(?:KEY|KEYS|SECRET|SECRETS|TOKEN|TOKENS|PASSWORD|PASSWD|PWD)(?:$|[_-])");

    private SubprocessEnvironmentSanitizer() {
    }

    /** 返回剥离凭证类变量后的环境副本;入参为 null 返回空 map。 */
    public static Map<String, String> sanitize(Map<String, String> env) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        if (env == null) {
            return cleaned;
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey() == null || CREDENTIAL_SHAPED.matcher(entry.getKey()).find()) {
                continue;
            }
            cleaned.put(entry.getKey(), entry.getValue());
        }
        return cleaned;
    }

    /** 就地清洗 ProcessBuilder 的 environment()。 */
    public static void applyTo(ProcessBuilder processBuilder) {
        if (processBuilder == null) {
            return;
        }
        Map<String, String> env = processBuilder.environment();
        env.keySet().removeIf(SubprocessEnvironmentSanitizer::isCredentialShaped);
    }

    /** 变量名是否凭证形态(含 KEY/SECRET/TOKEN/PASSWORD 词)。 */
    public static boolean isCredentialShaped(String name) {
        return name != null && CREDENTIAL_SHAPED.matcher(name).find();
    }
}
