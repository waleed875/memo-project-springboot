package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteDetailsQueryService;
import io.github.waleed875.memo.modules.notes.domain.NoteDetails;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET only. All mutations, search, review and attachment URLs remain denied. */
@RestController
@RequestMapping("/api/notes")
public final class NoteDetailsController {
    private final NoteDetailsQueryService queries;
    private final SessionUserResolver sessions;

    public NoteDetailsController(NoteDetailsQueryService queries, SessionUserResolver sessions) {
        this.queries = queries;
        this.sessions = sessions;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> find(@PathVariable String id, HttpServletRequest request) {
        UserProfile actor = sessions.resolve(request)
            .orElseThrow(UnauthenticatedMasterDataAccess::new);
        UUID noteId;
        try {
            noteId = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404)
                .body(Map.of("message", "المذكرة غير موجودة."));
        }
        return queries.find(actor, noteId)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(404)
                .body(Map.of("message", "المذكرة غير موجودة.")));
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    public ResponseEntity<Void> unauthenticated() {
        return ResponseEntity.status(401).build();
    }

    @ExceptionHandler(NoteAccessDenied.class)
    public ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(403).build();
    }
}
