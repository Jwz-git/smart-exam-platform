package com.smartexam.common;

import java.util.UUID;

/**
 * 成功响应的统一包装：{@code {"data": ..., "requestId": "..."}}。
 *
 * <p>所有正常返回都套一层 {@code data}，前端只需固定读取该字段，后续给响应加分页信息或元数据时
 * 不会破坏既有契约。{@code requestId} 便于把前端看到的报错和后端日志对应起来。
 *
 * <p>错误响应不使用本结构，而是由 {@link ApiExceptionHandler} 统一输出
 * {@code code / message / fieldErrors / requestId}，约定见 {@code docs/api.md} 第 1 节。
 *
 * @param data      业务数据
 * @param requestId 本次请求的追踪标识
 * @param <T>       业务数据类型
 */
public record ApiResponse<T>(T data, String requestId) {
    /** 包装一份业务数据，并自动生成请求标识。 */
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, UUID.randomUUID().toString());
    }
}
