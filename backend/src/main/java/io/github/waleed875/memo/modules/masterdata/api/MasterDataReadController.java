package io.github.waleed875.memo.modules.masterdata.api;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataQueryService;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET endpoints preserve the original /api/master JSON contract.
 * Mutating endpoints are deliberately not mapped or permitted.
 */
@RestController
@RequestMapping("/api/master")
public final class MasterDataReadController {
    private final MasterDataQueryService queries;
    private final SessionUserResolver sessions;

    public MasterDataReadController(MasterDataQueryService queries, SessionUserResolver sessions) {
        this.queries = queries;
        this.sessions = sessions;
    }

    private UserProfile current(HttpServletRequest request) {
        return sessions.resolve(request).orElse(null);
    }

    @GetMapping("/companies")
    public List<Company> companies(HttpServletRequest request) {
        return queries.companies(current(request));
    }

    @GetMapping("/companies/{id}")
    public ResponseEntity<Company> company(
        @PathVariable String id, HttpServletRequest request
    ) {
        // ASP.NET route constraint '{id:guid}' returns 404 for malformed ids.
        UUID parsed;
        try {
            parsed = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
        return queries.company(current(request), parsed)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/factories")
    public List<Factory> factories(
        @RequestParam(required = false) UUID companyId, HttpServletRequest request
    ) {
        return queries.factories(current(request), companyId);
    }

    @GetMapping("/production-lines")
    public List<ProductionLine> productionLines(
        @RequestParam(required = false) UUID factoryId, HttpServletRequest request
    ) {
        return queries.productionLines(current(request), factoryId);
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    public ResponseEntity<Void> unauthenticated() {
        return ResponseEntity.status(401).build();
    }
}
