package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.TaskDetail;
import com.dormrepair.remindme.enums.FinalStatus;
import com.dormrepair.remindme.enums.TaskStatus;
import com.dormrepair.remindme.utils.TaskSummaryUtils;
import com.dormrepair.remindme.utils.TaskSummaryUtils.SummaryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// ReplyTemplateService + TaskSummaryUtils 单元测试
// 覆盖 5 种最终状态场景：全部新建 / 部分覆盖 / 全部覆盖 / 部分跳过 / 部分失败
class ReplyTemplateServiceTest {

    private final ReplyTemplateService service = new ReplyTemplateService();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TaskDetail task(int idx, String title, String time, TaskStatus status, boolean hasConflict) {
        return TaskDetail.builder()
                .index(idx)
                .title(title)
                .remindTime(LocalDateTime.parse(time, FMT))
                .location(null)
                .priority("NORMAL")
                .status(status)
                .hasConflict(hasConflict)
                .conflictOriginalTitle(hasConflict ? "去超市" : null)
                .message(hasConflict ? "已覆盖原有提醒「去超市」" : null)
                .build();
    }

    @Test
    @DisplayName("全部新建 → ALL_SUCCESS / reply: ALL_CONFIRMED")
    void testAllSuccess() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.CONFIRMED, false),
                task(1, "去地铁站", "2026-08-26 08:00:00", TaskStatus.CONFIRMED, false)
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.finalStatus).isEqualTo(FinalStatus.ALL_SUCCESS);
        assertThat(summary.replyFinalStatus).isEqualTo(FinalStatus.ALL_CONFIRMED);
        assertThat(summary.cancelledCount).isEqualTo(0);

        String reply = service.buildReply("七点半买可乐，八点去地铁站", tasks);
        assertThat(reply).startsWith("✅ 已创建 2 项提醒：");
        assertThat(reply).contains("届时会准时通知你。");
        assertThat(reply).contains("买可口可乐");
        assertThat(reply).contains("去地铁站");
    }

    @Test
    @DisplayName("部分覆盖 → PARTIAL_OVERWRITE / reply: ALL_CONFIRMED")
    void testPartialOverwrite() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.CONFIRMED, false),
                task(1, "去地铁站", "2026-08-26 08:00:00", TaskStatus.OVERWRITTEN, true)
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.finalStatus).isEqualTo(FinalStatus.PARTIAL_OVERWRITE);
        assertThat(summary.replyFinalStatus).isEqualTo(FinalStatus.ALL_CONFIRMED); // 交互维度：都是"创建类"
        assertThat(summary.successCount).isEqualTo(1);
        assertThat(summary.overwrittenCount).isEqualTo(1);

        String reply = service.buildReply(null, tasks);
        assertThat(reply).startsWith("✅ 已创建 2 项提醒：");
        assertThat(reply).contains("📝");
        assertThat(reply).contains("已覆盖原有提醒");
        assertThat(reply).contains("届时会准时通知你。");
    }

    @Test
    @DisplayName("全部覆盖 → ALL_OVERWRITTEN / reply: ALL_CONFIRMED")
    void testAllOverwritten() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.OVERWRITTEN, true),
                task(1, "去地铁站", "2026-08-26 08:00:00", TaskStatus.OVERWRITTEN, true)
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.finalStatus).isEqualTo(FinalStatus.ALL_OVERWRITTEN);
        assertThat(summary.replyFinalStatus).isEqualTo(FinalStatus.ALL_CONFIRMED);

        String reply = service.buildReply(null, tasks);
        assertThat(reply).startsWith("✅ 已创建 2 项提醒：");
    }

    @Test
    @DisplayName("部分跳过 → PARTIAL_SKIPPED / reply: PARTIAL_CONFIRM_SKIP")
    void testPartialSkipped() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.CONFIRMED, false),
                task(1, "去地铁站", "2026-08-26 08:00:00", TaskStatus.SKIPPED, false)
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.finalStatus).isEqualTo(FinalStatus.PARTIAL_SKIPPED);
        assertThat(summary.replyFinalStatus).isEqualTo(FinalStatus.PARTIAL_CONFIRM_SKIP);
        assertThat(summary.successCount).isEqualTo(1);
        assertThat(summary.skippedCount).isEqualTo(1);

        String reply = service.buildReply(null, tasks);
        assertThat(reply).startsWith("✅ 已创建 1 项提醒：");
        assertThat(reply).contains("⏭️ 已跳过 1 项：");
        assertThat(reply).contains("⏭️");
        assertThat(reply).contains("如需调整，随时告诉我就好～");
    }

    @Test
    @DisplayName("部分失败 → PARTIAL_FAILED")
    void testPartialFailed() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.CONFIRMED, false),
                TaskDetail.builder()
                        .index(1)
                        .title("去地铁站")
                        .remindTime(LocalDateTime.parse("2026-08-26 08:00:00", FMT))
                        .priority("NORMAL")
                        .status(TaskStatus.FAILED)
                        .hasConflict(false)
                        .message("数据库写入失败")
                        .build()
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.finalStatus).isEqualTo(FinalStatus.PARTIAL_FAILED);
        assertThat(summary.replyFinalStatus).isEqualTo(FinalStatus.PARTIAL_FAILED);
        assertThat(summary.failedCount).isEqualTo(1);

        String reply = service.buildReply(null, tasks);
        assertThat(reply).startsWith("⚠️ 已创建 1 项提醒，1 项创建失败：");
        assertThat(reply).contains("1 项创建失败");
        assertThat(reply).contains("⚠️");
    }

    @Test
    @DisplayName("全部取消 → ALL_CANCELLED（CANCELLED 交互态）")
    void testAllCancelled() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.CANCELLED, false),
                task(1, "去地铁站", "2026-08-26 08:00:00", TaskStatus.CANCELLED, false)
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.replyFinalStatus).isEqualTo(FinalStatus.ALL_CANCELLED);
        assertThat(summary.cancelledCount).isEqualTo(2);

        String reply = service.buildReply(null, tasks);
        assertThat(reply).startsWith("❌ 已取消所有提醒，未保存任何内容。");
        assertThat(reply).contains("如需重新设置，随时告诉我就好～");
        assertThat(reply).doesNotContain("届时会准时通知你");
    }

    @Test
    @DisplayName("全部跳过 → ALL_SKIPPED")
    void testAllSkipped() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.SKIPPED, false),
                task(1, "去地铁站", "2026-08-26 08:00:00", TaskStatus.SKIPPED, false)
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.replyFinalStatus).isEqualTo(FinalStatus.ALL_SKIPPED);
        assertThat(summary.skippedCount).isEqualTo(2);

        String reply = service.buildReply(null, tasks);
        assertThat(reply).startsWith("⏭️ 已跳过所有提醒，未创建任何内容。");
        assertThat(reply).contains("如需重新设置，随时告诉我就好～");
    }

    @Test
    @DisplayName("部分确认 + 部分取消 → PARTIAL_CONFIRM_CANCEL")
    void testPartialConfirmCancel() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.CONFIRMED, false),
                task(1, "去地铁站", "2026-08-26 08:00:00", TaskStatus.CANCELLED, false)
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.replyFinalStatus).isEqualTo(FinalStatus.PARTIAL_CONFIRM_CANCEL);
        assertThat(summary.cancelledCount).isEqualTo(1);

        String reply = service.buildReply(null, tasks);
        assertThat(reply).startsWith("✅ 已创建 1 项提醒：");
        assertThat(reply).contains("❌ 已取消 1 项：");
        assertThat(reply).doesNotContain("❌ 已取消所有提醒");
        assertThat(reply).contains("如需调整，随时告诉我就好～");
    }

    @Test
    @DisplayName("回复按状态分段，不再使用旧的统一开头")
    void testNoOldGenericPrefix() {
        // 确认类场景：不应再出现旧的统一文案
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.CONFIRMED, false)
        );
        String reply = service.buildReply("买可乐", tasks);
        assertThat(reply).doesNotContain("⏰ 好的，已按你的要求完成设置");
    }
}
