package io.github.waleed875.memo.modules.dashboard.api;

import io.github.waleed875.memo.modules.dashboard.application.DashboardLegacySnapshotAdapter;
import io.github.waleed875.memo.modules.dashboard.application.DashboardLegacySnapshotPreview;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
import io.github.waleed875.memo.modules.dashboard.application.DashboardUnifiedService;
import io.github.waleed875.memo.modules.dashboard.application.DashboardUnifiedSnapshot;
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
 * Internal stage-51 composition. Deliberately distinct from the incomplete
 * legacy GET /api/dashboard/snapshot and carries explicit parity gaps.
 */
@RestController
public final class InternalDashboardUnifiedController {
    private final DashboardUnifiedService unified;
    private final SessionUserResolver sessions;

    public InternalDashboardUnifiedController(
        DashboardUnifiedService unified,SessionUserResolver sessions
    ) {
        this.unified=unified;
        this.sessions=sessions;
    }

    @GetMapping("/api/internal/dashboard/unified")
    public ResponseEntity<DashboardUnifiedSnapshot> read(
        @RequestParam(required=false) String from,
        @RequestParam(required=false) String to,
        @RequestParam(required=false) String dateBasis,
        @RequestParam(required=false) UUID companyId,
        @RequestParam(required=false) UUID factoryId,
        @RequestParam(required=false) UUID productionLineId,
        @RequestParam(required=false) UUID noteTypeId,
        @RequestParam(required=false) String status,
        @RequestParam(required=false) String entryCutoffDate,
        @RequestParam(defaultValue="10") int minSample,
        HttpServletRequest request
    ) {
        var actor=sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        var criteria=new DashboardSummaryCriteria(
            date(from),date(to),dateBasis,companyId,factoryId,
            productionLineId,noteTypeId,status,date(entryCutoffDate));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(unified.read(actor,criteria,minSample));
    }

    /**
     * Schema-preview envelope. Not interchangeable with the public legacy API.
     * All data comes from the same unified repeatable-read operation.
     */
    @GetMapping("/api/internal/dashboard/snapshot-preview")
    public ResponseEntity<DashboardLegacySnapshotPreview> preview(
        @RequestParam(required=false) String from,
        @RequestParam(required=false) String to,
        @RequestParam(required=false) String dateBasis,
        @RequestParam(required=false) UUID companyId,
        @RequestParam(required=false) UUID factoryId,
        @RequestParam(required=false) UUID productionLineId,
        @RequestParam(required=false) UUID noteTypeId,
        @RequestParam(required=false) String status,
        @RequestParam(required=false) String entryCutoffDate,
        @RequestParam(defaultValue="10") int minSample,
        HttpServletRequest request
    ) {
        DashboardUnifiedSnapshot unified=read(
            from,to,dateBasis,companyId,factoryId,productionLineId,
            noteTypeId,status,entryCutoffDate,minSample,request).getBody();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(DashboardLegacySnapshotAdapter.toPreview(unified));
    }

    private static LocalDate date(String raw) {
        if(raw==null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw); }
        catch(DateTimeParseException invalid) {
            throw new InvalidDashboardSummaryRequest("التاريخ يجب أن يكون YYYY-MM-DD.");
        }
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler(InvalidDashboardSummaryRequest.class)
    ResponseEntity<Map<String,String>> invalid(InvalidDashboardSummaryRequest e) {
        return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
}
