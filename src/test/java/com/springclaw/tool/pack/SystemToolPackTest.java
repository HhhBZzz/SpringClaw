package com.springclaw.tool.pack;

import com.springclaw.common.exception.BusinessException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SystemToolPackTest {

    @Test
    void runCommand_rejectsUnsupportedGitSubcommandBeforeStartingProcess() {
        SystemToolPack toolPack = new SystemToolPack(true, "whitelist", "echo,pwd,git", "", 5, 2000);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> toolPack.runCommand("git config --get user.name"));

        Assertions.assertEquals(40062, ex.getCode());
    }
}
