package io.github.waleed875.memo.modules.dashboard.api;

import io.github.waleed875.memo.modules.dashboard.application.DashboardFlowService;
import io.github.waleed875.memo.modules.dashboard.application.DashboardFlowSnapshot;
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

/** Internal admin dashboard flow, not the public DashboardSnapshot contract. */
@RestController
public final class InternalDashboardFlowController {
    private final DashboardFlowService service;
    private final SessionUserResolver sessions;
    public InternalDashboardFlowController(
        DashboardFlowService service,SessionUserResolver sessions
    ) {
        this.service=service;
        this.sessions=sessions;
    }

    @GetMapping("/api/internal/dashboard/flow")
    public ResponseEntity<DashboardFlowSnapshot> read(
        @RequestParam(required=false) String from,
        @RequestParam(required=false) String to,
        @RequestParam(required=false) String dateBasis,
        @RequestParam(required=false) UUID companyId,
        @RequestParam(required=false) UUID factoryId,
        @RequestParam(required=false) UUID productionLineId,
        @RequestParam(required=false) UUID noteTypeId,
        @RequestParam(required=false) String status,
        @RequestParam(required=false) String entryCutoffDate,
        HttpServletRequest request
    ) {
        var actor=sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        var filter=new DashboardSummaryCriteria(
            date(from),date(to),dateBasis,companyId,factoryId,
            productionLineId,noteTypeId,status,date(entryCutoffDate));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.read(actor,filter));
    }

    private static LocalDate date(String raw) {
        if(raw==null||raw.isBlank()) return null;
        try { return LocalDate.parse(raw); }
        catch(DateTimeParseException ex){
            throw new InvalidDashboardSummaryRequest("التاريخ يجب أن يكون YYYY-MM-DD.");
        }
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthorized() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler(InvalidDashboardSummaryRequest.class)
    ResponseEntity<Map<String,String>> invalid(InvalidDashboardSummaryRequest ex) {
        return ResponseEntity.badRequest().body(Map.of("message",ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
}
