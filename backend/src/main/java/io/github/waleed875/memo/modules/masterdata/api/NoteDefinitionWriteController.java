package io.github.waleed875.memo.modules.masterdata.api;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.NoteDefinitionCommandService;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldDefinition;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A narrow HTTP adapter. Authorization and write mode enforced by application. */
@RestController
@RequestMapping("/api/master/note-types/fields")
public final class NoteDefinitionWriteController {
    private final NoteDefinitionCommandService commands;
    private final SessionUserResolver sessions;

    public NoteDefinitionWriteController(
        NoteDefinitionCommandService commands, SessionUserResolver sessions
    ) {
        this.commands = commands;
        this.sessions = sessions;
    }

    private UserProfile current(HttpServletRequest req) {
        return sessions.resolve(req).orElse(null);
    }

    @PostMapping
    public NoteTypeFieldDefinition save(
        @RequestBody DefinitionRequest request, HttpServletRequest req
    ) {
        return commands.save(current(req), new NoteTypeFieldDefinition(
            request.id(), request.noteTypeId(), request.fieldId(),
            request.fieldCode(), request.fieldNameAr(), request.description(),
            request.sortOrder() == null ? 0 : request.sortOrder(),
            request.isRequired() == null || request.isRequired(),
            request.isActive() == null || request.isActive()
        ));
    }

    @DeleteMapping("/{fieldId}")
    public Map<String, String> deactivate(@PathVariable UUID fieldId, HttpServletRequest req) {
        commands.deactivate(current(req), fieldId);
        return Map.of("message", "تم تعطيل حقل المؤشر بنجاح.");
    }

    public record DefinitionRequest(
        UUID id, UUID noteTypeId, UUID fieldId, String fieldCode,
        String fieldNameAr, String description, Integer sortOrder,
        Boolean isRequired, Boolean isActive
    ) {}

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    public ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(MasterDataForbiddenException.class)
    public ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler(MasterDataReadOnlyException.class)
    public ResponseEntity<Map<String, String>> readOnly(MasterDataReadOnlyException ex) {
        return ResponseEntity.status(409).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(MasterDataOperationException.class)
    public ResponseEntity<Map<String, String>> badRequest(MasterDataOperationException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
