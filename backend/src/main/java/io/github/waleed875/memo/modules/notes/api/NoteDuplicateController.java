package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateQueryService;
import io.github.waleed875.memo.modules.notes.domain.NoteDuplicate;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only legacy duplicate-check route, not duplicate resolution. */
@RestController
public final class NoteDuplicateController {
    private final NoteDuplicateQueryService query;
    private final SessionUserResolver sessions;

    public NoteDuplicateController(
        NoteDuplicateQueryService query, SessionUserResolver sessions
    ) {
        this.query = query;
        this.sessions = sessions;
    }

    @GetMapping("/api/notes/duplicates/check")
    public List<NoteDuplicate> check(
        @RequestParam String noteNumber,
        @RequestParam UUID noteTypeId,
        @RequestParam UUID companyId,
        @RequestParam(required = false) String writingDate,
        @RequestParam(required = false) UUID currentNoteId,
        @RequestParam(defaultValue = "false") boolean isInvoice,
        HttpServletRequest request
    ) {
        UserProfile actor = sessions.resolve(request)
            .orElseThrow(UnauthenticatedMasterDataAccess::new);
        return query.find(actor, currentNoteId, noteNumber, noteTypeId,
            companyId, LegacySearchDateParser.parse(writingDate, false), isInvoice);
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }
}
