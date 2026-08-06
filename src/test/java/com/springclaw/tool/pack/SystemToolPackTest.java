package com.springclaw.tool.pack;

import com.springclaw.common.exception.BusinessException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SystemToolPackTest {

    @Test
    void runCommand_rejectsUnsafeCharactersBeforeStartingProcess() {
        // ApprovedSystemCommand 现在是 unsafe 字符护栏（不再硬编码只认 git status）；
        // 命令白名单由 allowed-commands 决定。含 ; 等注入字符仍被拦在执行前。
        SystemToolPack toolPack = new SystemToolPack(true, "whitelist", "echo,pwd,git", "", 5, 2000);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> toolPack.runCommand("echo hello; rm -rf /"));

        Assertions.assertEquals(40062, ex.getCode());
        Assertions.assertTrue(ex.getMessage().contains("不允许的字符"));
    }
}
