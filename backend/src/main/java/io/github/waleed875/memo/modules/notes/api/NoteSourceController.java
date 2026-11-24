package io.github.waleed875.memo.modules.notes.api;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteCreateConflict;
import io.github.waleed875.memo.modules.notes.application.NoteSourceService;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceDraft;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceType;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceView;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Source CRUD only, not invoice-attachment download or whole-note CRUD. */
@RestController
@RequestMapping("/api/notes/{id}/source")
public class NoteSourceController {
    private final NoteSourceService service;
    private final SessionUserResolver sessions;

    public NoteSourceController(NoteSourceService service, SessionUserResolver sessions) {
        this.service = service; this.sessions = sessions;
    }

    private UserProfile actor(HttpServletRequest request) {
        return sessions.resolve(request).orElse(null);
    }

    @GetMapping
    public ResponseEntity<?> get(@PathVariable UUID id, HttpServletRequest request) {
        var source = service.read(actor(request), id);
        if (source.isEmpty()) {
            return ResponseEntity.ok().build(); // legacy result: null
        }
        return ResponseEntity.ok(source.get());
    }

    public record ExitRefRequest(UUID exitNoteId, Integer referenceOrder, String noteNumber) {}
    public record InvoiceRequest(
        UUID id, String invoiceNumber, String invoiceDate, String destination,
        String attachmentFileName, String attachmentContentType, String attachmentBase64
    ) {}
    public record SourceRequest(
        String sourceType, String otherDescription, String lootDescription,
        List<ExitRefRequest> exitReferences, List<InvoiceRequest> invoices
    ) {}

    @PostMapping
    public Map<String, String> save(
        @PathVariable UUID id, @RequestBody SourceRequest body, HttpServletRequest request
    ) {
        if (body == null || body.sourceType() == null || body.sourceType().isBlank()) {
            throw new NoteSaveValidationException("نوع المصدر المحدد غير صالح.");
        }
        NoteSourceType type;
        try {
            type = NoteSourceType.valueOf(
                body.sourceType().substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + body.sourceType().substring(1).toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new NoteSaveValidationException("نوع المصدر المحدد غير صالح.");
        }
        List<NoteSourceDraft.ExitReference> refs = new ArrayList<>();
        if (body.exitReferences() != null) {
            for (var ref : body.exitReferences()) {
                if (ref == null) throw new NoteSaveValidationException("مرجع الإخراج غير صالح.");
                refs.add(new NoteSourceDraft.ExitReference(
                    ref.exitNoteId(), ref.referenceOrder() == null ? 0 : ref.referenceOrder()));
            }
        }
        List<NoteSourceDraft.Invoice> invoices = new ArrayList<>();
        if (body.invoices() != null) {
            for (var invoice : body.invoices()) {
                if (invoice == null) throw new NoteSaveValidationException("بيانات الفاتورة غير صالحة.");
                LocalDate date = null;
                if (invoice.invoiceDate() != null && !invoice.invoiceDate().isBlank()) {
                    try { date = LocalDate.parse(invoice.invoiceDate()); }
                    catch (java.time.format.DateTimeParseException ex) {
                        throw new NoteSaveValidationException("تاريخ الفاتورة غير صالح.");
                    }
                }
                NoteAttachment attachment = null;
                if (invoice.attachmentBase64() != null && !invoice.attachmentBase64().isBlank()) {
                    // Bound encoded size before allocating decoded bytes.
                    if (invoice.attachmentBase64().length() > 14_000_000) {
                        throw new NoteSaveValidationException("المرفق يتجاوز 10 ميغابايت.");
                    }
                    try {
                        attachment = new NoteAttachment(invoice.attachmentFileName(),
                            Base64.getDecoder().decode(invoice.attachmentBase64()));
                    } catch (IllegalArgumentException ex) {
                        throw new NoteSaveValidationException("ترميز المرفق غير صالح.");
                    }
                }
                invoices.add(new NoteSourceDraft.Invoice(
                    invoice.invoiceNumber(), date, invoice.destination(), attachment));
            }
        }
        service.save(actor(request), id, new NoteSourceDraft(
            type, body.otherDescription(), body.lootDescription(), refs, invoices));
        return Map.of("message", "تم حفظ مصدر مذكرة الإدخال بنجاح.");
    }

    @DeleteMapping
    public Map<String, String> remove(@PathVariable UUID id, HttpServletRequest request) {
        service.remove(actor(request), id);
        return Map.of("message", "تمت إزالة مصدر المذكرة بنجاح.");
    }

    @ExceptionHandler(UnauthenticatedMasterDataAccess.class)
    ResponseEntity<Void> unauthenticated() { return ResponseEntity.status(401).build(); }

    @ExceptionHandler({NoteAccessDenied.class, MasterDataForbiddenException.class})
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler({NoteCreateConflict.class, MasterDataReadOnlyException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException ex) {
        return ResponseEntity.status(409).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(NoteSaveValidationException.class)
    ResponseEntity<Map<String, String>> invalid(NoteSaveValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
