package com.dormrepair.remindme.enums;

// 提醒生命周期状态枚举。对应 reminders.status 字段，存储为 VARCHAR(20)，默认值 PENDING；标记单条提醒当前所处阶段
public enum ReminderStatus {

    // 【P2-3 语义：提醒生命周期级·待推送】
    // 归属阶段：提醒已创建且已确认（confirmStatus=CONFIRMED），但当前时间尚未到达推送触发时刻。
    // 等待 SchedulerService 扫描写入推送队列。
    // 与 ExecutionStatus.PENDING（已经推送了，等用户点"已执行"）区分：ReminderStatus.PENDING 发生在推送前。
    PENDING("待推送"),
    // 已推送待确认：调度器已推送执行卡片，等待用户确认/稍后/取消，不会重复推送
    REMINDED("已推送待确认"),
    // 已推送：推送动作已执行（收到渠道 ACK），等待后续执行确认或完成
    SENT("已推送"),
    // 已完成：用户已确认执行或到点后被自动标记完成，进入归档态
    COMPLETED("已完成"),
    // 已取消：用户主动取消，提醒不再推送
    CANCELLED("已取消"),
    // 已过期：提醒时间已过且未被处理，调度器扫描后标记为过期
    EXPIRED("已过期");

    // 中文展示名，用于日志输出、响应体中返回给调用方的可读文案
    private final String displayName;

    // 构造函数：把中文展示名绑定到对应枚举常量
    ReminderStatus(String displayName) {
        this.displayName = displayName;
    }

    // 返回枚举常量绑定的中文展示名
    public String getDisplayName() {
        return displayName;
    }

    // 根据中文展示名反查枚举常量，找不到返回 null
    public static ReminderStatus fromDisplayName(String displayName) {
        for (ReminderStatus value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
