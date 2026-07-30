# 校园生活提醒助手 (Life Reminder)

> 基于 Agent 的智能化校园生活提醒系统，支持自然语言创建提醒、课表智能导入、天气联动、经期管理等功能。

---

## 1. 项目概述

### 1.1 项目定位

校园生活智能提醒助手是一款面向大学生群体的 AI 驱动智能提醒应用。用户可通过自然语言与 AI 助手对话，快速创建日程提醒、查询天气、管理课表、记录经期等。

### 1.2 核心功能

| 功能模块 | 说明 |
|---------|------|
| **AI 对话提醒** | 基于 ReAct 模式的 Agent，支持自然语言创建、查询、修改、取消提醒 |
| **课表智能导入** | 支持 Excel / PDF / 图片上传，AI 自动识别课表结构并批量生成提醒 |
| **天气联动提醒** | 根据天气状况（高温/低温/暴雨）自动生成关联提醒 |
| **经期管理** | 经期追踪、状态预测、经期提醒自动生成 |
| **个人记忆系统 (RAG)** | 基于 ChromaDB + Ollama 的向量知识库，实现用户信息长期记忆 |
| **周期提醒** | 支持按日/周/月重复的周期性提醒 |
| **日历视图** | 可视化日历展示所有提醒 |
| **实时推送** | 基于 WebSocket 的卡片式提醒推送与交互 |
| **冲突检测** | 创建提醒时自动检测时间冲突，支持覆盖/跳过策略 |

### 1.3 适用场景

- 大学生日常日程管理
- 课程表批量导入与提醒生成
- 考试、作业、截止日期提醒
- 天气敏感场景的自动提醒（如带伞、添衣）
- 经期追踪与健康管理
- 个人知识管理与记忆存储

---

## 2. 技术栈

### 2.1 后端框架

| 技术 | 版本 | 说明 |
|-----|------|-----|
| Spring Boot | 3.4.1 | 核心框架 |
| Spring AI | 1.1.4 | AI 能力集成 |
| JDK | 21 | Java 运行环境 |

### 2.2 数据库与 ORM

| 技术 | 版本 | 说明 |
|-----|------|-----|
| MySQL | 8.0+ | 关系型数据库 |
| Spring Data JPA | (Boot 管理) | ORM 框架 |
| Hibernate | (Boot 管理) | JPA 实现 |

### 2.3 AI 与大模型

| 技术 | 版本 | 说明 |
|-----|------|-----|
| DeepSeek-V4-Flash | — | 主对话模型 |
| 通义千问 (qwen-vl-plus) | — | 多模态识别模型（课表图片识别） |
| Ollama | — | 本地 Embedding 模型运行时 |
| qwen3-embedding:4b | — | 文本向量嵌入模型 |
| ChromaDB | — | 向量数据库（RAG 知识库） |
| DashScope SDK | 2.16.0 | 阿里通义千问官方 SDK |

### 2.4 实时通信与安全

| 技术 | 版本 | 说明 |
|-----|------|-----|
| Spring WebSocket | (Boot 管理) | 实时消息推送 |
| Spring Security | (Boot 管理) | 认证授权框架 |
| JJWT | 0.12.5 | JWT 令牌处理 |

### 2.5 前端与模板

| 技术 | 版本 | 说明 |
|-----|------|-----|
| Thymeleaf | (Boot 管理) | 服务端模板引擎 |
| 原生 HTML/CSS/JS | — | 前端页面 |

### 2.6 文档解析与工具

| 技术 | 版本 | 说明 |
|-----|------|-----|
| Apache POI | 5.3.0 | Excel 文件解析 |
| PDFBox | 3.0.3 | PDF 文件解析 |
| Lombok | 1.18.46 | 代码简化 |
| Apache Commons Lang3 | (Boot 管理) | 通用工具库 |
| lunar | 1.7.7 | 农历/节日计算 |

---

## 3. 环境准备

### 3.1 必备环境

| 环境 | 版本要求 | 说明 |
|-----|---------|-----|
| JDK | 21+ | 推荐使用 JDK 21 |
| Maven | 3.8+ | 项目构建工具 |
| MySQL | 8.0+ | 关系型数据库 |
| Chrome / Edge | 最新版 | 推荐浏览器 |

### 3.2 外部服务

| 服务 | 版本要求 | 说明 |
|-----|---------|-----|
| Ollama | 最新版 | 本地运行 Embedding 模型 |
| ChromaDB | 0.4+ | 向量数据库 |

### 3.3 Ollama 模型

需在本地 Ollama 中安装以下模型：

```bash
# 拉取 qwen3-embedding:4b 用于文本向量化
ollama pull qwen3-embedding:4b
```

### 3.4 ChromaDB 安装

```bash
# 使用 pip 安装
pip install chromadb

# 启动 ChromaDB 服务（绑定所有网卡，允许外部访问）
chroma run --host 0.0.0.0 --port 8000

# 或使用 Docker
docker run -p 8000:8000 chromadb/chroma
```

---

## 4. 配置说明

### 4.1 `application.yml` 完整配置

项目配置文件位于 `src/main/resources/application.yml`：

```yaml
server:
  port: 8083

spring:
  datasource:
    url: jdbc:mysql://localhost:3307/reminder?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
    username: root
    password: password
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000

  jpa:
    database-platform: org.hibernate.dialect.MySQLDialect
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        format_sql: false
        use_sql_comments: false
        jdbc:
          batch_size: 20

  jackson:
    time-zone: Asia/Shanghai
    date-format: yyyy-MM-dd HH:mm:ss

  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 10MB

  thymeleaf:
    cache: false
    encoding: UTF-8
    mode: HTML

  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY:your-deepseek-api-key}
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-v4-flash
          temperature: 0.3
          max-tokens: 4096

    vectorstore:
      chroma:
        client:
          host: http://localhost
          port: 8000
        tenant-name: default_tenant
        database-name: default_database
        collection-name: public_knowledge
        initialize-schema: true

    ollama:
      base-url: http://localhost:11434
      embedding:
        options:
          model: qwen3-embedding:4b

jwt:
  secret: ${JWT_SECRET:your-jwt-secret-key-at-least-256-bits-long}
  expiration: 604800000

weather:
  api:
    key: ${WEATHER_API_KEY:your-weather-api-key}
    host: ${WEATHER_API_HOST:your-weather-api-host}

sms:
  api-key: ${SMS_API_KEY:your-sms-api-key}

logging:
  level:
    com.dormrepair.remindme: DEBUG
    org.hibernate.SQL: WARN

agent:
  enabled: true
  max-history: 10
  timeout-seconds: 15

amap:
  api:
    key: ${AMAP_API_KEY:}

dashscope:
  api:
    key: ${DASHSCOPE_API_KEY:}
```

### 4.2 配置项说明

#### 数据库配置

| 配置项 | 说明 | 是否必填 |
|-------|------|---------|
| `spring.datasource.url` | MySQL 连接 URL | 是 |
| `spring.datasource.username` | 数据库用户名 | 是 |
| `spring.datasource.password` | 数据库密码 | 是 |
| `spring.jpa.hibernate.ddl-auto` | DDL 策略，`update` 自动更新表结构 | 是 |

#### AI 配置

| 配置项 | 说明 | 是否必填 |
|-------|------|---------|
| `spring.ai.openai.api-key` | DeepSeek API Key | 是 |
| `spring.ai.openai.base-url` | DeepSeek API 地址 | 是 |
| `spring.ai.openai.chat.options.model` | 使用的模型名称 | 是 |
| `spring.ai.vectorstore.chroma.client.host` | ChromaDB 地址 | 是 |
| `spring.ai.vectorstore.chroma.client.port` | ChromaDB 端口 | 是 |
| `spring.ai.ollama.base-url` | Ollama 服务地址 | 是 |
| `spring.ai.ollama.embedding.options.model` | Embedding 模型名称 | 是 |

#### JWT 配置

| 配置项 | 说明 | 是否必填 |
|-------|------|---------|
| `jwt.secret` | JWT 签名密钥（至少 256 位） | 是 |
| `jwt.expiration` | Token 有效期（毫秒），默认 7 天 | 是 |

#### 可选服务配置

| 配置项 | 说明 | 是否必填 |
|-------|------|---------|
| `weather.api.key` | 天气 API Key | 否（天气功能需要） |
| `weather.api.host` | 天气 API 地址 | 否（天气功能需要） |
| `sms.api-key` | 短信服务 API Key | 否（短信功能需要） |
| `amap.api.key` | 高德地图 API Key | 否（地图功能需要） |
| `dashscope.api.key` | 通义千问 API Key | 否（图片/课表识别需要） |

---

## 5. API 密钥与外部服务配置

### 5.1 环境变量列表

| 环境变量 | 对应配置项 | 用途 | 获取方式 |
|---------|----------|------|---------|
| `DEEPSEEK_API_KEY` | `spring.ai.openai.api-key` | 主对话 AI 模型 | [https://platform.deepseek.com](https://platform.deepseek.com) 注册后获取 |
| `JWT_SECRET` | `jwt.secret` | JWT 令牌签名密钥 | 自行生成（建议至少 256 位随机字符串） |
| `DASHSCOPE_API_KEY` | `dashscope.api.key` | 通义千问多模态识别（课表图片识别） | [https://dashscope.console.aliyun.com](https://dashscope.console.aliyun.com) 开通服务后获取 |
| `WEATHER_API_KEY` | `weather.api.key` | 天气数据查询 | 和风天气、心知天气等服务商申请 |
| `WEATHER_API_HOST` | `weather.api.host` | 天气 API 服务地址 | 与 Weather API Key 同源 |
| `SMS_API_KEY` | `sms.api-key` | 短信验证码发送 | 阿里云短信、腾讯云短信等服务商申请 |
| `AMAP_API_KEY` | `amap.api.key` | 高德地图 API（地点搜索/地理编码） | [https://lbs.amap.com](https://lbs.amap.com) 申请 Key |

### 5.2 设置环境变量

#### Windows (PowerShell)

```powershell
# 临时设置（当前会话有效）
$env:DEEPSEEK_API_KEY = "your-deepseek-api-key"
$env:JWT_SECRET = "your-jwt-secret-key-at-least-256-bits-long"
$env:DASHSCOPE_API_KEY = "your-dashscope-api-key"
$env:WEATHER_API_KEY = "your-weather-api-key"
$env:WEATHER_API_HOST = "your-weather-api-host"
$env:SMS_API_KEY = "your-sms-api-key"
$env:AMAP_API_KEY = "your-amap-api-key"

# 或在启动时通过 JVM 参数传递
mvn spring-boot:run -Dspring-boot.run.jvmArguments="
  -DDEEPSEEK_API_KEY=your-key
  -DJWT_SECRET=your-secret
  -DDASHSCOPE_API_KEY=your-key
"
```

#### macOS / Linux (bash)

```bash
# 临时设置
export DEEPSEEK_API_KEY="your-deepseek-api-key"
export JWT_SECRET="your-jwt-secret-key-at-least-256-bits-long"
export DASHSCOPE_API_KEY="your-dashscope-api-key"
export WEATHER_API_KEY="your-weather-api-key"
export WEATHER_API_HOST="your-weather-api-host"
export SMS_API_KEY="your-sms-api-key"
export AMAP_API_KEY="your-amap-api-key"

# 写入配置文件（永久生效）
echo 'export DEEPSEEK_API_KEY="your-key"' >> ~/.bashrc
source ~/.bashrc
```

#### 启动时通过 JVM 参数传递（推荐）

```bash
java -jar life-reminder.jar \
  -DDEEPSEEK_API_KEY=your-deepseek-api-key \
  -DJWT_SECRET=your-jwt-secret-key-at-least-256-bits-long \
  -DDASHSCOPE_API_KEY=your-dashscope-api-key
```

---

## 6. 数据库初始化

### 6.1 创建数据库

```sql
CREATE DATABASE IF NOT EXISTS reminder
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

### 6.2 数据库表结构

JPA 配置为 `ddl-auto: update`，首次启动时自动创建以下表：

| 表名 | 说明 |
|------|------|
| `users` | 用户表：账号信息、偏好设置、经期参数 |
| `reminders` | 提醒表：所有用户提醒，支持单点和时间段 |
| `push_logs` | 推送日志表：记录每一次推送动作 |
| `confirmation_sessions` | 确认会话表：提醒创建确认流程的会话管理 |
| `verification_codes` | 验证码表：手机验证码存储与校验 |
| `user_dialog_stats` | 对话统计表：用户对话记录，用于 RAG |
| `schedule_imports` | 课表导入表：文件导入任务记录 |
| `schedule_import_items` | 课表导入明细表：每一条待创建提醒 |
| `recurring_reminders` | 周期提醒模板表：按日/周/月重复规则 |
| `recurring_instances` | 周期提醒实例表：模板生成的每日实例 |

### 6.3 表关系图

```
users ──1:N──> reminders
users ──1:N──> confirmation_sessions
users ──1:N──> user_dialog_stats
users ──1:N──> schedule_imports
users ──1:N──> recurring_reminders

schedule_imports ──1:N──> schedule_import_items
recurring_reminders ──1:N──> recurring_instances
confirmation_sessions ──1:1──> reminders (确认后回填)
reminders ──1:N──> push_logs
```

---

## 7. 启动步骤

### 7.1 克隆项目

```bash
git clone <repository-url>
cd RemindMe
```

### 7.2 配置 `application.yml`

根据 [第 4 章](#4-配置说明) 的说明，修改 `src/main/resources/application.yml`：

**最低配置（必须修改）：**

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3307/reminder?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
    username: root
    password: your-password
```

### 7.3 设置环境变量

至少需要设置以下必填项：

```bash
# 方式一：环境变量
export DEEPSEEK_API_KEY="your-deepseek-api-key"
export JWT_SECRET="your-jwt-secret-key"

# 方式二：修改 application.yml 中的默认值
# 将 ${DEEPSEEK_API_KEY:your-deepseek-api-key} 改为实际值
```

### 7.4 启动外部依赖服务

```bash
# 1. 启动 MySQL 数据库
# 确保 MySQL 正在运行，且已创建 reminder 数据库

# 2. 启动 Ollama（本地 Embedding 服务）
ollama serve  # 默认端口 11434

# 3. 启动 ChromaDB（向量数据库）
chroma run --host 0.0.0.0 --port 8000
# 或 Docker 方式：
# docker run -d -p 8000:8000 chromadb/chroma
```

### 7.5 启动 Spring Boot 应用

```bash
# 开发模式
mvn spring-boot:run

# 打包构建
mvn clean package

# 运行打包后的 Jar
java -jar target/life-reminder-0.0.1-SNAPSHOT.jar
```

### 7.6 验证运行状态

启动成功后访问：

- **首页**：[http://localhost:8083](http://localhost:8083)
- **登录页**：[http://localhost:8083/login](http://localhost:8083/login)
- **健康检查**：[http://localhost:8083/api/health](http://localhost:8083/api/health)

---

## 8. 项目结构说明

### 8.1 完整包结构

```
com.dormrepair.remindme
├── agent/                          # AI Agent 模块
│   ├── AgentService.java           # Agent 核心调度（ReAct 循环）
│   ├── AgentResponse.java          # Agent 响应 DTO
│   ├── AgentTool.java              # 工具接口
│   ├── ConversationMemory.java     # 对话记忆管理
│   ├── ToolCall.java               # 工具调用记录
│   ├── ToolDefinition.java         # 工具定义
│   ├── ToolRegistry.java           # 工具注册中心
│   └── event/                      # Agent 事件
│       ├── AgentEvent.java
│       ├── EventPublisher.java
│       ├── ReminderCancelledEvent.java
│       ├── ReminderConfirmedEvent.java
│       └── ReminderCreatedEvent.java
├── config/                         # 配置模块
│   ├── AppConfig.java              # 应用基础配置
│   ├── ChromaConfig.java           # ChromaDB 向量库配置
│   ├── QwenConfig.java             # 通义千问配置
│   ├── SecurityConfig.java         # Spring Security 配置
│   ├── WeatherConfig.java          # 天气服务配置
│   ├── WebConfig.java              # Web 配置
│   └── WebSocketConfig.java        # WebSocket 配置
├── controller/                     # REST 控制器
│   ├── AuthApiController.java       # 认证 API（注册/登录/验证码）
│   ├── AuthPageController.java     # 认证页面路由
│   ├── CalendarController.java     # 日历视图接口
│   ├── ChatController.java         # 对话接口
│   ├── CityController.java         # 城市搜索接口
│   ├── ConfirmationController.java # 确认流程接口
│   ├── IndexController.java        # 首页路由
│   ├── RagController.java          # RAG 知识库接口
│   ├── RecurringReminderController.java  # 周期提醒接口
│   ├── ReminderController.java     # 提醒 CRUD 接口
│   ├── ScheduleImportController.java    # 课表导入接口
│   ├── UserController.java         # 用户信息接口
│   └── WeatherController.java     # 天气查询接口
├── dto/                            # 数据传输对象
│   ├── request/                    # 请求 DTO
│   ├── response/                   # 响应 DTO
│   ├── CourseTimeConfigDTO.java    # 课表时间配置
│   ├── ManualCourseDTO.java        # 手动课程 DTO
│   ├── ScheduleItemDTO.java        # 日程项 DTO
│   ├── ScheduleTableDTO.java       # 课表结构化 DTO
│   └── TimeSlotDTO.java            # 时间段 DTO
├── entity/                         # JPA 实体
│   ├── ConfirmationSession.java    # 确认会话
│   ├── PushLog.java                # 推送日志
│   ├── RecurringInstance.java      # 周期实例
│   ├── RecurringReminder.java      # 周期模板
│   ├── Reminder.java               # 提醒（核心）
│   ├── ScheduleImport.java         # 课表导入任务
│   ├── ScheduleImportItem.java    # 课表导入明细
│   ├── User.java                   # 用户
│   ├── UserDialogStats.java        # 对话统计
│   └── VerificationCode.java       # 验证码
├── enums/                          # 枚举定义
│   ├── ConfirmStatus.java          # 确认状态
│   ├── ExecutionStatus.java        # 执行状态
│   ├── Gender.java                 # 性别
│   ├── IntentType.java             # 意图类型
│   ├── MemoryCategory.java         # 记忆分类
│   ├── PeriodStatus.java           # 经期状态
│   ├── Priority.java               # 优先级
│   ├── PushChannel.java            # 推送渠道
│   ├── ReminderSource.java         # 提醒来源
│   ├── ReminderStatus.java         # 提醒状态
│   └── RepeatType.java             # 重复类型
├── handler/                        # 全局异常处理
│   └── GlobalExceptionHandler.java
├── repository/                     # Spring Data JPA 仓储
├── service/                        # 业务服务
│   ├── AgentService.java           # AI Agent 服务
│   ├── ConfirmationService.java    # 确认会话服务
│   ├── ConflictDetectionService.java  # 冲突检测服务
│   ├── DialogStatsService.java     # 对话统计服务
│   ├── LLMService.java             # LLM 调用服务
│   ├── LocationService.java        # 地点服务
│   ├── PeriodService.java          # 经期管理服务
│   ├── PushService.java            # 推送服务
│   ├── QwenRecognitionService.java # 通义千问识别服务
│   ├── RagService.java             # RAG 知识库服务
│   ├── RecurringReminderService.java   # 周期提醒服务
│   ├── ReminderService.java        # 提醒核心服务
│   ├── ScheduleImportService.java  # 课表导入服务
│   ├── SchedulerService.java       # 定时调度服务
│   ├── TimeInferenceService.java   # 时间推断服务
│   ├── UserService.java            # 用户服务
│   ├── VerificationCodeService.java # 验证码服务
│   ├── WeatherService.java         # 天气服务
│   └── WebSocketPushService.java   # WebSocket 推送服务
├── utils/                          # 工具类
│   ├── FileParserUtil.java         # 文件解析工具
│   ├── JwtUtils.java               # JWT 工具
│   ├── TimeTools.java              # 时间工具
│   └── TimeUtils.java              # 时间通用工具
└── RemindMeApplication.java        # 启动类
```

### 8.2 核心模块说明

| 模块 | 包路径 | 说明 |
|------|-------|------|
| **AI Agent** | `agent/` | 基于 ReAct 模式的 AI 调度器，支持工具调用、对话记忆、事件驱动 |
| **LLM 服务** | `service/LLMService.java` | DeepSeek API 封装，负责对话生成 |
| **RAG 知识库** | `service/RagService.java` | 基于 ChromaDB + Ollama 的向量检索 |
| **课表识别** | `service/QwenRecognitionService.java` | 通义千问多模态课表识别 |
| **课表导入** | `service/ScheduleImportService.java` | 文件解析 → AI 识别 → 批量创建 |
| **提醒核心** | `service/ReminderService.java` | 提醒 CRUD、冲突检测、优先级处理 |
| **确认流程** | `service/ConfirmationService.java` | 确认卡片、覆盖操作、超时处理 |
| **定时调度** | `service/SchedulerService.java` | 定时扫描待推送提醒，触发推送 |
| **WebSocket 推送** | `service/WebSocketPushService.java` | 实时卡片推送到前端 |
| **安全认证** | `config/SecurityConfig.java` + `utils/JwtUtils.java` | BCrypt 密码加密 + JWT Token |

---

## 9. 常见问题 (FAQ)

### 9.1 启动时端口占用

**问题**：`Port 8083 is already in use`

**解决**：

```bash
# Windows (PowerShell)
netstat -ano | findstr :8083
taskkill /PID <进程ID> /F

# macOS / Linux
lsof -ti:8083 | xargs kill -9

# 或修改 application.yml 中的端口
server:
  port: 8084
```

### 9.2 数据库连接失败

**问题**：`Communications link failure` 或 `Access denied`

**解决**：

1. 确认 MySQL 服务已启动
2. 确认数据库 `reminder` 已创建
3. 检查用户名密码是否正确
4. 检查端口是否正确（默认 3307）
5. 如果使用 Docker MySQL，确认网络连通

```bash
# 测试 MySQL 连接
mysql -h localhost -P 3307 -u root -p
```

### 9.3 WebSocket 连接问题

**问题**：前端 WebSocket 连接失败

**解决**：

1. 确认应用已正常启动
2. 检查 `WebSocketConfig` 中的端点配置
3. 前端连接地址应为 `ws://localhost:8083/ws?user-id={userId}`
4. 确认浏览器允许 WebSocket 连接（检查防火墙/代理）

### 9.4 ChromaDB 连接失败

**问题**：`Connection refused` 或向量库相关报错

**解决**：

1. 确认 ChromaDB 服务已启动
2. 确认端口 8000 未被占用
3. 检查 `application.yml` 中的 `chroma.client.host` 和 `chroma.client.port` 配置

```bash
# 检查 ChromaDB 状态
curl http://localhost:8000/api/v2/version
```

### 9.5 Ollama 连接失败

**问题**：`Cannot connect to Ollama` 或 Embedding 相关报错

**解决**：

1. 确认 Ollama 服务已启动
2. 确认 `qwen3-embedding:4b` 模型已安装

```bash
# 检查 Ollama 状态
curl http://localhost:11434/api/tags

# 如无 embedding 模型，执行：
ollama pull qwen3-embedding:4b
```

### 9.6 LLM API 调用失败

**问题**：401 Unauthorized 或 403 Forbidden

**解决**：

1. 检查 `DEEPSEEK_API_KEY` 是否正确设置
2. 确认 API Key 有效且未过期
3. 检查账户余额是否充足

### 9.7 课表图片识别失败

**问题**：图片上传后无法正确识别课表

**解决**：

1. 确认 `DASHSCOPE_API_KEY` 已正确配置（通义千问 Key）
2. 确认图片清晰、课表结构完整
3. 支持的格式：JPG、PNG
4. 建议图片大小在 5MB 以内（`spring.servlet.multipart.max-file-size`）

### 9.8 时区问题

**问题**：时间显示不正确或时区偏移

**解决**：

项目统一使用 `Asia/Shanghai` 时区，已在多处配置：

- `application.yml` → `spring.jackson.time-zone: Asia/Shanghai`
- `application.yml` → `spring.jackson.date-format: yyyy-MM-dd HH:mm:ss`
- `application.yml` → 数据库连接 `serverTimezone=Asia/Shanghai`
- `RemindMeApplication` → `@PostConstruct` 中设置 JVM 默认时区

如需修改时区，需同时修改以上四处。

---

## 附录

### A. 数据库建表 DDL

首次启动时 JPA 自动创建表。如需手动建表，参考 [DATA_DICT.md](项目设计文档/DATA_DICT.md) 中的 DDL 语句。

### B. 主要 API 端点

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 用户注册 |
| POST | `/api/auth/login` | 用户登录 |
| POST | `/api/auth/send-code` | 发送验证码 |
| POST | `/api/chat/send` | 发送对话消息 |
| GET | `/api/chat/history` | 获取对话历史 |
| POST | `/api/reminders/create` | 创建提醒 |
| GET | `/api/reminders/list` | 查询提醒列表 |
| PUT | `/api/reminders/{id}` | 更新提醒 |
| DELETE | `/api/reminders/{id}` | 删除提醒 |
| POST | `/api/reminders/check-conflict` | 检查时间冲突 |
| POST | `/api/reminders/batch` | 批量创建提醒 |
| POST | `/api/confirmations/create` | 创建确认会话 |
| POST | `/api/confirmations/{sessionId}/confirm` | 确认创建 |
| POST | `/api/confirmations/{sessionId}/cancel` | 取消确认 |
| POST | `/api/confirmations/{sessionId}/overwrite` | 覆盖冲突 |
| POST | `/api/confirmations/{sessionId}/reparse` | 重新解析 |
| GET | `/api/calendar/{userId}/{year}/{month}` | 查询日历 |
| POST | `/api/schedule-import/upload` | 上传课表文件 |
| POST | `/api/schedule-import/{importId}/confirm` | 确认导入 |
| POST | `/api/schedule-import/{importId}/cancel` | 取消导入 |
| GET | `/api/weather/current` | 查询当前天气 |
| GET | `/api/weather/forecast` | 查询天气预报 |
| POST | `/api/rag/query` | RAG 知识库查询 |
| POST | `/api/rag/store` | 存储知识到 RAG |

### C. WebSocket 端点

| 端点 | 说明 |
|------|------|
| `/ws` | WebSocket 连接端点（需携带 `user-id` 参数） |
| `/queue/confirmations` | 确认卡片推送目的地 |
| `/queue/messages` | 通用消息推送目的地 |

### D. 技术版本速查

| 技术 | 版本 |
|------|------|
| Spring Boot | 3.4.1 |
| Spring AI | 1.1.4 |
| JDK | 21 |
| MySQL | 8.0+ |
| JJWT | 0.12.5 |
| Lombok | 1.18.46 |
| DashScope SDK | 2.16.0 |
| Apache POI | 5.3.0 |
| PDFBox | 3.0.3 |
| lunar | 1.7.7 |

---


