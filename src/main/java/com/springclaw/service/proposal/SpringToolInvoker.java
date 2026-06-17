package com.springclaw.service.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.tool.runtime.CapabilityRegistry;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 解析 toolName → 找到 ToolPack 代理 bean → 反射调用 @Tool 方法。
 * 因为 bean 是 CGLIB 代理，反射调用会触发 ToolRuntimeAspect.aroundTool（不变量 11 执行侧）。
 */
@Component
public class SpringToolInvoker implements ToolInvoker {

    private final CapabilityRegistry capabilityRegistry;
    private final ObjectMapper mapper;

    @Autowired
    public SpringToolInvoker(CapabilityRegistry capabilityRegistry, ObjectMapper mapper) {
        this.capabilityRegistry = capabilityRegistry;
        this.mapper = mapper;
    }

    @Override
    public Object invoke(String toolName, String argumentsCanonicalJson) {
        // toolName 形如 "WorkspaceEditToolPack.workspaceWriteFile"
        int dot = toolName.indexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("toolName 格式错误: " + toolName);
        }
        String simpleClass = toolName.substring(0, dot);
        String methodName = toolName.substring(dot + 1);

        Object proxyBean = capabilityRegistry.findToolPackBeanByClassName(simpleClass);
        if (proxyBean == null) {
            throw new IllegalArgumentException("toolPack 不存在: " + simpleClass);
        }
        Class<?> targetClass = AopUtils.getTargetClass(proxyBean);
        Method targetMethod = findToolMethod(targetClass, methodName);
        Object[] args = decodeArgs(argumentsCanonicalJson, targetMethod);

        try {
            // 反射代理对象 → 触发 Spring AOP @annotation 切面
            return targetMethod.invoke(proxyBean, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(cause == null ? ex : cause);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException("invoke @Tool method failed: " + toolName, ex);
        }
    }

    private Method findToolMethod(Class<?> clazz, String methodName) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName)
                    && m.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) {
                return m;
            }
        }
        throw new IllegalArgumentException(
                "@Tool 方法未找到: " + clazz.getSimpleName() + "." + methodName);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object[] decodeArgs(String canonicalJson, Method method) {
        try {
            // canonicalJson 是 ["arg0", "arg1", ...] 形式（positional args）
            List raw = mapper.readValue(canonicalJson, List.class);
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                args[i] = i < raw.size() ? mapper.convertValue(raw.get(i), paramTypes[i]) : null;
            }
            return args;
        } catch (Exception ex) {
            throw new RuntimeException("decode args failed: " + canonicalJson, ex);
        }
    }
}