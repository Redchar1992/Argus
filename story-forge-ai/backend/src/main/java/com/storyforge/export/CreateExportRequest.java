package com.storyforge.export;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateExportRequest(
        @Positive Long releaseId,
        @NotNull ExportFormat format,
        boolean includeReport
) { }
