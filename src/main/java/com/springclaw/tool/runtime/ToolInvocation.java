package com.springclaw.tool.runtime;

/**
 * 一次工具调用的中性描述(管线各段只依赖此抽象,不依赖 AOP 类型)。
 *
 * @param toolName 运行时工具名(含 script skill 归一化后缀)
 * @param args     原始入参
 */
public interface ToolInvocation {

    String toolName();

    Object[] args();
}
