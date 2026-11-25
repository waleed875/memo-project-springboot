package io.github.waleed875.memo.modules.dashboard.api;

import io.github.waleed875.memo.modules.dashboard.application.DashboardDistributions;
import io.github.waleed875.memo.modules.dashboard.application.DashboardDistributionsService;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal diagnostic chart aggregates only. Never replace the public legacy
 * /api/dashboard/snapshot until its whole SQL/DTO is verified.
 */
@RestController
public final class InternalDashboardDistributionsController {
    private final DashboardDistributionsService distributions;
    private final SessionUserResolver sessions;

    public InternalDashboardDistributionsController(
        DashboardDistributionsService distributions, SessionUserResolver sessions
    ) {
        this.distributions = distributions;
        this.sessions = sessions;
    }

    @GetMapping("/api/internal/dashboard/distributions")
    public ResponseEntity<DashboardDistributions> read(
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
        var filter = new DashboardSummaryCriteria(
            date(from), date(to), dateBasis, companyId, factoryId,
            productionLineId, noteTypeId, status, date(entryCutoffDate));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(distributions.read(actor,filter));
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw); }
        catch (DateTimeParseException exception) {
            throw new InvalidDashboardSummaryRequest("التاريخ يجب أن يكون بصيغة YYYY-MM-DD.");
        }
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler(InvalidDashboardSummaryRequest.class)
    ResponseEntity<Map<String,String>> invalid(InvalidDashboardSummaryRequest exception) {
        return ResponseEntity.badRequest().body(Map.of("message",exception.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> databaseFailure() { return ResponseEntity.status(503).build(); }
}
