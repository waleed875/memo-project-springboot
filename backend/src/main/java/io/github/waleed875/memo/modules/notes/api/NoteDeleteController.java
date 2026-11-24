package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.NoteDeleteConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDeleteService;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Existing DELETE contract: ?expectedUpdatedAt=..., CSRF and admin session. */
@RestController
@RequestMapping("/api/notes")
public class NoteDeleteController {
    private final NoteDeleteService commands;
    private final SessionUserResolver sessions;

    public NoteDeleteController(NoteDeleteService commands, SessionUserResolver sessions) {
        this.commands = commands;
        this.sessions = sessions;
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(
        @PathVariable UUID id,
        @RequestParam LocalDateTime expectedUpdatedAt,
        HttpServletRequest request
    ) {
        UserProfile actor = sessions.resolve(request).orElse(null);
        commands.delete(actor, id, expectedUpdatedAt);
        return Map.of("message", "تم حذف المذكرة بنجاح.");
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler({NoteDeleteConflict.class, MasterDataReadOnlyException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException ex) {
        return ResponseEntity.status(409).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(MasterDataOperationException.class)
    ResponseEntity<Map<String, String>> invalid(MasterDataOperationException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
