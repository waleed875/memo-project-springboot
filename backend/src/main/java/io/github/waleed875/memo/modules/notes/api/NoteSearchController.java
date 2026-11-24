package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.InvalidNoteSearch;
import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteSearchService;
import io.github.waleed875.memo.modules.notes.domain.NoteSearchCriteria;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import io.github.waleed875.memo.modules.notes.domain.PagedNotes;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only /api/notes search adapter; no SQL and no mutable user IDs. */
@RestController
public final class NoteSearchController {
    private final NoteSearchService service;
    private final SessionUserResolver sessions;

    public NoteSearchController(NoteSearchService service, SessionUserResolver sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/api/notes")
    public PagedNotes search(
        @RequestParam(required = false) String noteNumber,
        @RequestParam(required = false) UUID companyId,
        @RequestParam(required = false) UUID factoryId,
        @RequestParam(required = false) UUID productionLineId,
        @RequestParam(required = false) UUID noteTypeId,
        @RequestParam(required = false) UUID createdBy,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String dateBasis,
        @RequestParam(required = false) Boolean hasWritingDate,
        @RequestParam(required = false) Boolean unlinkedOnly,
        @RequestParam(required = false) Boolean isLoot,
        @RequestParam(required = false) Boolean isInvoice,
        @RequestParam(required = false) String entryCutoffDate,
        @RequestParam(defaultValue = "1") int pageNumber,
        @RequestParam(defaultValue = "25") int pageSize,
        HttpServletRequest request
    ) {
        UserProfile actor = sessions.resolve(request)
            .orElseThrow(UnauthenticatedMasterDataAccess::new);

        NoteStatus parsedStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                parsedStatus = NoteStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException badStatus) {
                throw new InvalidNoteSearch("حالة المذكرة غير صالحة.");
            }
        }

        return service.search(actor, new NoteSearchCriteria(
            noteNumber, companyId, factoryId, productionLineId, noteTypeId,
            createdBy, parsedStatus,
            LegacySearchDateParser.parse(from, false),
            LegacySearchDateParser.parse(to, true), dateBasis,
            hasWritingDate, unlinkedOnly, isLoot, isInvoice,
            LegacySearchDateParser.parse(entryCutoffDate, true),
            pageNumber, pageSize
        ));
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    public ResponseEntity<Void> unauthenticated() {
        return ResponseEntity.status(401).build();
    }

    @ExceptionHandler(NoteAccessDenied.class)
    public ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(403).build();
    }

    @ExceptionHandler(InvalidNoteSearch.class)
    public ResponseEntity<Map<String, String>> badSearch(InvalidNoteSearch ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
