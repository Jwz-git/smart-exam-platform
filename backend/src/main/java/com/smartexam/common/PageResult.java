package com.smartexam.common;

import java.util.List;

/**
 * 分页查询结果。字段与 {@code docs/api.md} 约定一致：{@code page} 从 1 开始，{@code total} 是
 * 满足筛选条件的总条数（不是当前页条数），前端据此渲染页码。
 *
 * @param items 当前页数据
 * @param page  当前页码，从 1 开始
 * @param size  每页条数
 * @param total 符合条件的总条数
 * @param <T>   列表元素类型
 */
public record PageResult<T>(List<T> items, int page, int size, long total) {}
