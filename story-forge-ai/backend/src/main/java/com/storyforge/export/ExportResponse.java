package com.storyforge.export;

import java.time.LocalDateTime;

public record ExportResponse(
        Long exportId,
        Long storyId,
        Long releaseId,
        ExportFormat format,
        String status,
        String fileName,
        Long fileSize,
        String contentType,
        String downloadUrl,
        LocalDateTime expiresAt,
        String errorMessage,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) { }
