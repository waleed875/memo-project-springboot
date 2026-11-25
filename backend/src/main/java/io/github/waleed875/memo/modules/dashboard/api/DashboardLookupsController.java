package io.github.waleed875.memo.modules.dashboard.api;

import io.github.waleed875.memo.modules.dashboard.application.DashboardLookups;
import io.github.waleed875.memo.modules.dashboard.application.DashboardLookupsService;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * First fully ported dashboard endpoint. Other dashboard paths remain denyAll.
 * The caller's identity is resolved from the existing DB-revalidated session.
 */
@RestController
public final class DashboardLookupsController {
    private final DashboardLookupsService service;
    private final SessionUserResolver sessions;

    public DashboardLookupsController(
        DashboardLookupsService service, SessionUserResolver sessions
    ) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/api/dashboard/lookups")
    public ResponseEntity<DashboardLookups> lookups(HttpServletRequest request) {
        var actor = sessions.resolve(request).orElseThrow(UnauthenticatedMasterDataAccess::new);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.read(actor));
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @ExceptionHandler(MasterDataForbiddenException.class)
    ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> databaseUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
