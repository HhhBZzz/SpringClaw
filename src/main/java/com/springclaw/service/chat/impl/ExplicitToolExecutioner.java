package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 显式 Action 文本执行器——从模型文本输出解析 "Action: toolName(...)" 行并反射手动调用 @Tool 方法。
 * <p>从 {@link ReActEngine} 提取的 ReAct 手动循环主路径(行为不变):Thought+Action 文本 → 引擎执行工具 →
 * Observation。反射调用 Spring 代理 bean 时经 {@code ToolRuntimeAspect} AOP 拦截(权限/限流/审计 +
 * tracker 证据上报 + RunCoordinator TOOL_* emit)——前提是调用方已开启 {@link
 * com.springclaw.tool.runtime.ToolExecutionContextHolder} scope(ReAct/Plan-Execute 循环负责)。</p>
 * <p>解析失败 / 工具未找到 / 执行异常时返回错误说明字符串(不抛,保持循环推进,让 LLM 下一轮纠错)。</p>
 * <p>无状态 Spring bean——ReAct 与 Plan-Execute(Task 4)构造注入复用。</p>
 */
@Component
public class ExplicitToolExecutioner {

    private static final Logger log = LoggerFactory.getLogger(ExplicitToolExecutioner.class);

    /** 解析后的 Action:工具名 + 原始参数串(尚未拆分)。 */
    private record ParsedAction(String toolName, String rawArgs) {
    }

    /**
     * ReAct/Plan-Execute 手动循环主逻辑:从模型输出解析 Action 行,在 tools 中按名查找 @Tool 方法,
     * 手动调用(经 Spring AOP 代理 → ToolRuntimeAspect 审计 + tracker 证据上报),
     * 把结果作为 Observation 返回。
     * <p>解析失败 / 工具未找到 / 执行异常时返回错误说明字符串(不抛,保持循环推进,让 LLM 下一轮纠错)。</p>
     */
    public String execute(String thought, Object[] tools, String requestId) {
        String actionContent = findActionLine(thought);
        if (actionContent == null) {
            return "(Action 行解析失败)";
        }
        ParsedAction parsed = splitAction(actionContent);
        if (parsed == null) {
            return "(Action 格式无法解析: " + TextUtils.truncate(actionContent.trim(), 120) + ")";
        }
        Optional<ToolReflectionSupport.ToolMethod> target = ToolReflectionSupport.findToolMethod(tools, parsed.toolName());
        if (target.isEmpty()) {
            log.warn("手动循环未找到工具: tool={}, requestId={}", parsed.toolName(), requestId);
            return "(未找到工具: " + parsed.toolName() + ")";
        }
        ToolReflectionSupport.ToolMethod toolMethod = target.get();
        try {
            Object[] args = bindArguments(toolMethod.method(), parsed.rawArgs());
            toolMethod.method().setAccessible(true);
            // 反射调用 Spring 代理 bean → Java 虚拟分派到 CGLIB override → ToolRuntimeAspect @Around
            // 拦截(权限/限流/审计 + tracker 证据上报 + RunCoordinator TOOL_* emit)。
            Object result = toolMethod.method().invoke(toolMethod.bean(), args);
            String obs = result == null ? "(工具返回 null)" : String.valueOf(result);
            log.info("手动循环执行工具: tool={}, requestId={}, observationLen={}",
                    parsed.toolName(), requestId, obs.length());
            return TextUtils.truncate(obs, 400);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            log.warn("手动循环工具执行失败: tool={}, requestId={}, reason={}",
                    parsed.toolName(), requestId, cause.getMessage());
            return "(工具执行失败: " + cause.getClass().getSimpleName() + ": "
                    + TextUtils.truncate(String.valueOf(cause.getMessage()), 200) + ")";
        }
    }

    /**
     * 判定本轮模型输出是否发起了工具调用(即是否含 Action 行)。
     * <p>模型本轮输出含以 "Action:" 起首的行(经 markdown 归一化,容忍 {@code **Action:**} /
     * {@code - Action:} / {@code ## Action:} 等噪声)→ 视为发起工具调用。无 Action 行即最终答案,
     * 循环终止。驱动 ReAct/Plan-Execute 的 Thought-Action-Observation 多步循环。</p>
     */
    public boolean hasActionLine(String thought) {
        return findActionLine(thought) != null;
    }

    /**
     * 从模型输出提取 Action 描述(取首个 "Action:" 行);无工具调用时标注为最终答案。
     * 用于 history/trace 投影。
     */
    public String describeAction(String thought, boolean hasToolCall) {
        if (!hasToolCall) {
            return "(无工具调用,给出最终答案)";
        }
        String content = findActionLine(thought);
        return TextUtils.truncate(content.trim(), 400);
    }

    /**
     * 扫描模型输出,返回首个 "Action:" 行**前缀之后的内容(取自原始行,未归一化)**。
     * <p>归一化({@link #stripMarkdownLinePrefix})仅用于"行首是不是 Action: 前缀"的**判定**
     * (容忍 {@code **Action:**} / {@code - Action:} / {@code ## Action:} 等噪声);
     * 内容必须从**原始行**取——{@code stripMarkdownLinePrefix} 会把 {@code *.*} → {@code .}
     * (italic 正则)、剥掉反引号,损坏 glob 通配符与 shell 反引号参数。
     * 归一化借鉴 {@link AutonomousLoopEngine#normalizeMarkerLine} 的前缀清洗,差异:
     * 不 lowercases 全行(参数需保大小写),只在比较前缀时局部 lowercase。</p>
     */
    private String findActionLine(String thought) {
        if (!StringUtils.hasText(thought)) return null;
        for (String line : thought.split("\n")) {
            String stripped = stripMarkdownLinePrefix(line);
            if (stripped == null) continue;
            if (stripped.toLowerCase(Locale.ROOT).startsWith("action:")) {
                return rawActionContent(line);
            }
        }
        return null;
    }

    /**
     * 从**原始行**(未归一化)提取 "Action:" 前缀之后的内容,保留参数里的 * / 反引号 / _ 原样。
     * <p>在原始行里(case-insensitive)找首个 {@code "action:"}——它对应归一化检测命中的前缀位置
     * (原始行首可能带 {@code **} / {@code -} / {@code #} 等 markdown 噪声,但首个 {@code "action:"}
     * 必是前缀,因为归一化检测已确认行首非噪声部分即 {@code "action:"})。然后跳过紧随前缀的闭合型
     * markdown 标记({@code *} / {@code _} / {@code `},如 {@code **Action:**} 右侧的 {@code **})与空白——
     * 这些是 "Action:" 关键字的格式包装,不是参数;参数必以工具名(标识符)起首,不会以这些字符开头,
     * 故安全跳过。</p>
     */
    private String rawActionContent(String rawLine) {
        String lower = rawLine.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("action:");
        if (idx < 0) return rawLine.strip();
        int after = idx + "action:".length();
        while (after < rawLine.length() && isPrefixMarkdownCloser(rawLine.charAt(after))) {
            after++;
        }
        return rawLine.substring(after);
    }

    private boolean isPrefixMarkdownCloser(char c) {
        return c == '*' || c == '_' || c == '`' || Character.isWhitespace(c);
    }

    /**
     * 去掉行首 markdown 噪声(`、**、*、__、heading、列表符、数字列表)与两端空白。
     * 空行返回 null。保留内容大小写(Action 参数可能含大写)。
     */
    private String stripMarkdownLinePrefix(String line) {
        String s = line.strip();
        if (s.isEmpty()) return null;
        s = s.replace("`", "");
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1");  // **bold**
        s = s.replaceAll("\\*(.+?)\\*", "$1");        // *italic*
        s = s.replaceAll("__(.+?)__", "$1");           // __bold__
        s = s.strip();
        if (s.isEmpty()) return null;
        s = s.replaceAll("^#{1,6}\\s*", "");           // heading
        s = s.replaceAll("^[-*+]\\s+", "");            // list bullet
        s = s.replaceAll("^\\d+[.)]\\s+", "");         // numbered list
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    /**
     * 把 "search(query=\"q\")" 拆成 (toolName=search, rawArgs=query="q")。
     * 无括号的裸工具名(无参)也接受。格式不符返回 null。
     */
    private ParsedAction splitAction(String actionContent) {
        String s = actionContent.trim();
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        if (open <= 0 || close <= open) {
            // 无括号:仅当整体是一个裸标识符(无空白)时视为零参工具调用
            if (StringUtils.hasText(s) && !s.contains(" ") && s.matches("[\\w-]+")) {
                return new ParsedAction(s, "");
            }
            return null;
        }
        String name = s.substring(0, open).trim();
        String args = s.substring(open + 1, close).trim();
        if (!StringUtils.hasText(name)) return null;
        return new ParsedAction(name, args);
    }

    /**
     * 把原始参数串绑定到方法的形参,返回可反射 invoke 的 Object[]。
     * 支持:
     * <ul>
     *   <li>命名参数 {@code name="value"}(形参名经 {@code -parameters} 保留,按名匹配;)</li>
     *   <li>位置参数 {@code "value"} / 裸 token(按序填充空位)</li>
     *   <li>混合:命名找不到形参时回退位置绑定</li>
     *   <li>引号剥离 + String/int/long/boolean/double 类型转换</li>
     * </ul>
     * 顶层级逗号拆分(尊重 "..." 与 '...' 引号及 ()/[]/{})。缺省值:null(对象)/0false(原始)。
     */
    private Object[] bindArguments(Method method, String rawArgs) {
        Parameter[] params = method.getParameters();
        Object[] bound = new Object[params.length];
        if (params.length == 0 || !StringUtils.hasText(rawArgs)) {
            return fillDefaults(bound, params);
        }
        List<String> tokens = splitArgs(rawArgs);
        boolean anyNamed = false;
        for (String t : tokens) {
            if (nameValueSplitIndex(t) >= 0) { anyNamed = true; break; }
        }
        int pos = 0;
        for (String token : tokens) {
            int eq = anyNamed ? nameValueSplitIndex(token) : -1;
            if (eq >= 0) {
                String nm = token.substring(0, eq).trim();
                String val = token.substring(eq + 1).trim();
                int idx = findParamIndex(params, nm);
                if (idx >= 0) {
                    bound[idx] = parseValue(val, params[idx].getType());
                    continue;
                }
                // 命名但形参名不匹配(如未带 -parameters)→ 回退位置绑定(用 value)
                int slot = nextSlot(bound, pos);
                if (slot >= 0) {
                    bound[slot] = parseValue(val, params[slot].getType());
                    pos = slot + 1;
                }
            } else {
                int slot = nextSlot(bound, pos);
                if (slot >= 0) {
                    bound[slot] = parseValue(token, params[slot].getType());
                    pos = slot + 1;
                }
            }
        }
        return fillDefaults(bound, params);
    }

    /** 顶层级逗号拆分,尊重双引号/单引号与 ()/[]/{} 嵌套。 */
    private List<String> splitArgs(String rawArgs) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < rawArgs.length(); i++) {
            char c = rawArgs.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                cur.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                cur.append(c);
            } else if (c == ',' && depth == 0) {
                String t = cur.toString().trim();
                if (!t.isEmpty()) out.add(t);
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        String last = cur.toString().trim();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    /**
     * 返回 token 中分隔命名参数的 {@code =} 的索引;不是命名参数返回 -1。
     * 规则:= 左侧必须是纯标识符([A-Za-z0-9_-],避开 ==、&gt;=、&lt;=、!=)。
     */
    private int nameValueSplitIndex(String token) {
        if (token == null || token.isEmpty()) return -1;
        int eq = token.indexOf('=');
        if (eq <= 0) return -1;
        String lhs = token.substring(0, eq);
        if (lhs.endsWith(">") || lhs.endsWith("<") || lhs.endsWith("!")) return -1;
        for (int i = 0; i < lhs.length(); i++) {
            char c = lhs.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) return -1;
        }
        return eq;
    }

    private int findParamIndex(Parameter[] params, String name) {
        for (int i = 0; i < params.length; i++) {
            if (name.equalsIgnoreCase(params[i].getName())) return i;
        }
        return -1;
    }

    private int nextSlot(Object[] bound, int from) {
        for (int i = from; i < bound.length; i++) {
            if (bound[i] == null) return i;
        }
        return -1;
    }

    /** 剥引号 + 按 target 类型转换(String/int/long/boolean/double);其余按 String 返回。 */
    private Object parseValue(String value, Class<?> type) {
        String v = value == null ? "" : value.trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1);
        }
        if (type == String.class) return v;
        if (type == int.class || type == Integer.class) {
            try { return Integer.parseInt(v); } catch (Exception e) { return 0; }
        }
        if (type == long.class || type == Long.class) {
            try { return Long.parseLong(v); } catch (Exception e) { return 0L; }
        }
        if (type == boolean.class || type == Boolean.class) {
            return "true".equalsIgnoreCase(v) || "1".equals(v);
        }
        if (type == double.class || type == Double.class) {
            try { return Double.parseDouble(v); } catch (Exception e) { return 0d; }
        }
        return v;
    }

    private Object[] fillDefaults(Object[] bound, Parameter[] params) {
        for (int i = 0; i < bound.length; i++) {
            if (bound[i] == null) {
                bound[i] = primitiveDefault(params[i].getType());
            }
        }
        return bound;
    }

    private Object primitiveDefault(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == boolean.class) return false;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }
}
