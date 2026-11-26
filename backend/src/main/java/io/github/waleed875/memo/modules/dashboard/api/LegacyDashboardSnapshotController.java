package io.github.waleed875.memo.modules.dashboard.api;

import io.github.waleed875.memo.modules.dashboard.application.DashboardLegacySnapshot;
import io.github.waleed875.memo.modules.dashboard.application.DashboardLegacySnapshotAdapter;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
import io.github.waleed875.memo.modules.dashboard.application.DashboardUnifiedService;
import io.github.waleed875.memo.modules.dashboard.application.InvalidDashboardSummaryRequest;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legacy React dashboard contract, explicitly enabled only for the isolated
 * Docker preview. Disabled by default outside preview; a browser can never
 * turn it on. Session resolution and the unified service both enforce live
 * database-backed ADMIN authorization. No placeholder figures are returned.
 */
@RestController
public final class LegacyDashboardSnapshotController {
    private final SessionUserResolver sessions;
    private final DashboardUnifiedService unified;
    private final boolean previewEnabled;

    public LegacyDashboardSnapshotController(
        SessionUserResolver sessions, DashboardUnifiedService unified,
        @Value("${memo.dashboard.snapshot.enabled:false}") boolean previewEnabled
    ) {
        this.sessions = sessions;
        this.unified = unified;
        this.previewEnabled = previewEnabled;
    }

    @GetMapping("/api/dashboard/snapshot")
    public ResponseEntity<DashboardLegacySnapshot> snapshot(
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
        if (!previewEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore()).build();
        }

        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        if (!"ADMIN".equals(actor.role())) {
            throw new MasterDataForbiddenException();
        }
        var filters = new DashboardSummaryCriteria(
            date(from), date(to), dateBasis, companyId, factoryId,
            productionLineId, noteTypeId, status, date(entryCutoffDate));
        var result = unified.read(actor, filters, 10);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(DashboardLegacySnapshotAdapter.toSnapshot(result));
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException invalid) {
            throw new InvalidDashboardSummaryRequest("التاريخ يجب أن يكون YYYY-MM-DD.");
        }
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(InvalidDashboardSummaryRequest.class)
    ResponseEntity<Map<String, String>> invalid(InvalidDashboardSummaryRequest error) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
            .body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore()).build();
    }
}
