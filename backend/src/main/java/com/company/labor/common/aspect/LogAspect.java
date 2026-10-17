package com.company.labor.common.aspect;

import com.company.labor.common.util.DesensitizeUtil;
import com.company.labor.common.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

/**
 * 日志切面
 *
 * <p>自动记录所有Controller方法的：
 * <ul>
 *   <li>请求参数（脱敏后）</li>
 *   <li>返回结果（脱敏后）</li>
 *   <li>执行耗时</li>
 *   <li>异常信息</li>
 * </ul>
 *
 * <p>日志示例：
 * <pre>
 * [EmployeeController.getById] 请求参数：{"id":1001}
 * [EmployeeController.getById] 返回结果：{"code":200,"data":{...}}
 * [EmployeeController.getById] 执行耗时：15ms
 * </pre>
 *
 * <p>安全要求：
 * <ul>
 *   <li>密码、身份证、银行卡等敏感信息必须脱敏</li>
 *   <li>不能记录大文件内容（如上传的文件）</li>
 * </ul>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@Slf4j
@Aspect
@Component
public class LogAspect {

    /**
     * 切入点：所有Controller
     */
    @Pointcut("execution(* com.company.labor..controller..*.*(..))")
    public void controllerPointcut() {
    }

    /**
     * 环绕通知：记录请求参数、返回结果、执行耗时
     *
     * @param joinPoint 切入点
     * @return 返回结果
     * @throws Throwable 异常
     */
    @Around("controllerPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = method.getName();
        String fullMethodName = className + "." + methodName;

        // 记录请求参数
        logRequestParams(fullMethodName, method, joinPoint.getArgs());

        // 记录开始时间
        long startTime = System.currentTimeMillis();

        Object result = null;
        try {
            // 执行目标方法
            result = joinPoint.proceed();

            // 记录返回结果
            logResponse(fullMethodName, result);

            return result;

        } catch (Throwable e) {
            // 记录异常信息
            logException(fullMethodName, e);
            throw e;

        } finally {
            // 记录执行耗时
            long endTime = System.currentTimeMillis();
            long costTime = endTime - startTime;
            logCostTime(fullMethodName, costTime);
        }
    }

    /**
     * 记录请求参数
     *
     * @param fullMethodName 完整方法名
     * @param method 方法
     * @param args 参数值
     */
    private void logRequestParams(String fullMethodName, Method method, Object[] args) {
        // 获取请求信息
        HttpServletRequest request = getHttpServletRequest();
        if (request != null) {
            log.info("[{}] 请求URL：{}", fullMethodName, request.getRequestURI());
        }

        // 获取参数名和参数值
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0 || args.length == 0) {
            log.info("[{}] 请求参数：无", fullMethodName);
            return;
        }

        // 构建参数Map
        Map<String, Object> paramMap = new HashMap<>();
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            String paramName = parameters[i].getName();
            Object paramValue = args[i];

            // 跳过HttpServletRequest、HttpServletResponse等Servlet对象
            if (isServletObject(paramValue)) {
                continue;
            }

            // 脱敏敏感参数
            paramValue = desensitizeIfNeeded(paramName, paramValue);

            paramMap.put(paramName, paramValue);
        }

        // 转JSON输出
        String paramsJson = JsonUtil.toJson(paramMap);
        log.info("[{}] 请求参数：{}", fullMethodName, paramsJson);
    }

    /**
     * 记录返回结果
     *
     * @param fullMethodName 完整方法名
     * @param result 返回结果
     */
    private void logResponse(String fullMethodName, Object result) {
        if (result == null) {
            log.info("[{}] 返回结果：null", fullMethodName);
            return;
        }

        // 转JSON输出（限制长度，避免日志过大）
        String resultJson = JsonUtil.toJson(result);
        if (resultJson != null && resultJson.length() > 1000) {
            resultJson = resultJson.substring(0, 1000) + "...(truncated)";
        }
        log.info("[{}] 返回结果：{}", fullMethodName, resultJson);
    }

    /**
     * 记录异常信息
     *
     * @param fullMethodName 完整方法名
     * @param e 异常
     */
    private void logException(String fullMethodName, Throwable e) {
        log.error("[{}] 执行异常：{}", fullMethodName, e.getMessage(), e);
    }

    /**
     * 记录执行耗时
     *
     * @param fullMethodName 完整方法名
     * @param costTime 耗时（毫秒）
     */
    private void logCostTime(String fullMethodName, long costTime) {
        if (costTime > 1000) {
            // 超过1秒，记录WARN日志
            log.warn("[{}] 执行耗时：{}ms（超过1秒）", fullMethodName, costTime);
        } else {
            log.info("[{}] 执行耗时：{}ms", fullMethodName, costTime);
        }
    }

    /**
     * 如果是敏感参数，进行脱敏处理
     *
     * @param paramName 参数名
     * @param paramValue 参数值
     * @return 脱敏后的值
     */
    private Object desensitizeIfNeeded(String paramName, Object paramValue) {
        if (paramValue == null) {
            return null;
        }

        String paramNameLower = paramName.toLowerCase();

        // 密码字段不输出任何信息
        if (paramNameLower.contains("password")) {
            return "***";
        }

        // 字符串类型才脱敏
        if (!(paramValue instanceof String)) {
            return paramValue;
        }

        String valueStr = (String) paramValue;

        // 身份证字段脱敏
        if (paramNameLower.contains("idcard")) {
            return DesensitizeUtil.idCard(valueStr);
        }

        // 手机号字段脱敏
        if (paramNameLower.contains("mobile") || paramNameLower.contains("phone")) {
            return DesensitizeUtil.mobile(valueStr);
        }

        // 银行卡字段脱敏
        if (paramNameLower.contains("bankcard")) {
            return DesensitizeUtil.bankCard(valueStr);
        }

        return paramValue;
    }

    /**
     * 判断是否是Servlet对象
     *
     * @param obj 对象
     * @return true是，false否
     */
    private boolean isServletObject(Object obj) {
        if (obj == null) {
            return false;
        }
        String className = obj.getClass().getName();
        return className.startsWith("javax.servlet")
                || className.startsWith("jakarta.servlet")
                || className.startsWith("org.springframework.web.multipart");
    }

    /**
     * 获取HttpServletRequest
     *
     * @return HttpServletRequest
     */
    private HttpServletRequest getHttpServletRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }
}
