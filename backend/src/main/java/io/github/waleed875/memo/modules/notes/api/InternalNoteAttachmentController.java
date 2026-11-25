package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentDeleteCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentDeleteService;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentUploadCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentUploadService;
import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteAttachmentDownloadService;
import io.github.waleed875.memo.modules.notes.application.NoteAttachmentNotFound;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachmentPolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Restricted write operations and owner-scoped downloads, never the legacy attachment routes. */
@RestController
@RequestMapping("/api/internal/notes/{id}")
public final class InternalNoteAttachmentController {
    private final AdminNoteAttachmentUploadService uploads;
    private final AdminNoteAttachmentDeleteService deletes;
    private final NoteAttachmentDownloadService downloads;
    private final SessionUserResolver sessions;

    public InternalNoteAttachmentController(
        AdminNoteAttachmentUploadService uploads,
        AdminNoteAttachmentDeleteService deletes,
        NoteAttachmentDownloadService downloads,
        SessionUserResolver sessions
    ) {
        this.uploads = uploads;
        this.deletes = deletes;
        this.downloads = downloads;
        this.sessions = sessions;
    }

    @PostMapping(path = "/admin/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
        @PathVariable UUID id,
        @RequestParam("expectedUpdatedAt") String expectedUpdatedAt,
        @RequestPart("file") MultipartFile file,
        HttpServletRequest request
    ) throws IOException {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        if (file.isEmpty() || file.getSize() > NoteAttachmentPolicy.MAX_BYTES)
            throw invalid("الملف فارغ أو يتجاوز 10 ميغابايت.");
        NoteAttachment attachment = new NoteAttachment(file.getOriginalFilename(), file.getBytes());
        var result = uploads.upload(actor, new AdminNoteAttachmentUploadCommand(
            id, revision(expectedUpdatedAt), attachment));
        return Map.of(
            "id", result.attachmentId(), "updatedAt", result.updatedAt(),
            "contentType", result.contentType(), "fileSize", result.fileSize(),
            "message", "تم رفع المرفق بنجاح.");
    }

    @DeleteMapping("/admin/attachments/{attachmentId}")
    public Map<String, Object> delete(
        @PathVariable UUID id, @PathVariable UUID attachmentId,
        @RequestParam("expectedUpdatedAt") String expectedUpdatedAt,
        HttpServletRequest request
    ) {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        var result = deletes.delete(actor, new AdminNoteAttachmentDeleteCommand(
            id, attachmentId, revision(expectedUpdatedAt)));
        // Nullable primary name is intentionally not inserted in Map.of.
        return Map.of(
            "id", result.attachmentId(), "updatedAt", result.updatedAt(),
            "message", "تم حذف المرفق بنجاح.");
    }

    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<byte[]> download(
        @PathVariable UUID id, @PathVariable UUID attachmentId,
        HttpServletRequest request
    ) {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        var file = downloads.find(actor, id, attachmentId);
        if (file.isEmpty()) return ResponseEntity.notFound().build();
        var value = file.get();
        byte[] bytes = value.bytes();
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(bytes.length)
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(safeName(value.fileName()), StandardCharsets.UTF_8)
                    .build().toString())
            .body(bytes);
    }

    static String safeName(String input) {
        StringBuilder safe = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (safe.length() >= 150) break;
            if (Character.isISOControl(c) || c == '/' || c == 92 || c == '"' || c == ';')
                safe.append('_');
            else safe.append(c);
        }
        if (safe.isEmpty() || safe.toString().isBlank()) return "attachment";
        return safe.toString();
    }

    private static LocalDateTime revision(String input) {
        try {
            return LocalDateTime.parse(input);
        } catch (DateTimeParseException ex) {
            throw invalid("نسخة التعديل يجب أن تكون تاريخًا محليًا ISO-8601 دون منطقة زمنية.");
        }
    }
    private static NoteSaveValidationException invalid(String message) {
        return new NoteSaveValidationException(message);
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler({MasterDataForbiddenException.class, NoteAccessDenied.class})
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }
    @ExceptionHandler(NoteAttachmentNotFound.class)
    ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }
    @ExceptionHandler({NoteDraftEditConflict.class, MasterDataReadOnlyException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException error) {
        return ResponseEntity.status(409).body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler(NoteSaveValidationException.class)
    ResponseEntity<Map<String, String>> badRequest(NoteSaveValidationException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
