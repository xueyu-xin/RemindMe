/*
 Navicat Premium Dump SQL

 Source Server         : localhost_3306
 Source Server Type    : MySQL
 Source Server Version : 80400 (8.4.0)
 Source Host           : localhost:3306
 Source Schema         : reminder

 Target Server Type    : MySQL
 Target Server Version : 80400 (8.4.0)
 File Encoding         : 65001

 Date: 30/07/2026 22:28:43
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for confirmation_sessions
-- ----------------------------
DROP TABLE IF EXISTS `confirmation_sessions`;
CREATE TABLE `confirmation_sessions`  (
  `session_id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `created_at` datetime(6) NULL DEFAULT NULL,
  `expires_at` datetime(6) NULL DEFAULT NULL,
  `original_input` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `parsed_data` json NULL,
  `retry_count` int NULL DEFAULT NULL,
  `status` enum('CANCELLED','CONFIRMED','EXPIRED','PENDING') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `updated_at` datetime(6) NULL DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `attempt_count` int NULL DEFAULT NULL,
  `interval_minutes` int NULL DEFAULT NULL,
  `max_attempts` int NULL DEFAULT NULL,
  `reminder_id` bigint NULL DEFAULT NULL,
  `stage` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `parsed_advance_minutes` int NULL DEFAULT NULL,
  `parsed_location` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `parsed_priority` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `parsed_time` datetime(6) NULL DEFAULT NULL,
  `parsed_title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `parsed_end_time` datetime(6) NULL DEFAULT NULL,
  PRIMARY KEY (`session_id`) USING BTREE,
  INDEX `idx_user_status`(`user_id` ASC, `status` ASC) USING BTREE,
  INDEX `idx_expires_at`(`expires_at` ASC) USING BTREE,
  INDEX `idx_stage`(`stage` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for push_logs
-- ----------------------------
DROP TABLE IF EXISTS `push_logs`;
CREATE TABLE `push_logs`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志ID，主键',
  `reminder_id` bigint NOT NULL COMMENT '关联的提醒ID',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `pushed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '推送时间',
  `user_read` tinyint(1) NOT NULL DEFAULT 0 COMMENT '用户是否已读：1已读，0未读',
  `read_at` datetime NULL DEFAULT NULL COMMENT '已读时间',
  `push_channel` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEBSOCKET' COMMENT '推送渠道：WEBSOCKET/EMAIL/SMS',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_push_logs_user_pushed`(`user_id` ASC, `pushed_at` ASC) USING BTREE,
  INDEX `idx_push_logs_reminder`(`reminder_id` ASC) USING BTREE,
  CONSTRAINT `fk_push_logs_reminder_id` FOREIGN KEY (`reminder_id`) REFERENCES `reminders` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_push_logs_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '推送日志表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for recurring_instances
-- ----------------------------
DROP TABLE IF EXISTS `recurring_instances`;
CREATE TABLE `recurring_instances`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '实例ID',
  `recurring_id` bigint NOT NULL COMMENT '关联周期提醒ID',
  `reminder_id` bigint NOT NULL COMMENT '关联 reminders 表ID（实际创建的提醒）',
  `remind_date` date NOT NULL COMMENT '该实例对应的日期',
  `status` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_recurring_date`(`recurring_id` ASC, `remind_date` ASC) USING BTREE COMMENT '同一天不重复生成',
  UNIQUE INDEX `idx_recurring_date`(`recurring_id` ASC, `remind_date` ASC) USING BTREE,
  INDEX `idx_recurring_id`(`recurring_id` ASC) USING BTREE,
  INDEX `idx_reminder_id`(`reminder_id` ASC) USING BTREE,
  INDEX `idx_remind_date`(`remind_date` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  CONSTRAINT `recurring_instances_ibfk_1` FOREIGN KEY (`recurring_id`) REFERENCES `recurring_reminders` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `recurring_instances_ibfk_2` FOREIGN KEY (`reminder_id`) REFERENCES `reminders` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 793 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '周期提醒实例表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for recurring_reminders
-- ----------------------------
DROP TABLE IF EXISTS `recurring_reminders`;
CREATE TABLE `recurring_reminders`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '周期提醒ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提醒标题',
  `remind_time` time NOT NULL COMMENT '每天提醒时间（如 08:30:00）',
  `start_date` date NOT NULL COMMENT '周期开始日期',
  `end_date` date NOT NULL COMMENT '周期结束日期',
  `repeat_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DAILY' COMMENT '周期类型：DAILY/WEEKLY/MONTHLY',
  `location` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '地点',
  `priority` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `advance_minutes` int NULL DEFAULT 10 COMMENT '提前量（分钟）',
  `status` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_generated_date` date NULL DEFAULT NULL COMMENT '最后一次生成提醒的日期（用于断点续传）',
  `source` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `timezone` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_status`(`user_id` ASC, `status` ASC) USING BTREE,
  INDEX `idx_dates`(`start_date` ASC, `end_date` ASC) USING BTREE,
  INDEX `idx_repeat_type`(`repeat_type` ASC) USING BTREE,
  INDEX `idx_date_range`(`start_date` ASC, `end_date` ASC) USING BTREE,
  CONSTRAINT `recurring_reminders_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 261 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '周期提醒模板表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for reminders
-- ----------------------------
DROP TABLE IF EXISTS `reminders`;
CREATE TABLE `reminders`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '提醒ID，主键',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提醒事项',
  `remind_time` datetime NOT NULL COMMENT '计划推送时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '提醒结束时间（时间段提醒使用，NULL表示单个时间点）',
  `timezone` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `advance_minutes` int NOT NULL DEFAULT 10 COMMENT '提前量（分钟）',
  `cron_expression` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `location` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '地点',
  `priority` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NORMAL' COMMENT '优先级：LOW/NORMAL/HIGH',
  `status` enum('PENDING','SENT','CANCELLED','EXPIRED','COMPLETED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'PENDING',
  `confirm_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '确认状态：PENDING/CONFIRMED/CANCELLED/DEFAULTED',
  `confirm_attempts` int NOT NULL DEFAULT 0 COMMENT '已确认提醒次数，范围0-3',
  `last_confirm_remind_at` datetime NULL DEFAULT NULL COMMENT '最后一次确认提醒时间',
  `source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER_INPUT' COMMENT '来源：USER_INPUT/WEATHER_LINK/EXCEL_IMPORT/PERIOD_REMINDER',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `execution_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'PENDING' COMMENT '执行状态：PENDING/CONFIRMED/CANCELLED/DEFAULTED，用于阶段二',
  `note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备注/附加信息（如：带袋子、带伞等）',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_reminders_user_status_time`(`user_id` ASC, `status` ASC, `remind_time` ASC) USING BTREE,
  INDEX `idx_reminders_remind_time`(`remind_time` ASC) USING BTREE,
  INDEX `idx_reminders_confirm_status`(`confirm_status` ASC) USING BTREE,
  INDEX `idx_reminders_user_time`(`user_id` ASC, `remind_time` ASC) USING BTREE,
  INDEX `idx_reminders_execution_status`(`execution_status` ASC) USING BTREE,
  INDEX `idx_reminders_user_end_time`(`user_id` ASC, `end_time` ASC) USING BTREE,
  CONSTRAINT `fk_reminders_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 1472 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '提醒表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for schedule_import_items
-- ----------------------------
DROP TABLE IF EXISTS `schedule_import_items`;
CREATE TABLE `schedule_import_items`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '明细ID',
  `import_id` bigint NOT NULL COMMENT '关联导入记录ID',
  `reminder_id` bigint NULL DEFAULT NULL COMMENT '创建的提醒ID（成功时）',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '日程标题',
  `remind_date` date NOT NULL COMMENT '日期',
  `remind_time` time NOT NULL COMMENT '时间',
  `location` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '地点',
  `repeat_type` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `status` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `error_message` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_import`(`import_id` ASC) USING BTREE,
  INDEX `idx_reminder`(`reminder_id` ASC) USING BTREE,
  CONSTRAINT `schedule_import_items_ibfk_1` FOREIGN KEY (`import_id`) REFERENCES `schedule_imports` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `schedule_import_items_ibfk_2` FOREIGN KEY (`reminder_id`) REFERENCES `reminders` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '日程导入明细表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for schedule_imports
-- ----------------------------
DROP TABLE IF EXISTS `schedule_imports`;
CREATE TABLE `schedule_imports`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '导入记录ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '原始文件名',
  `file_type` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `total_count` int NOT NULL DEFAULT 0 COMMENT '识别总数',
  `success_count` int NOT NULL DEFAULT 0 COMMENT '成功创建数',
  `failed_count` int NOT NULL DEFAULT 0 COMMENT '失败数',
  `status` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '导入时间',
  `semester_start_date` date NULL DEFAULT NULL,
  `total_weeks` int NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_created`(`user_id` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `schedule_imports_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '日程导入记录表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for user_dialog_stats
-- ----------------------------
DROP TABLE IF EXISTS `user_dialog_stats`;
CREATE TABLE `user_dialog_stats`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID，主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `input_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户输入文本',
  `intent_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '意图类型：REMINDER/QUERY/WEATHER/DELETE/UPDATE/CONFIRM/CANCEL/UNKNOWN',
  `parsed_time` datetime NULL DEFAULT NULL COMMENT 'LLM解析出的提醒时间',
  `parsed_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'LLM解析出的事项标题',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '输入时间',
  `processed` bit(1) NOT NULL,
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `role` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_dialog_stats_user_time`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_user_dialog_stats_intent`(`intent_type` ASC) USING BTREE,
  INDEX `idx_user_created`(`user_id` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `fk_user_dialog_stats_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 191 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '对话统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for users
-- ----------------------------
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID，主键',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '手机号（11位），唯一登录标识',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'BCrypt加密后的密码',
  `nickname` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `avatar` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `gender` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PRIVATE' COMMENT '性别：MALE/FEMALE/PRIVATE',
  `city` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `default_advance_minutes` int NOT NULL DEFAULT 10 COMMENT '默认提前量（分钟）：5,10,15,30,60',
  `remind_interval` int NOT NULL DEFAULT 10 COMMENT '补提醒间隔（分钟）：5,10,15,20,30',
  `cycle_days` int NOT NULL DEFAULT 28 COMMENT '月经周期（天），仅女性，范围21-35',
  `last_period_start` date NULL DEFAULT NULL COMMENT '上次月经开始日期',
  `period_duration` int NOT NULL DEFAULT 5 COMMENT '经期持续天数，范围3-7',
  `enable_period_reminder` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否开启经期提醒：1开启，0关闭',
  `period_confirm_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '经期确认状态：PENDING/CONFIRMED/DEFAULTED',
  `period_postponed_until` date NULL DEFAULT NULL COMMENT '经期确认推迟到哪天',
  `period_postpone_count` int NOT NULL DEFAULT 0 COMMENT '经期已推迟次数，范围0-3',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `period_confirm_count` int NOT NULL,
  `period_remind_postponed` bit(1) NOT NULL,
  `period_remind_postponed_count` int NOT NULL,
  `semester_start_date` date NULL DEFAULT NULL COMMENT '学期开始日期（第一周周一）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_users_phone`(`phone` ASC) USING BTREE,
  INDEX `idx_users_phone`(`phone` ASC) USING BTREE,
  INDEX `idx_users_gender`(`gender` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for verification_codes
-- ----------------------------
DROP TABLE IF EXISTS `verification_codes`;
CREATE TABLE `verification_codes`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID，主键',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '手机号',
  `code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '6位验证码',
  `expires_at` datetime NOT NULL COMMENT '过期时间（创建+5分钟）',
  `used` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否已使用：1已使用，0未使用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_verification_codes_phone`(`phone` ASC) USING BTREE,
  INDEX `idx_verification_codes_expires`(`expires_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '验证码表' ROW_FORMAT = DYNAMIC;

SET FOREIGN_KEY_CHECKS = 1;
