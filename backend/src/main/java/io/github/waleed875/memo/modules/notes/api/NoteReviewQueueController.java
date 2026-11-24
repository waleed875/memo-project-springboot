package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteReviewQueueService;
import io.github.waleed875.memo.modules.notes.domain.NoteReviewQueueItem;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET only: never accept a caller-controlled reviewer ID. */
@RestController
public final class NoteReviewQueueController {
    private final NoteReviewQueueService service;
    private final SessionUserResolver sessions;

    public NoteReviewQueueController(NoteReviewQueueService service, SessionUserResolver sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/api/notes/review-queue")
    public List<NoteReviewQueueItem> queue(HttpServletRequest request) {
        return service.findForReviewer(sessions.resolve(request)
            .orElseThrow(UnauthenticatedMasterDataAccess::new));
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthenticated() {
        return ResponseEntity.status(401).build();
    }

    @ExceptionHandler(NoteAccessDenied.class)
    ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(403).build();
    }
}

