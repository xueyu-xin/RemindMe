package com.dormrepair.remindme.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;

// 文件解析工具（final + 私有构造，不允许实例化）。按扩展名把三类上传文件转为文本：
// Excel（.xls/.xlsx）→逐行逐单元格拼接成 工作表分隔+竖线分隔 的表格文本；
// PDF → PDFTextStripper 抽纯文本；图片→Base64 data URI
@Slf4j
public final class FileParserUtil {

    // 单个文件体积上限 10MB；超过仍继续解析但打印 warn 日志，提醒排查上传文件是否合理
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024L; // 10MB

    // 私有构造，禁止实例化
    private FileParserUtil() {
    }

    // 解析分发入口。执行三步校验：文件非空/文件名非空/大小；取扩展名转小写后分支到 parseExcel / parsePdf / parseImage；
    // 不支持的扩展名直接抛 IllegalArgumentException
    public static String parseFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        long size = file.getSize();
        if (size > MAX_FILE_SIZE) {
            log.warn("文件过大：{}，大小：{} bytes", originalFilename, size);
        }

        String lowerName = originalFilename.toLowerCase();

        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return parseExcel(file);
        } else if (lowerName.endsWith(".pdf")) {
            return parsePdf(file);
        } else if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png") || lowerName.endsWith(".gif")
                || lowerName.endsWith(".webp")) {
            return parseImage(file);
        } else {
            throw new IllegalArgumentException("不支持的文件类型：" + originalFilename);
        }
    }

    // Excel 解析。遍历所有 Sheet，每张 sheet 开头加一行【工作表：XXX】；
    // 同一行的单元格用「|」拼接；整行无内容则跳过，避免大量空行污染结果；
    // try-with-resources 自动关 Workbook；最后走课表关键词特征检测
    public static String parseExcel(MultipartFile file) {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            StringBuilder sb = new StringBuilder();

            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);
                String sheetName = sheet.getSheetName();

                if (sheetIdx > 0) {
                    sb.append("\n");
                }
                sb.append("【工作表：").append(sheetName).append("】\n");

                for (Row row : sheet) {
                    boolean hasContent = false;
                    StringBuilder rowBuilder = new StringBuilder();

                    for (int cellIdx = 0; cellIdx < row.getLastCellNum(); cellIdx++) {
                        if (cellIdx > 0) {
                            rowBuilder.append("|");
                        }
                        Cell cell = row.getCell(cellIdx, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        String value = getCellValueAsString(cell);
                        rowBuilder.append(value);
                        if (!value.isEmpty()) {
                            hasContent = true;
                        }
                    }

                    if (hasContent) {
                        sb.append(rowBuilder).append("\n");
                    }
                }
            }

            String result = sb.toString();

            // 匹配到课表关键字≥2个，打印 info 日志，保留原始表格不做其他变换
            if (isScheduleFormat(result)) {
                log.info("检测到课表格式，保留原始表格结构：{}", file.getOriginalFilename());
            }

            log.info("Excel 解析成功：{}", file.getOriginalFilename());
            return result;
        } catch (Exception e) {
            log.error("Excel 解析失败：{}", file.getOriginalFilename(), e);
            throw new RuntimeException("Excel 解析失败：" + e.getMessage(), e);
        }
    }

    // PDF 解析。Loader.loadPDF 读入字节后由 PDFTextStripper 抽纯文本；
    // try-with-resources 自动关 PDDocument；成功打印文本长度，失败抛 RuntimeException 带 cause
    public static String parsePdf(MultipartFile file) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            log.info("PDF 解析成功：{}，文本长度：{}", file.getOriginalFilename(), text.length());
            return text;
        } catch (IOException e) {
            log.error("PDF 解析失败：{}", file.getOriginalFilename(), e);
            throw new RuntimeException("PDF 解析失败：" + e.getMessage(), e);
        }
    }

    // 图片转 Base64。先读字节再 Base64 编码；contentType 优先取 MultipartFile#getContentType；
    // 若为空或非 image/* 则根据扩展名兜底：.jpg/jpeg→image/jpeg，.png→image/png，gif/webp 同理；
    // 最终拼成 data:<mime>;base64,<base64> 完整 data URI 字符串
    public static String parseImage(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);

            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                // 根据扩展名兜底推断 MIME
                String name = file.getOriginalFilename().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                    contentType = "image/jpeg";
                } else if (name.endsWith(".png")) {
                    contentType = "image/png";
                } else if (name.endsWith(".gif")) {
                    contentType = "image/gif";
                } else if (name.endsWith(".webp")) {
                    contentType = "image/webp";
                } else {
                    contentType = "image/jpeg";
                }
            }

            String result = "data:" + contentType + ";base64," + base64;
            log.info("图片转换 Base64 成功：{}，大小：{} bytes", file.getOriginalFilename(), bytes.length);
            return result;
        } catch (IOException e) {
            log.error("图片解析失败：{}", file.getOriginalFilename(), e);
            throw new RuntimeException("图片解析失败：" + e.getMessage(), e);
        }
    }

    // 单元格取值转字符串。6 种 CellType 分支处理：
    // STRING：trim 去前后空白；NUMERIC：日期格式单元格走 LocalDate 转字符串，整数用 long 精确显示避免科学计数法；
    // BOOLEAN：直接 toString；FORMULA：先尝试 getString 失败再 try getNumeric；default 空串兜底
    private static String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double num = cell.getNumericCellValue();
                if (num == Math.floor(num) && !Double.isInfinite(num)) {
                    return String.valueOf((long) num);
                }
                return String.valueOf(num);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    try {
                        return String.valueOf(cell.getNumericCellValue());
                    } catch (Exception e2) {
                        return "";
                    }
                }
            default:
                return "";
        }
    }

    // 课表格式检测。扫描内容是否同时命中 2+ 课表特征词：时间/节次、周一~周五、第1/2/3节、08:00 08:50 09:50 三个典型上课时间；
    // 返回 bool：true 表示内容很可能是一张课程表，后续调用方可据此选择不同的后处理流程
    private static boolean isScheduleFormat(String content) {
        if (content == null || content.trim().isEmpty()) return false;
        String[] scheduleKeywords = {"时间/节次", "周一", "周二", "周三", "周四", "周五",
                "第1节", "第2节", "第3节", "08:00", "08:50", "09:50"};
        int matchCount = 0;
        for (String kw : scheduleKeywords) {
            if (content.contains(kw)) {
                matchCount++;
            }
        }
        return matchCount >= 2;
    }
}
