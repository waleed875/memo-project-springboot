package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncService;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Restricted internal atomic admin link-set replacement, not legacy /link or /unlink. */
@RestController
@RequestMapping("/api/internal/notes/{id}/admin/links")
public final class InternalNoteLinksController {
    private final AdminNoteLinkSyncService service;
    private final SessionUserResolver sessions;

    public InternalNoteLinksController(AdminNoteLinkSyncService service,
                                       SessionUserResolver sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> replace(@PathVariable UUID id,
                                        @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        if (body == null || !body.keySet().equals(
            Set.of("expectedUpdatedAt", "expectedRelatedNoteIds", "relatedNoteIds")))
            throw invalid("يتطلب تعديل الروابط نسخة المذكرة والقائمتين الصريحتين دون حقول إضافية.");
        var command = new AdminNoteLinkSyncCommand(
            id, revision(body.get("expectedUpdatedAt")),
            ids(body.get("expectedRelatedNoteIds")),
            ids(body.get("relatedNoteIds")));
        var result = service.sync(actor, command);
        return Map.of(
            "id", result.noteId(), "relatedNoteIds", result.relatedNoteIds(),
            "added", result.added(), "removed", result.removed(),
            "updatedAt", result.updatedAt(),
            "message", "تم تحديث روابط المذكرة بنجاح.");
    }

    private static LocalDateTime revision(Object raw) {
        if (!(raw instanceof String s)) throw invalid("نسخة التعديل مطلوبة.");
        try { return LocalDateTime.parse(s); }
        catch (DateTimeParseException ex) { throw invalid("نسخة التعديل غير صالحة."); }
    }

    private static List<UUID> ids(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > 100)
            throw invalid("قائمة الروابط مطلوبة ولا تتجاوز 100 رابط.");
        var result = new ArrayList<UUID>();
        for (Object item : list) {
            if (!(item instanceof String text)) throw invalid("معرّف الرابط غير صالح.");
            try { result.add(UUID.fromString(text)); }
            catch (IllegalArgumentException ex) { throw invalid("معرّف الرابط غير صالح."); }
        }
        return List.copyOf(result);
    }

    private static NoteSaveValidationException invalid(String message) {
        return new NoteSaveValidationException(message);
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }
    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }
    @ExceptionHandler({NoteDraftEditConflict.class, MasterDataReadOnlyException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException ex) {
        return ResponseEntity.status(409).body(Map.of("message", ex.getMessage()));
    }
    @ExceptionHandler(NoteSaveValidationException.class)
    ResponseEntity<Map<String, String>> invalidRequest(NoteSaveValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
