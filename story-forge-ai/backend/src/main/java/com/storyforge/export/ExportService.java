package com.storyforge.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.analytics.ProductAnalyticsService;
import com.storyforge.analytics.ProductEventNames;
import com.storyforge.chapter.entity.StoryChapterVersion;
import com.storyforge.chapter.mapper.StoryChapterVersionMapper;
import com.storyforge.common.exception.ApiException;
import com.storyforge.release.ReleaseResponse;
import com.storyforge.release.ReleaseService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ExportService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ReleaseService releases;
    private final StoryChapterVersionMapper versions;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final Path storageDir;
    private final int ttlMinutes;
    private final ProductAnalyticsService analytics;

    public ExportService(ReleaseService releases, StoryChapterVersionMapper versions, ObjectMapper mapper,
            JdbcTemplate jdbc, @Value("${app.export.storage-dir:./data/exports}") String storageDir,
            @Value("${app.export.download-ttl-minutes:15}") int ttlMinutes,
            ProductAnalyticsService analytics) {
        this.releases = releases;
        this.versions = versions;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        this.ttlMinutes = Math.max(5, Math.min(ttlMinutes, 30));
        this.analytics = analytics;
    }

    @Transactional
    public ExportResponse create(Long userId, Long storyId, CreateExportRequest request) {
        ReleaseResponse release = releases.get(userId, request.releaseId());
        if (!storyId.equals(release.storyId())) throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "EXPORT_FORBIDDEN", "无权导出该故事");
        if (!"LOCKED".equals(release.status()) && !"EXPORTED".equals(release.status())) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "RELEASE_NOT_LOCKED", "正式版本尚未锁定");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO export_task
                    (story_id, release_id, user_id, format, include_report, status, created_time, updated_time)
                    VALUES (?, ?, ?, ?, ?, 'WAITING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[] {"id"});
            statement.setLong(1, storyId);
            statement.setLong(2, request.releaseId());
            statement.setLong(3, userId);
            statement.setString(4, request.format().name());
            statement.setBoolean(5, request.includeReport());
            return statement;
        }, keyHolder);
        // Avoid relying on a driver-specific generated-key column label.
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) throw new IllegalStateException("导出任务未生成 ID");
        Long exportId = generatedId.longValue();
        try {
            byte[] bytes = render(release, request.format(), request.includeReport());
            String extension = request.format().name().toLowerCase();
            String safeBase = safeName(release.title());
            String fileName = safeBase + "-v" + release.releaseNo() + "." + extension;
            Files.createDirectories(storageDir);
            Path target = storageDir.resolve(exportId + "-" + UUID.randomUUID() + "." + extension).normalize();
            if (!target.startsWith(storageDir)) throw new IllegalStateException("导出路径越界");
            Files.write(target, bytes);
            jdbc.update("UPDATE export_task SET status='SUCCESS', file_name=?, object_path=?, file_size=?, content_type=?, updated_time=CURRENT_TIMESTAMP WHERE id=?",
                    fileName, target.toString(), bytes.length, contentType(request.format()), exportId);
            jdbc.update("UPDATE story_release SET status='EXPORTED' WHERE id=? AND status='LOCKED'", request.releaseId());
        } catch (Exception exception) {
            jdbc.update("UPDATE export_task SET status='FAILED', error_message=?, updated_time=CURRENT_TIMESTAMP WHERE id=?", exception.getMessage() == null ? "导出失败" : exception.getMessage().substring(0, Math.min(1000, exception.getMessage().length())), exportId);
        }
        ExportResponse response = get(userId, exportId);
        if ("SUCCESS".equals(response.status())) {
            analytics.record(
                    ProductEventNames.EXPORT_SUCCEEDED,
                    userId,
                    storyId,
                    null,
                    "export:" + exportId + ":succeeded",
                    java.util.Map.of(
                            "format", request.format().name(),
                            "releaseId", request.releaseId(),
                            "fileSize", response.fileSize() == null ? 0 : response.fileSize()
                    )
            );
        }
        return response;
    }

    public List<ExportResponse> list(Long userId, Long storyId) {
        return jdbc.query("SELECT * FROM export_task WHERE user_id=? AND story_id=? ORDER BY created_time DESC, id DESC", (rs, row) -> map(rs, true), userId, storyId);
    }

    public ExportResponse get(Long userId, Long exportId) {
        List<ExportResponse> rows = jdbc.query("SELECT * FROM export_task WHERE id=? AND user_id=?", (rs, row) -> map(rs, true), exportId, userId);
        if (rows.isEmpty()) throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "导出任务不存在");
        return rows.get(0);
    }

    public Download download(Long userId, Long exportId, String token) {
        String sql = userId == null ? "SELECT * FROM export_task WHERE id=?" : "SELECT * FROM export_task WHERE id=? AND user_id=?";
        Object[] args = userId == null ? new Object[] { exportId } : new Object[] { exportId, userId };
        List<Row> rows = jdbc.query(sql, (rs, row) -> new Row(
                rs.getLong("id"), rs.getString("format"), rs.getString("status"), rs.getString("file_name"),
                rs.getString("object_path"), rs.getString("content_type"), rs.getString("download_token_hash"), rs.getTimestamp("expires_time")), args);
        if (rows.isEmpty()) throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "导出任务不存在");
        Row row = rows.get(0);
        if (!"SUCCESS".equals(row.status()) || row.path() == null || !StringUtils.hasText(token) || !sha256(token).equals(row.tokenHash()) || row.expires() == null || row.expires().toLocalDateTime().isBefore(LocalDateTime.now())) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "EXPORT_LINK_INVALID", "下载链接无效或已过期");
        }
        Path path = Path.of(row.path()).toAbsolutePath().normalize();
        if (!path.startsWith(storageDir) || !Files.exists(path)) throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "EXPORT_FILE_NOT_FOUND", "导出文件不存在");
        return new Download(new FileSystemResource(path), MediaType.parseMediaType(row.contentType()), ContentDisposition.attachment().filename(row.fileName(), StandardCharsets.UTF_8).build().toString());
    }

    private ExportResponse map(java.sql.ResultSet rs, boolean issueToken) throws java.sql.SQLException {
        String status = rs.getString("status");
        String token = null;
        LocalDateTime expires = timestamp(rs.getTimestamp("expires_time"));
        if (issueToken && "SUCCESS".equals(status)) {
            token = issueToken(rs.getLong("id"));
            expires = LocalDateTime.now().plusMinutes(ttlMinutes);
        }
        String url = token == null ? null : "/api/exports/" + rs.getLong("id") + "/download?token=" + token;
        return new ExportResponse(rs.getLong("id"), rs.getLong("story_id"), rs.getLong("release_id"), ExportFormat.valueOf(rs.getString("format")), status,
                rs.getString("file_name"), rs.getObject("file_size", Long.class), rs.getString("content_type"), url, expires,
                rs.getString("error_message"), timestamp(rs.getTimestamp("created_time")), timestamp(rs.getTimestamp("updated_time")));
    }

    private String issueToken(Long id) {
        byte[] bytes = new byte[24]; RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        LocalDateTime expires = LocalDateTime.now().plusMinutes(ttlMinutes);
        jdbc.update("UPDATE export_task SET download_token_hash=?, expires_time=?, updated_time=CURRENT_TIMESTAMP WHERE id=?", sha256(token), expires, id);
        return token;
    }

    private byte[] render(ReleaseResponse release, ExportFormat format, boolean includeReport) throws IOException {
        ArrayNode chapters = mapper.createArrayNode();
        StringBuilder plain = new StringBuilder(release.title()).append("\n\n");
        if (StringUtils.hasText(release.summary())) plain.append("简介\n").append(release.summary()).append("\n\n");
        for (JsonNode snapshot : release.chapterVersions()) {
            StoryChapterVersion version = versions.selectById(snapshot.path("versionId").asLong());
            if (version == null || !"APPROVED".equals(version.getSourceType())) throw new IllegalStateException("正式版本中的章节版本不可用");
            String title = snapshot.path("title").asText("第" + snapshot.path("chapterNo").asInt() + "章");
            plain.append(title).append("\n\n").append(version.getContent()).append("\n\n");
            ObjectNode chapter = chapters.addObject(); chapter.put("chapterNo", snapshot.path("chapterNo").asInt()); chapter.put("title", title); chapter.put("content", version.getContent()); chapter.put("versionId", version.getId());
        }
        String report = includeReport ? reportText(release) : null;
        if (format == ExportFormat.TXT) {
            if (report != null) plain.append("终审报告\n\n").append(report).append("\n");
            return plain.toString().getBytes(StandardCharsets.UTF_8);
        }
        if (format == ExportFormat.MARKDOWN) return markdown(release, chapters, report).getBytes(StandardCharsets.UTF_8);
        if (format == ExportFormat.JSON) return json(release, chapters, includeReport).getBytes(StandardCharsets.UTF_8);
        return docx(release, chapters, report);
    }

    private String markdown(ReleaseResponse release, ArrayNode chapters, String report) {
        StringBuilder value = new StringBuilder("# ").append(release.title()).append("\n\n");
        if (StringUtils.hasText(release.summary())) value.append("## 简介\n\n").append(release.summary()).append("\n\n");
        for (JsonNode chapter : chapters) value.append("## ").append(chapter.path("title").asText()).append("\n\n").append(chapter.path("content").asText()).append("\n\n");
        if (report != null) value.append("## 终审报告\n\n```json\n").append(report).append("\n```\n");
        return value.toString();
    }

    private String json(ReleaseResponse release, ArrayNode chapters, boolean includeReport) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode story = root.putObject("story"); story.put("id", release.storyId()); story.put("title", release.title()); story.put("summary", release.summary());
        root.set("release", mapper.valueToTree(release));
        root.set("characters", release.characters() == null ? mapper.createArrayNode() : release.characters());
        root.set("outline", release.outline() == null ? mapper.createArrayNode() : release.outline());
        root.set("chapters", chapters);
        if (includeReport && release.reportId() != null) {
            List<String> reports = jdbc.queryForList("SELECT report_json FROM story_final_report WHERE id=?", String.class, release.reportId());
            if (!reports.isEmpty()) root.set("report", mapper.readTree(reports.get(0)));
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private byte[] docx(ReleaseResponse release, ArrayNode chapters, String report) {
        StringBuilder body = new StringBuilder();
        body.append(paragraph(release.title(), true));
        if (StringUtils.hasText(release.summary())) body.append(paragraph("简介\n" + release.summary(), false));
        for (JsonNode chapter : chapters) body.append(paragraph(chapter.path("title").asText(), true)).append(paragraph(chapter.path("content").asText(), false));
        if (report != null) body.append(paragraph("终审报告", true)).append(paragraph(report, false));
        return zipDocx(body.toString());
    }

    private String reportText(ReleaseResponse release) throws JsonProcessingException {
        if (release.reportId() == null) return "暂无终审报告";
        List<String> reports = jdbc.queryForList("SELECT report_json FROM story_final_report WHERE id=?", String.class, release.reportId());
        if (reports.isEmpty()) return "暂无终审报告";
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(reports.get(0)));
    }

    private byte[] zipDocx(String body) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            add(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            add(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            add(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body + "<w:sectPr/></w:body></w:document>");
            zip.finish();
            return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("DOCX 生成失败", exception); }
    }

    private void add(ZipOutputStream zip, String name, String value) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
    private String paragraph(String value, boolean heading) { StringBuilder xml = new StringBuilder("<w:p><w:r>"); if (heading) xml.append("<w:rPr><w:b/></w:rPr>"); return xml.append("<w:t xml:space=\"preserve\">").append(xmlEscape(value)).append("</w:t></w:r></w:p>").toString(); }
    private String xmlEscape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private String safeName(String value) { String safe = value == null ? "story" : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim(); return safe.isEmpty() ? "story" : safe.substring(0, Math.min(80, safe.length())); }
    private String contentType(ExportFormat format) { return switch (format) { case TXT -> "text/plain;charset=UTF-8"; case MARKDOWN -> "text/markdown;charset=UTF-8"; case JSON -> "application/json;charset=UTF-8"; case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; }; }
    private LocalDateTime timestamp(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private record Row(Long id, String format, String status, String fileName, String path, String contentType, String tokenHash, Timestamp expires) { }
    public record Download(Resource resource, MediaType contentType, String contentDisposition) { }
}
