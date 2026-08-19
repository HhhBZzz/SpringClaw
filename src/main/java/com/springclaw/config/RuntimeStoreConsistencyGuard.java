package com.springclaw.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.StringUtils;

/**
 * db-enabled 与 runtime.lifecycle.store 组合一致性校验:
 * 业务表落 MySQL 而 run 事件仅存内存的半持久状态是配置事故,fail-fast。
 */
public class RuntimeStoreConsistencyGuard implements InitializingBean {

    private final boolean dbEnabled;
    private final String lifecycleStore;

    public RuntimeStoreConsistencyGuard(boolean dbEnabled, String lifecycleStore) {
        this.dbEnabled = dbEnabled;
        this.lifecycleStore = lifecycleStore;
    }

    @Override
    public void afterPropertiesSet() {
        if (dbEnabled && !"mysql".equals(StringUtils.hasText(lifecycleStore)
                ? lifecycleStore.trim().toLowerCase() : "")) {
            throw new IllegalStateException(
                    "springclaw.persistence.db-enabled=true 时必须设置 "
                            + "springclaw.runtime.lifecycle.store=mysql(当前 store="
                            + lifecycleStore + "),否则 run 事件只存内存、"
                            + "重启即失,与业务持久化形成半持久状态。"
            );
        }
    }
}
