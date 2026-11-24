package io.github.waleed875.memo.modules.masterdata.api;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataCommandService;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapters only: all commands are authorized and gated in application. */
@RestController
@RequestMapping("/api/master")
public final class MasterDataWriteController {
    private final MasterDataCommandService commands;
    private final SessionUserResolver sessions;

    public MasterDataWriteController(MasterDataCommandService commands, SessionUserResolver sessions) {
        this.commands = commands;
        this.sessions = sessions;
    }

    private UserProfile actor(HttpServletRequest request) {
        return sessions.resolve(request).orElse(null);
    }

    @PostMapping("/companies")
    public Company createCompany(@RequestBody CompanyBody body, HttpServletRequest req) {
        return commands.saveCompany(actor(req), null, body.name(), body.isActive());
    }

    @PutMapping("/companies/{id}")
    public Company updateCompany(@PathVariable UUID id, @RequestBody CompanyBody body, HttpServletRequest req) {
        return commands.saveCompany(actor(req), id, body.name(), body.isActive());
    }

    @DeleteMapping("/companies/{id}")
    public Map<String, String> deleteCompany(@PathVariable UUID id, HttpServletRequest req) {
        commands.deleteCompany(actor(req), id);
        return Map.of("message", "تم حذف الشركة بنجاح.");
    }

    @PostMapping("/factories")
    public Factory createFactory(@RequestBody FactoryBody body, HttpServletRequest req) {
        return commands.saveFactory(actor(req), null, body.name(), body.companyId(), body.isActive());
    }

    @PutMapping("/factories/{id}")
    public Factory updateFactory(@PathVariable UUID id, @RequestBody FactoryBody body, HttpServletRequest req) {
        return commands.saveFactory(actor(req), id, body.name(), body.companyId(), body.isActive());
    }

    @DeleteMapping("/factories/{id}")
    public Map<String, String> deleteFactory(@PathVariable UUID id, HttpServletRequest req) {
        commands.deleteFactory(actor(req), id);
        return Map.of("message", "تم حذف المعمل بنجاح.");
    }

    @PostMapping("/production-lines")
    public ProductionLine createLine(@RequestBody ProductionLineBody body, HttpServletRequest req) {
        return commands.saveProductionLine(actor(req), null, body.name(), body.factoryId(), body.isActive());
    }

    @PutMapping("/production-lines/{id}")
    public ProductionLine updateLine(@PathVariable UUID id,
                                     @RequestBody ProductionLineBody body, HttpServletRequest req) {
        return commands.saveProductionLine(actor(req), id, body.name(), body.factoryId(), body.isActive());
    }

    @DeleteMapping("/production-lines/{id}")
    public Map<String, String> deleteLine(@PathVariable UUID id, HttpServletRequest req) {
        commands.deleteProductionLine(actor(req), id);
        return Map.of("message", "تم حذف خط الإنتاج بنجاح.");
    }

    @PostMapping("/production-lines/{id}/toggle")
    public Map<String, String> toggleLine(@PathVariable UUID id,
                                          @RequestBody ToggleBody body, HttpServletRequest req) {
        commands.toggleProductionLine(actor(req), id, Boolean.TRUE.equals(body.isActive()));
        return Map.of("message", "تم تحديث حالة خط الإنتاج.");
    }

    public record CompanyBody(String name, Boolean isActive) {}
    public record FactoryBody(String name, UUID companyId, Boolean isActive) {}
    public record ProductionLineBody(String name, UUID factoryId, Boolean isActive) {}
    public record ToggleBody(Boolean isActive) {}

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    public ResponseEntity<Void> unauthenticated() {
        return ResponseEntity.status(401).build();
    }

    @ExceptionHandler(MasterDataForbiddenException.class)
    public ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(403).build();
    }

    @ExceptionHandler(MasterDataReadOnlyException.class)
    public ResponseEntity<Map<String, String>> readOnly(MasterDataReadOnlyException e) {
        return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MasterDataOperationException.class)
    public ResponseEntity<Map<String, String>> invalid(MasterDataOperationException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
