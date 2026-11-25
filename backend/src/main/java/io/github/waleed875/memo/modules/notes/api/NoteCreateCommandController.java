package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.NoteCreateCommand;
import io.github.waleed875.memo.modules.notes.application.NoteCreateConflict;
import io.github.waleed875.memo.modules.notes.application.NoteCreateService;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateClassification;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateResolution;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicit opt-in command API, NOT the legacy SaveNoteRequest endpoint.
 * Reject all unrecognized fields so legacy source/invoice/attachment flags
 * cannot silently disappear while creating a partial memo.
 *
 * Spring Security enforces session CSRF; the application service verifies
 * the current role and the repository revalidates it in the transaction.
 */
@RestController
@RequestMapping("/api/internal/notes")
public final class NoteCreateCommandController {
    private static final Set<String> ROOT = Set.of(
        "operationId", "noteId", "candidate", "notes", "relatedNoteIds", "duplicateResolution");
    private static final Set<String> CANDIDATE = Set.of(
        "noteNumber", "companyId", "noteTypeId", "factoryId", "productionLineId",
        "writingDate", "arrivalAt", "saveAsDraft", "markAllIndicatorsCorrect", "fieldValues");
    private final NoteCreateService service;
    private final SessionUserResolver sessions;

    public NoteCreateCommandController(NoteCreateService service, SessionUserResolver sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> create(@RequestBody Map<String, Object> payload,
                                      HttpServletRequest request) {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        NoteCreateCommand command = parse(payload);
        UUID id = service.create(actor, command);
        return Map.of("id", id, "message", "تم حفظ المذكرة بنجاح.");
    }

    static NoteCreateCommand parse(Map<String, Object> payload) {
        Map<String, Object> root = object(payload, ROOT);
        Map<String, Object> input = object(root.get("candidate"), CANDIDATE);
        List<NoteSaveCandidate.IndicatorInput> values = indicators(input.get("fieldValues"));
        NoteSaveCandidate candidate = new NoteSaveCandidate(
            string(input.get("noteNumber"), true),
            uuid(input.get("companyId"), true),
            uuid(input.get("noteTypeId"), true),
            uuid(input.get("factoryId"), false),
            uuid(input.get("productionLineId"), false),
            date(input.get("writingDate"), false),
            date(input.get("arrivalAt"), true),
            flag(input.get("saveAsDraft")),
            flag(input.get("markAllIndicatorsCorrect")),
            values);
        NoteDuplicateResolution decision = null;
        if (root.get("duplicateResolution") != null) {
            var resolution = object(root.get("duplicateResolution"),
                Set.of("classification", "matchedNoteId"));
            if (!"COMPANY_DUPLICATE_NUMBER".equals(resolution.get("classification"))) {
                throw invalid("تصنيف معالجة التكرار غير مدعوم في مسار الإنشاء.");
            }
            decision = new NoteDuplicateResolution(
                NoteDuplicateClassification.COMPANY_DUPLICATE_NUMBER,
                uuid(resolution.get("matchedNoteId"), true));
        }
        return new NoteCreateCommand(
            uuid(root.get("operationId"), true), uuid(root.get("noteId"), true),
            candidate, string(root.get("notes"), false), null,
            links(root.get("relatedNoteIds")), decision);
    }

    /** Strict tree decoding avoids Jackson's default ignore-unknown-properties behavior. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, Set<String> allowed) {
        if (!(raw instanceof Map<?, ?> map)) throw invalid("بنية بيانات الطلب غير صالحة.");
        for (Object key : map.keySet()) {
            if (!(key instanceof String field) || !allowed.contains(field)) {
                throw invalid("يتضمن الطلب حقلاً غير مدعوم: " + key);
            }
        }
        return (Map<String, Object>) map;
    }

    private static String string(Object raw, boolean required) {
        if (raw == null && !required) return null;
        if (!(raw instanceof String text) || (required && text.isBlank()))
            throw invalid("قيمة النص في الطلب غير صالحة.");
        return text;
    }

    private static UUID uuid(Object raw, boolean required) {
        if (raw == null && !required) return null;
        if (!(raw instanceof String value)) throw invalid("معرّف الطلب غير صالح.");
        try {
            UUID id = UUID.fromString(value);
            if (id.equals(new UUID(0L, 0L))) throw invalid("المعرّف الفارغ غير مسموح.");
            return id;
        } catch (IllegalArgumentException ex) {
            throw invalid("معرّف الطلب غير صالح.");
        }
    }

    private static LocalDate date(Object raw, boolean required) {
        if (raw == null && !required) return null;
        if (!(raw instanceof String value)) throw invalid("التاريخ غير صالح.");
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalid("التاريخ يجب أن يكون بصيغة YYYY-MM-DD.");
        }
    }

    private static boolean flag(Object raw) {
        if (raw == null) return false;
        if (!(raw instanceof Boolean value)) throw invalid("قيمة الخيار المنطقي غير صالحة.");
        return value;
    }

    private static List<NoteSaveCandidate.IndicatorInput> indicators(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > 250)
            throw invalid("قائمة المؤشرات مفقودة أو تتجاوز الحد المسموح.");
        List<NoteSaveCandidate.IndicatorInput> items = new ArrayList<>(list.size());
        Set<UUID> seen = new HashSet<>();
        for (Object element : list) {
            var field = object(element, Set.of("fieldId", "value"));
            UUID fieldId = uuid(field.get("fieldId"), true);
            if (!seen.add(fieldId)) throw invalid("لا يمكن تكرار المؤشر.");
            String spelling = string(field.get("value"), true);
            try {
                items.add(new NoteSaveCandidate.IndicatorInput(
                    fieldId, IndicatorValue.valueOf(spelling)));
            } catch (IllegalArgumentException ex) {
                throw invalid("قيمة المؤشر غير معروفة.");
            }
        }
        return List.copyOf(items);
    }

    private static List<UUID> links(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > 100)
            throw invalid("روابط المذكرات غير صالحة.");
        List<UUID> ids = new ArrayList<>(list.size());
        Set<UUID> seen = new HashSet<>();
        for (Object item : list) {
            UUID id = uuid(item, true);
            if (!seen.add(id)) throw invalid("لا يمكن تكرار رابط مذكرة.");
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static NoteSaveValidationException invalid(String message) {
        return new NoteSaveValidationException(message);
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler({NoteCreateConflict.class, MasterDataReadOnlyException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException ex) {
        return ResponseEntity.status(409).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler({NoteSaveValidationException.class, MasterDataOperationException.class})
    ResponseEntity<Map<String, String>> invalidRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
