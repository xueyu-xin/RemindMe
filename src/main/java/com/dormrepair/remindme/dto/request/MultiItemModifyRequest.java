package com.dormrepair.remindme.dto.request;

import com.dormrepair.remindme.dto.BaseReminderFields;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

// 多项事务场景下单条条目修改请求 DTO。用于 PUT /api/confirmations/multi/{sessionId}/item/{index}
@Data
@EqualsAndHashCode(callSuper = true)
public class MultiItemModifyRequest extends BaseReminderFields {
    // 提醒时间（必填，yyyy-MM-dd HH:mm:ss）
    @NotBlank(message = "提醒时间不能为空")
    private String remindTime;
    // 结束时间（可选）
    private String endTime;
    // 提前提醒分钟数（可选，默认 10）
    private Integer advanceMinutes;

    // 覆盖父类 getter：Bean Validation 走属性级 @NotBlank，不影响其他 DTO 的 title 可空契约
    @Override
    @NotBlank(message = "标题不能为空")
    public String getTitle() {
        return super.getTitle();
    }
}
