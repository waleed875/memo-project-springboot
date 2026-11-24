package io.github.waleed875.memo.modules.masterdata.api;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.NoteDefinitionQueryService;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.masterdata.domain.NoteType;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeField;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldImpactPreview;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only dictionary and impact preview HTTP adapter.
 * Preview POST is computation only; Spring CSRF remains required as a
 * deliberate security hardening of the original endpoint.
 */
@RestController
@RequestMapping("/api/master/note-types")
public final class NoteDefinitionReadController {
    private final NoteDefinitionQueryService queries;
    private final SessionUserResolver sessions;

    public NoteDefinitionReadController(
        NoteDefinitionQueryService queries, SessionUserResolver sessions
    ) {
        this.queries = queries;
        this.sessions = sessions;
    }

    private UserProfile current(HttpServletRequest request) {
        return sessions.resolve(request).orElse(null);
    }

    @GetMapping
    public List<NoteType> noteTypes(HttpServletRequest req) {
        return queries.noteTypes(current(req));
    }

    @GetMapping("/{id}/fields")
    public ResponseEntity<List<NoteTypeField>> fields(
        @PathVariable String id, HttpServletRequest req
    ) {
        UserProfile actor = current(req);
        if (actor == null) throw new UnauthenticatedMasterDataAccess();
        try {
            return ResponseEntity.ok(queries.fieldsForType(actor, UUID.fromString(id)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/fields/preview")
    public NoteTypeFieldImpactPreview preview(
        @RequestBody PreviewDefinition body, HttpServletRequest req
    ) {
        return queries.preview(current(req), body.noteTypeId(), body.fieldId());
    }

    /** Full source request shape; preview uses noteTypeId and optional fieldId. */
    public record PreviewDefinition(
        UUID id, UUID noteTypeId, UUID fieldId, String fieldCode,
        String fieldNameAr, String description, Integer sortOrder,
        Boolean isRequired, Boolean isActive
    ) {}

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    public ResponseEntity<Void> unauthenticated() {
        return ResponseEntity.status(401).build();
    }

    @ExceptionHandler(MasterDataForbiddenException.class)
    public ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(403).build();
    }

    @ExceptionHandler(MasterDataOperationException.class)
    public ResponseEntity<Map<String, String>> invalid(MasterDataOperationException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
