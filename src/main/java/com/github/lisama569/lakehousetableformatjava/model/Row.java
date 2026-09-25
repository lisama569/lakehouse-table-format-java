package com.github.lisama569.lakehousetableformatjava.model;

import java.util.Map;

/**
 * 一行数据：列 ID -> 值。数据文件与查询行均以列 ID 为键，
 * 因此列改名后历史文件仍能按当前 schema 正确解释。
 */
public record Row(Map<Integer, Object> values) {
}
