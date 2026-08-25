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
    @DisplayName("全部新建 → ALL_SUCCESS")
    void testAllSuccess() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.CONFIRMED, false),
                task(1, "去地铁站", "2026-08-26 08:00:00", TaskStatus.CONFIRMED, false)
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.finalStatus).isEqualTo(FinalStatus.ALL_SUCCESS);

        String reply = service.buildReply("七点半买可乐，八点去地铁站", tasks);
        assertThat(reply).contains("2 项提醒均已创建成功");
        assertThat(reply).contains("✅");
        assertThat(reply).contains("买可口可乐");
        assertThat(reply).contains("去地铁站");
    }

    @Test
    @DisplayName("部分覆盖 → PARTIAL_OVERWRITE")
    void testPartialOverwrite() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.CONFIRMED, false),
                task(1, "去地铁站", "2026-08-26 08:00:00", TaskStatus.OVERWRITTEN, true)
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.finalStatus).isEqualTo(FinalStatus.PARTIAL_OVERWRITE);
        assertThat(summary.successCount).isEqualTo(1);
        assertThat(summary.overwrittenCount).isEqualTo(1);

        String reply = service.buildReply(null, tasks);
        assertThat(reply).contains("2 项提醒均已处理完成");
        assertThat(reply).contains("📝");
        assertThat(reply).contains("已覆盖原有提醒");
    }

    @Test
    @DisplayName("全部覆盖 → ALL_OVERWRITTEN")
    void testAllOverwritten() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.OVERWRITTEN, true),
                task(1, "去地铁站", "2026-08-26 08:00:00", TaskStatus.OVERWRITTEN, true)
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.finalStatus).isEqualTo(FinalStatus.ALL_OVERWRITTEN);

        String reply = service.buildReply(null, tasks);
        assertThat(reply).contains("2 项提醒均已覆盖更新");
    }

    @Test
    @DisplayName("部分跳过 → PARTIAL_SKIPPED")
    void testPartialSkipped() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.CONFIRMED, false),
                task(1, "去地铁站", "2026-08-26 08:00:00", TaskStatus.SKIPPED, false)
        );

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        assertThat(summary.finalStatus).isEqualTo(FinalStatus.PARTIAL_SKIPPED);
        assertThat(summary.successCount).isEqualTo(1);
        assertThat(summary.skippedCount).isEqualTo(1);

        String reply = service.buildReply(null, tasks);
        assertThat(reply).contains("1 项提醒已创建");
        assertThat(reply).contains("1 项已跳过");
        assertThat(reply).contains("⏭️");
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
        assertThat(summary.failedCount).isEqualTo(1);

        String reply = service.buildReply(null, tasks);
        assertThat(reply).contains("1 项提醒已创建");
        assertThat(reply).contains("1 项创建失败");
        assertThat(reply).contains("❌");
    }

    @Test
    @DisplayName("回复包含四段式结构")
    void testFourPartStructure() {
        List<TaskDetail> tasks = List.of(
                task(0, "买可口可乐", "2026-08-26 07:30:00", TaskStatus.CONFIRMED, false)
        );

        String reply = service.buildReply("买可乐", tasks);

        // 第一段：操作确认
        assertThat(reply).contains("⏰ 好的，已按你的要求完成设置");
        // 第二段：任务清单
        assertThat(reply).contains("1. 买可口可乐");
        // 第三段：结论行
        assertThat(reply).contains("1 项提醒均已创建成功");
        // 第四段：操作引导
        assertThat(reply).contains("如需修改或取消");
    }
}
