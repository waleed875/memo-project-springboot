package io.github.waleed875.memo.modules.dashboard.api;

import io.github.waleed875.memo.modules.dashboard.application.DashboardQualityService;
import io.github.waleed875.memo.modules.dashboard.application.DashboardQualitySnapshot;
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

/** Internal admin-only quality projection, NOT the legacy dashboard snapshot. */
@RestController
public final class InternalDashboardQualityController {
    private final DashboardQualityService service;
    private final SessionUserResolver sessions;

    public InternalDashboardQualityController(
        DashboardQualityService service, SessionUserResolver sessions
    ) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/api/internal/dashboard/quality")
    public ResponseEntity<DashboardQualitySnapshot> read(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String dateBasis,
        @RequestParam(required = false) UUID companyId,
        @RequestParam(required = false) UUID factoryId,
        @RequestParam(required = false) UUID productionLineId,
        @RequestParam(required = false) UUID noteTypeId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String entryCutoffDate,
        @RequestParam(defaultValue = "10") int minSample,
        HttpServletRequest request
    ) {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        var criteria = new DashboardSummaryCriteria(
            date(from), date(to), dateBasis, companyId, factoryId,
            productionLineId, noteTypeId, status, date(entryCutoffDate));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.read(actor,criteria,minSample));
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) {
            throw new InvalidDashboardSummaryRequest("التاريخ يجب أن يكون YYYY-MM-DD.");
        }
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler(InvalidDashboardSummaryRequest.class)
    ResponseEntity<Map<String,String>> badRequest(InvalidDashboardSummaryRequest ex) {
        return ResponseEntity.badRequest().body(Map.of("message",ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
}
