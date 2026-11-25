package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.AdminApprovedMetadataEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminApprovedMetadataEditService;
import io.github.waleed875.memo.modules.notes.application.AdminNoteIndicatorEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteIndicatorEditService;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDraftMetadata;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Narrow internal administrative commands; NOT the legacy note PUT endpoint.
 * Business and row-level security stay in application and JDBC transaction layers.
 */
@RestController
@RequestMapping("/api/internal/notes/{id}/admin")
public final class AdminNoteEditCommandController {
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Set<String> INDICATORS_ROOT = Set.of(
        "expectedUpdatedAt", "markAllIndicatorsCorrect", "fieldValues");
    private static final Set<String> FIELD = Set.of(
        "fieldId", "value", "inactiveReason");
    private static final Set<String> METADATA_ROOT = Set.of(
        "expectedUpdatedAt", "metadata");
    private static final Set<String> METADATA = Set.of(
        "noteNumber", "companyId", "noteTypeId", "factoryId",
        "productionLineId", "writingDate", "arrivalAt");

    private final AdminNoteIndicatorEditService indicatorEdits;
    private final AdminApprovedMetadataEditService approvedMetadataEdits;
    private final SessionUserResolver sessions;

    public AdminNoteEditCommandController(
        AdminNoteIndicatorEditService indicatorEdits,
        AdminApprovedMetadataEditService approvedMetadataEdits,
        SessionUserResolver sessions
    ) {
        this.indicatorEdits = indicatorEdits;
        this.approvedMetadataEdits = approvedMetadataEdits;
        this.sessions = sessions;
    }

    @PutMapping(path = "/indicators", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> updateIndicators(
        @PathVariable UUID id, @RequestBody Map<String, Object> payload,
        HttpServletRequest request
    ) {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        var root = object(payload, INDICATORS_ROOT);
        LocalDateTime updated = indicatorEdits.updateIndicators(actor,
            new AdminNoteIndicatorEditCommand(
                id, revision(root.get("expectedUpdatedAt")),
                requiredBoolean(root.get("markAllIndicatorsCorrect")),
                indicators(root.get("fieldValues"))));
        return Map.of("id", id, "updatedAt", updated,
            "message", "تم تحديث مؤشرات المذكرة بنجاح.");
    }

    @PutMapping(path = "/approved-metadata", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> updateApprovedMetadata(
        @PathVariable UUID id, @RequestBody Map<String, Object> payload,
        HttpServletRequest request
    ) {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        var root = object(payload, METADATA_ROOT);
        Map<String, Object> values = object(root.get("metadata"), METADATA);
        // Complete-replacement semantics: explicit nullable values are required
        // to prevent accidental clearing when a client omits a field.
        if (!values.keySet().containsAll(METADATA))
            throw invalid("يجب إرسال جميع حقول بيانات المذكرة، بما فيها الحقول الاختيارية.");
        NoteDraftMetadata metadata = new NoteDraftMetadata(
            requiredText(values.get("noteNumber")),
            uuid(values.get("companyId"), true),
            uuid(values.get("noteTypeId"), true),
            uuid(values.get("factoryId"), false),
            uuid(values.get("productionLineId"), false),
            date(values.get("writingDate"), false),
            date(values.get("arrivalAt"), true));
        if (metadata.productionLineId() != null && metadata.factoryId() == null)
            throw invalid("لا يمكن اختيار خط إنتاج دون مصنع.");
        LocalDateTime updated = approvedMetadataEdits.edit(actor,
            new AdminApprovedMetadataEditCommand(
                id, revision(root.get("expectedUpdatedAt")), metadata));
        return Map.of("id", id, "updatedAt", updated,
            "message", "تم تحديث بيانات المذكرة المعتمدة بنجاح.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, Set<String> allowed) {
        if (!(value instanceof Map<?, ?> map))
            throw invalid("بنية طلب التعديل غير صالحة.");
        for (Object key : map.keySet()) {
            if (!(key instanceof String field) || !allowed.contains(field))
                throw invalid("يتضمن الطلب حقلاً غير مدعوم: " + key);
        }
        return (Map<String, Object>) map;
    }

    private static LocalDateTime revision(Object value) {
        if (!(value instanceof String text)) throw invalid("نسخة التعديل مطلوبة.");
        try {
            // Preserve the PostgreSQL timestamp-without-timezone revision as-is.
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException error) {
            throw invalid("نسخة التعديل يجب أن تكون تاريخًا محليًا ISO-8601 دون منطقة زمنية.");
        }
    }

    private static boolean requiredBoolean(Object value) {
        if (!(value instanceof Boolean result)) throw invalid("الخيار المنطقي مطلوب.");
        return result;
    }

    private static String requiredText(Object value) {
        if (!(value instanceof String result) || result.isBlank())
            throw invalid("رقم المذكرة مطلوب.");
        return result;
    }

    private static String optionalText(Object value) {
        if (value == null) return null;
        if (!(value instanceof String result)) throw invalid("نص سبب التعطيل غير صالح.");
        return result;
    }

    private static UUID uuid(Object value, boolean required) {
        if (value == null && !required) return null;
        if (!(value instanceof String text) || !UUID_PATTERN.matcher(text).matches())
            throw invalid("معرف المذكرة أو المرجع غير صالح.");
        UUID result = UUID.fromString(text);
        if (result.equals(new UUID(0, 0))) throw invalid("المعرف الفارغ غير مسموح.");
        return result;
    }

    private static LocalDate date(Object value, boolean required) {
        if (value == null && !required) return null;
        if (!(value instanceof String text)) throw invalid("صيغة التاريخ غير صالحة.");
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException error) {
            throw invalid("التاريخ يجب أن يكون YYYY-MM-DD.");
        }
    }

    private static List<AdminNoteIndicatorEditCommand.IndicatorInput> indicators(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 250)
            throw invalid("قائمة المؤشرات مطلوبة ولا تتجاوز 250 بندًا.");
        List<AdminNoteIndicatorEditCommand.IndicatorInput> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Object raw : list) {
            var field = object(raw, FIELD);
            UUID id = uuid(field.get("fieldId"), true);
            if (!seen.add(id)) throw invalid("لا يمكن تكرار المؤشر.");
            if (!(field.get("value") instanceof String name))
                throw invalid("قيمة المؤشر مطلوبة.");
            IndicatorValue enumValue;
            try {
                enumValue = IndicatorValue.valueOf(name);
            } catch (IllegalArgumentException error) {
                throw invalid("قيمة المؤشر غير معروفة.");
            }
            result.add(new AdminNoteIndicatorEditCommand.IndicatorInput(
                id, enumValue, optionalText(field.get("inactiveReason"))));
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
    ResponseEntity<Map<String, String>> conflict(RuntimeException error) {
        return ResponseEntity.status(409).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler({NoteSaveValidationException.class, MasterDataOperationException.class})
    ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
