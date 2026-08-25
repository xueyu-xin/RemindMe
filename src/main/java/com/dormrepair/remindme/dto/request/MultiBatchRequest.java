package com.dormrepair.remindme.dto.request;

import lombok.Data;

import java.util.List;

// 多项事务批量操作请求 DTO。用于确认选中/取消选中/覆盖选中接口，传递要操作的条目索引列表
@Data
public class MultiBatchRequest {
    // 要操作的条目索引列表（从 0 开始，对应 multiItems 列表位置）
    private List<Integer> itemIds;
}
