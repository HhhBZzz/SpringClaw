package com.springclaw.service.agent.kernel;

import com.springclaw.tool.pack.FileToolPack;
import com.springclaw.tool.pack.LocalFilesystemToolPack;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.WorkspaceEditToolPack;

/**
 * 模型调用安全策略(spec §3.2 K 段收敛):工具集中含写类工具包时禁止
 * provider failover 重试,防止副作用重放(同一次 agent 步骤换 provider 重跑
 * 会把文件写入/脚本执行重复一遍)。
 *
 * <p>此前 6 个引擎各持一份私有拷贝,且已漂移成两套工具名单——5 处查
 * {@code WorkspaceEditToolPack + ScriptSkillToolPack},OparLoop 查
 * {@code FileToolPack + LocalFilesystemToolPack + ScriptSkillToolPack}。
 * 本类取并集统一:四类都是写副作用工具包,并集对所有引擎都是收紧方向
 * (只会更少允许 failover,不会放宽)。</p>
 */
public final class ModelCallSafety {

    private ModelCallSafety() {
    }

    /**
     * 工具集是否可安全重试(failover 换 provider 重跑)。
     * null/空集视为安全(纯读或无工具调用)。
     */
    public static boolean isSafeToRetry(Object[] tools) {
        if (tools == null || tools.length == 0) {
            return true;
        }
        for (Object tool : tools) {
            if (tool instanceof WorkspaceEditToolPack
                    || tool instanceof FileToolPack
                    || tool instanceof LocalFilesystemToolPack
                    || tool instanceof ScriptSkillToolPack) {
                return false;
            }
        }
        return true;
    }
}
