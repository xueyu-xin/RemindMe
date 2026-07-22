package com.dormrepair.remindme.enums;

/**
 * 执行状态
 * <p>
 * 对应数据库字段：reminders.execution_status
 * 存储类型：VARCHAR(20)
 * 默认值：'PENDING'
 * </p>
 */
public enum ExecutionStatus {

    PENDING("待执行确认"),
    CONFIRMED("已确认执行"),
    CANCELLED("已取消执行"),
    DEFAULTED("默认确认执行");

    private final String displayName;

    ExecutionStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
