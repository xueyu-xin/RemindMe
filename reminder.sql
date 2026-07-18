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

 Date: 18/07/2026 17:00:25
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

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
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '推送日志表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of push_logs
-- ----------------------------

-- ----------------------------
-- Table structure for reminders
-- ----------------------------
DROP TABLE IF EXISTS `reminders`;
CREATE TABLE `reminders`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '提醒ID，主键',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提醒事项',
  `remind_time` datetime NOT NULL COMMENT '计划推送时间',
  `timezone` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '时区',
  `advance_minutes` int NOT NULL DEFAULT 10 COMMENT '提前量（分钟）',
  `cron_expression` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'Cron表达式，非NULL时为周期提醒',
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
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '提醒表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of reminders
-- ----------------------------

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
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_dialog_stats_user_time`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_user_dialog_stats_intent`(`intent_type` ASC) USING BTREE,
  CONSTRAINT `fk_user_dialog_stats_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '对话统计表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user_dialog_stats
-- ----------------------------

-- ----------------------------
-- Table structure for users
-- ----------------------------
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID，主键',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '手机号（11位），唯一登录标识',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'BCrypt加密后的密码',
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '用户昵称',
  `avatar` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '头像（Base64或URL）',
  `gender` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PRIVATE' COMMENT '性别：MALE/FEMALE/PRIVATE',
  `city` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '广州' COMMENT '默认城市（天气查询用）',
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
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_users_phone`(`phone` ASC) USING BTREE,
  INDEX `idx_users_phone`(`phone` ASC) USING BTREE,
  INDEX `idx_users_gender`(`gender` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of users
-- ----------------------------

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
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '验证码表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of verification_codes
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;
