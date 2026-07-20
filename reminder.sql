/*
 Navicat Premium Dump SQL

 Source Server         : localhost_3307
 Source Server Type    : MySQL
 Source Server Version : 80400 (8.4.0)
 Source Host           : localhost:3307
 Source Schema         : reminder

 Target Server Type    : MySQL
 Target Server Version : 80400 (8.4.0)
 File Encoding         : 65001

 Date: 21/07/2026 00:39:25
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
  PRIMARY KEY (`session_id`) USING BTREE,
  INDEX `idx_user_status`(`user_id` ASC, `status` ASC) USING BTREE,
  INDEX `idx_expires_at`(`expires_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of confirmation_sessions
-- ----------------------------

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
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '推送日志表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of push_logs
-- ----------------------------
INSERT INTO `push_logs` VALUES (1, 1, 2, '2026-07-20 23:57:00', 0, NULL, 'WEBSOCKET');
INSERT INTO `push_logs` VALUES (2, 1, 2, '2026-07-21 00:00:00', 0, NULL, 'WEBSOCKET');

-- ----------------------------
-- Table structure for reminders
-- ----------------------------
DROP TABLE IF EXISTS `reminders`;
CREATE TABLE `reminders`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '提醒ID，主键',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提醒事项',
  `remind_time` datetime NOT NULL COMMENT '计划推送时间',
  `timezone` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `advance_minutes` int NOT NULL DEFAULT 10 COMMENT '提前量（分钟）',
  `cron_expression` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `location` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '地点',
  `priority` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NORMAL' COMMENT '优先级：LOW/NORMAL/HIGH',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '推送状态：PENDING/SENT/CANCELLED/EXPIRED',
  `confirm_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '确认状态：PENDING/CONFIRMED/CANCELLED/DEFAULTED',
  `confirm_attempts` int NOT NULL DEFAULT 0 COMMENT '已确认提醒次数，范围0-3',
  `last_confirm_remind_at` datetime NULL DEFAULT NULL COMMENT '最后一次确认提醒时间',
  `source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER_INPUT' COMMENT '来源：USER_INPUT/WEATHER_LINK/EXCEL_IMPORT/PERIOD_REMINDER',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_reminders_user_status_time`(`user_id` ASC, `status` ASC, `remind_time` ASC) USING BTREE,
  INDEX `idx_reminders_remind_time`(`remind_time` ASC) USING BTREE,
  INDEX `idx_reminders_confirm_status`(`confirm_status` ASC) USING BTREE,
  INDEX `idx_reminders_user_time`(`user_id` ASC, `remind_time` ASC) USING BTREE,
  CONSTRAINT `fk_reminders_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '提醒表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of reminders
-- ----------------------------
INSERT INTO `reminders` VALUES (1, 2, '去教学楼1领取资料', '2026-07-19 15:00:00', 'Asia/Shanghai', 10, NULL, '教学楼1', 'NORMAL', 'SENT', 'PENDING', 1, '2026-07-21 00:00:00', 'USER_INPUT', '2026-07-20 23:56:13', '2026-07-21 00:00:00');
INSERT INTO `reminders` VALUES (2, 2, '拿水果', '2026-07-21 19:00:00', 'Asia/Shanghai', 10, NULL, 'null', 'NORMAL', 'PENDING', 'PENDING', 0, NULL, 'USER_INPUT', '2026-07-20 23:59:40', '2026-07-20 23:59:40');

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
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_dialog_stats_user_time`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_user_dialog_stats_intent`(`intent_type` ASC) USING BTREE,
  CONSTRAINT `fk_user_dialog_stats_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '对话统计表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user_dialog_stats
-- ----------------------------
INSERT INTO `user_dialog_stats` VALUES (1, 1, '明天下午三点钟去汇智楼上课', 'UNKNOWN', NULL, NULL, '2026-07-20 17:09:16', b'0');
INSERT INTO `user_dialog_stats` VALUES (2, 2, '明天下午三点钟我要去把垃圾倒掉', 'UNKNOWN', NULL, NULL, '2026-07-20 20:54:43', b'0');
INSERT INTO `user_dialog_stats` VALUES (3, 2, '明天下午三点提醒我去教学楼1领取资料', 'UNKNOWN', NULL, NULL, '2026-07-20 22:49:03', b'0');
INSERT INTO `user_dialog_stats` VALUES (4, 2, '明天下午三点提醒我去教学楼1领取资料', 'UNKNOWN', NULL, NULL, '2026-07-20 23:44:18', b'0');
INSERT INTO `user_dialog_stats` VALUES (5, 2, '明天下午三点提醒我去教学楼1领取资料', 'UNKNOWN', NULL, NULL, '2026-07-20 23:47:25', b'0');
INSERT INTO `user_dialog_stats` VALUES (6, 2, '明天下午三点提醒我去教学楼1领取资料', 'UNKNOWN', NULL, NULL, '2026-07-20 23:50:43', b'0');
INSERT INTO `user_dialog_stats` VALUES (7, 2, '明天下午三点提醒我去教学楼1领取资料', 'REMINDER', NULL, NULL, '2026-07-20 23:56:13', b'0');
INSERT INTO `user_dialog_stats` VALUES (8, 2, '明天下午3点提醒我还书', 'REMINDER', NULL, NULL, '2026-07-20 23:57:25', b'0');
INSERT INTO `user_dialog_stats` VALUES (9, 2, '明晚七点钟拿水果', 'REMINDER', NULL, NULL, '2026-07-20 23:59:40', b'0');

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
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_users_phone`(`phone` ASC) USING BTREE,
  INDEX `idx_users_phone`(`phone` ASC) USING BTREE,
  INDEX `idx_users_gender`(`gender` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of users
-- ----------------------------
INSERT INTO `users` VALUES (1, '13800138001', '$2a$10$Y.7V8nhiZcZnPUOHQXxnFezn6Grf7/QIRXorq2vHOYrYYwno4zyou', '用户8001', NULL, 'PRIVATE', '广州', 10, 10, 28, NULL, 5, 1, 'PENDING', NULL, 0, '2026-07-18 21:28:01', '2026-07-18 21:28:01', 0, b'0', 0);
INSERT INTO `users` VALUES (2, '15989669556', '$2a$10$hLsh49s5YX.h4awzJJQKG.Zyj35kRLrlfmZwcpgfHNTIT.1vCg.AK', '用户9556', NULL, 'FEMALE', '广州', 10, 10, 28, NULL, 5, 1, 'PENDING', NULL, 0, '2026-07-20 17:08:25', '2026-07-20 20:53:55', 0, b'0', 0);

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
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '验证码表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of verification_codes
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;
