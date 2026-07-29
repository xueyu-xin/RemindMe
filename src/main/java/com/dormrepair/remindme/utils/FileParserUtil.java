package com.dormrepair.remindme.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;

/**
 * 文件解析工具类
 * <p>
 * 根据文件类型（Excel、PDF、图片）解析文件内容，返回纯文本或 Base64 字符串
 * </p>
 */
@Slf4j
public final class FileParserUtil {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024L; // 10MB

    private FileParserUtil() {
    }

    /**
     * 根据文件类型分发解析
     *
     * @param file 上传的文件
     * @return 解析后的文本内容，图片返回 Base64 data URI
     */
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

    /**
     * 解析 Excel 文件
     */
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
                            rowBuilder.append("\t");
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

            log.info("Excel 解析成功：{}", file.getOriginalFilename());
            return sb.toString();
        } catch (Exception e) {
            log.error("Excel 解析失败：{}", file.getOriginalFilename(), e);
            throw new RuntimeException("Excel 解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解析 PDF 文件
     */
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

    /**
     * 解析图片文件，返回 Base64 data URI（供多模态 AI 使用）
     */
    public static String parseImage(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);

            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                // 根据扩展名兜底
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

    /**
     * 获取单元格值为字符串
     */
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
}
