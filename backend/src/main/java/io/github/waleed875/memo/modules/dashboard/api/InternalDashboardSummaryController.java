package io.github.waleed875.memo.modules.dashboard.api;

import io.github.waleed875.memo.modules.dashboard.application.DashboardCoreSnapshot;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryService;
import io.github.waleed875.memo.modules.dashboard.application.InvalidDashboardSummaryRequest;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal scalar-only dashboard diagnostic endpoint. It must NOT impersonate
 * original /api/dashboard/snapshot, which has many more required collections.
 */
@RestController
public final class InternalDashboardSummaryController {
    private final DashboardSummaryService summaries;
    private final SessionUserResolver sessions;

    public InternalDashboardSummaryController(
        DashboardSummaryService summaries, SessionUserResolver sessions
    ) {
        this.summaries = summaries;
        this.sessions = sessions;
    }

    @GetMapping("/api/internal/dashboard/summary")
    public ResponseEntity<DashboardCoreSnapshot> summary(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String dateBasis,
        @RequestParam(required = false) UUID companyId,
        @RequestParam(required = false) UUID factoryId,
        @RequestParam(required = false) UUID productionLineId,
        @RequestParam(required = false) UUID noteTypeId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String entryCutoffDate,
        HttpServletRequest request
    ) {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        DashboardSummaryCriteria criteria = new DashboardSummaryCriteria(
            date(from), date(to), dateBasis, companyId, factoryId,
            productionLineId, noteTypeId, status, date(entryCutoffDate));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(summaries.read(actor, criteria));
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException invalid) {
            throw new InvalidDashboardSummaryRequest("التاريخ يجب أن يكون بصيغة YYYY-MM-DD.");
        }
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    @ExceptionHandler(InvalidDashboardSummaryRequest.class)
    ResponseEntity<Map<String,String>> invalid(InvalidDashboardSummaryRequest exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> databaseFailure() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
