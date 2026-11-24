package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.NoteReviewConflict;
import io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionService;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legacy review action paths; Spring Security enforces CSRF before controllers.
 * No client-controlled reviewer/updated-by identity is accepted.
 */
@RestController
@RequestMapping("/api/notes")
public final class NoteReviewTransitionController {
    public record ReviewActionRequest(LocalDateTime expectedUpdatedAt) {}
    public record ReviewReasonRequest(String reason, LocalDateTime expectedUpdatedAt) {}

    private final NoteReviewTransitionService service;
    private final SessionUserResolver sessions;

    public NoteReviewTransitionController(
        NoteReviewTransitionService service, SessionUserResolver sessions
    ) {
        this.service = service;
        this.sessions = sessions;
    }

    @PostMapping("/{id}/start-review")
    public Map<String, String> start(@PathVariable UUID id,
        @RequestBody(required = false) ReviewActionRequest body,
        HttpServletRequest request
    ) {
        service.start(sessions.resolve(request).orElse(null), id,
            body == null ? null : body.expectedUpdatedAt());
        return Map.of("message", "بدأت عملية المراجعة.");
    }

    @PostMapping("/{id}/return")
    public Map<String, String> returned(@PathVariable UUID id,
        @RequestBody(required = false) ReviewReasonRequest body,
        HttpServletRequest request
    ) {
        service.returnForCorrection(sessions.resolve(request).orElse(null), id,
            body == null ? null : body.reason(),
            body == null ? null : body.expectedUpdatedAt());
        return Map.of("message", "تمت إعادة المذكرة للتصحيح.");
    }

    @PostMapping("/{id}/release-review")
    public Map<String, String> release(@PathVariable UUID id,
        @RequestBody(required = false) ReviewReasonRequest body,
        HttpServletRequest request
    ) {
        service.release(sessions.resolve(request).orElse(null), id,
            body == null ? null : body.reason());
        return Map.of("message", "تم تحرير قفل المراجعة.");
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler({NoteReviewConflict.class, MasterDataReadOnlyException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException error) {
        return ResponseEntity.status(409).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(MasterDataOperationException.class)
    ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}

