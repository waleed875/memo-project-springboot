package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.AdminNoteTextEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteTextEditService;
import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditCommand;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditService;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
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

/**
 * Explicit opt-in edit operations, deliberately separate from legacy PUT /api/notes/{id}.
 *
 * Owner draft editing REPLACES the entire active indicator set, so clients must
 * supply all current field values. Omitted links, metadata and attachment are
 * preserved by the existing transaction. Administrator text editing preserves
 * reviewer, approval and review history.
 */
@RestController
@RequestMapping("/api/internal/notes/{id}")
public final class NoteEditCommandController {
    private static final Set<String> DRAFT_FIELDS = Set.of(
        "expectedUpdatedAt", "notes", "saveAsDraft",
        "markAllIndicatorsCorrect", "fieldValues");
    private static final Set<String> ADMIN_TEXT_FIELDS =
        Set.of("expectedUpdatedAt", "notes");
    private static final Set<String> INDICATOR_FIELDS =
        Set.of("fieldId", "value", "inactiveReason");

    private final NoteDraftEditService ownerEdits;
    private final AdminNoteTextEditService adminEdits;
    private final SessionUserResolver sessions;

    public NoteEditCommandController(NoteDraftEditService ownerEdits,
                                     AdminNoteTextEditService adminEdits,
                                     SessionUserResolver sessions) {
        this.ownerEdits = ownerEdits;
        this.adminEdits = adminEdits;
        this.sessions = sessions;
    }

    @PutMapping(path = "/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> editOwnerDraft(@PathVariable UUID id,
                                              @RequestBody Map<String, Object> payload,
                                              HttpServletRequest request) {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        Map<String, Object> body = strictObject(payload, DRAFT_FIELDS);
        require(body, "notes");
        NoteDraftEditCommand command = new NoteDraftEditCommand(
            id, revision(body.get("expectedUpdatedAt")),
            optionalText(body.get("notes")),
            requiredBoolean(body.get("saveAsDraft")),
            requiredBoolean(body.get("markAllIndicatorsCorrect")),
            indicators(body.get("fieldValues")),
            null, null, null, null);
        NoteDraftEditRepository.EditResult updated = ownerEdits.edit(actor, command);
        return Map.of(
            "id", updated.noteId(),
            "status", updated.status().name(),
            "updatedAt", updated.updatedAt(),
            "message", "تم تحديث المذكرة بنجاح.");
    }

    @PutMapping(path = "/admin/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> editAdminText(@PathVariable UUID id,
                                             @RequestBody Map<String, Object> payload,
                                             HttpServletRequest request) {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        Map<String, Object> body = strictObject(payload, ADMIN_TEXT_FIELDS);
        require(body, "notes");
        LocalDateTime updatedAt = adminEdits.updateText(actor,
            new AdminNoteTextEditCommand(
                id, revision(body.get("expectedUpdatedAt")),
                optionalText(body.get("notes"))));
        return Map.of("id", id, "updatedAt", updatedAt,
            "message", "تم تحديث نص المذكرة بنجاح.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> strictObject(Object raw, Set<String> allowed) {
        if (!(raw instanceof Map<?, ?> map))
            throw invalid("بنية بيانات التعديل غير صالحة.");
        for (Object key : map.keySet()) {
            if (!(key instanceof String text) || !allowed.contains(text))
                throw invalid("يتضمن طلب التعديل حقلاً غير مدعوم: " + key);
        }
        return (Map<String, Object>) map;
    }

    private static void require(Map<String, Object> input, String key) {
        if (!input.containsKey(key))
            throw invalid("الحقل مطلوب: " + key);
    }

    private static LocalDateTime revision(Object raw) {
        if (!(raw instanceof String value))
            throw invalid("نسخة التعديل expectedUpdatedAt مطلوبة.");
        try {
            // Legacy PostgreSQL timestamp without a timezone. Do not silently
            // convert a timezone-bearing instant to an unrelated revision.
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalid("نسخة التعديل يجب أن تكون بصيغة تاريخ ووقت محلي ISO-8601.");
        }
    }

    private static String optionalText(Object raw) {
        if (raw == null) return null; // Explicit null clears optional note text.
        if (!(raw instanceof String text))
            throw invalid("نص المذكرة غير صالح.");
        return text;
    }

    private static boolean requiredBoolean(Object raw) {
        if (!(raw instanceof Boolean flag))
            throw invalid("الخيار المنطقي مطلوب ويجب أن يكون true أو false.");
        return flag;
    }

    private static UUID fieldId(Object raw) {
        if (!(raw instanceof String value)) throw invalid("معرّف المؤشر غير صالح.");
        try {
            UUID id = UUID.fromString(value);
            if (id.equals(new UUID(0, 0))) throw invalid("معرّف المؤشر فارغ.");
            return id;
        } catch (IllegalArgumentException ex) {
            throw invalid("معرّف المؤشر غير صالح.");
        }
    }

    private static List<NoteDraftEditCommand.IndicatorInput> indicators(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() > 250)
            throw invalid("قائمة المؤشرات مطلوبة ولا تتجاوز 250 بندًا.");
        List<NoteDraftEditCommand.IndicatorInput> parsed = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Object value : values) {
            Map<String, Object> item = strictObject(value, INDICATOR_FIELDS);
            UUID id = fieldId(item.get("fieldId"));
            if (!seen.add(id)) throw invalid("معرّف المؤشر مكرر.");
            if (!(item.get("value") instanceof String spelling))
                throw invalid("قيمة المؤشر مطلوبة.");
            IndicatorValue indicator;
            try {
                indicator = IndicatorValue.valueOf(spelling);
            } catch (IllegalArgumentException ex) {
                throw invalid("قيمة المؤشر غير معروفة.");
            }
            String reason = optionalText(item.get("inactiveReason"));
            parsed.add(new NoteDraftEditCommand.IndicatorInput(id, indicator, reason));
        }
        return List.copyOf(parsed);
    }

    private static NoteSaveValidationException invalid(String message) {
        return new NoteSaveValidationException(message);
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler({MasterDataForbiddenException.class, NoteAccessDenied.class})
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler({NoteDraftEditConflict.class, MasterDataReadOnlyException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException ex) {
        return ResponseEntity.status(409).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler({NoteSaveValidationException.class, MasterDataOperationException.class})
    ResponseEntity<Map<String, String>> invalidRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
