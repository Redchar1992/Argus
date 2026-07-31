package com.storyforge.export;

import java.io.IOException;
import java.util.List;

import com.storyforge.common.security.AuthenticatedUser;

import jakarta.validation.Valid;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ExportController {
    private final ExportService service;

    public ExportController(ExportService service) { this.service = service; }

    @PostMapping("/stories/{storyId}/exports")
    public ExportResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                 @PathVariable Long storyId,
                                 @Valid @RequestBody CreateExportRequest request) {
        return service.create(user.userId(), storyId, request);
    }

    @GetMapping("/stories/{storyId}/exports")
    public List<ExportResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                     @PathVariable Long storyId) {
        return service.list(user.userId(), storyId);
    }

    @GetMapping("/exports/{exportId}")
    public ExportResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                              @PathVariable Long exportId) {
        return service.get(user.userId(), exportId);
    }

    @GetMapping("/exports/{exportId}/download")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal(errorOnInvalidType = false) AuthenticatedUser user,
                                             @PathVariable Long exportId,
                                             @RequestParam String token) throws IOException {
        ExportService.Download download = service.download(user == null ? null : user.userId(), exportId, token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, download.contentDisposition())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(download.contentType())
                .contentLength(download.resource().contentLength())
                .body(download.resource());
    }
}
