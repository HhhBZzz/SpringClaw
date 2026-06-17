package com.springclaw.service.proposal;

/**
 * 工具调用接口。所有实现必须保证调用路径会被 ToolRuntimeAspect 拦截（不变量 11 执行侧）。
 * 不允许直接反射目标 bean 的 Method 绕过 Spring proxy。
 */
public interface ToolInvoker {
    Object invoke(String toolName, String argumentsCanonicalJson);
}