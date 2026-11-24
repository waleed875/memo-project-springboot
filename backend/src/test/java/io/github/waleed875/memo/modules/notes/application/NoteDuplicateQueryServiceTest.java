package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteDuplicate;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteDuplicateQueryServiceTest {
    static class Recorder implements NoteDuplicateReader {
        int calls; UUID company; UUID type; String normalized;
        Long numeric; Integer year; boolean invoice; LocalDate writing;
        @Override public List<NoteDuplicate> find(UUID excluded, UUID company, UUID type,
            String normalized, Long numeric, Integer year, LocalDate writing, boolean invoice) {
            calls++; this.company=company; this.type=type; this.normalized=normalized;
            this.numeric=numeric; this.year=year; this.invoice=invoice; this.writing=writing;
            return List.of();
        }
    }
    final UUID company=UUID.randomUUID(), type=UUID.randomUUID();
    UserProfile user(String role) {
        return new UserProfile(UUID.randomUUID(), "x", "User", role);
    }

    @Test void anonymousAndReviewerNeverHitDatabase() {
        Recorder r = new Recorder();
        var service = new NoteDuplicateQueryService(r);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.find(null,null,"123",type,company,null,false));
        assertThrows(MasterDataForbiddenException.class,
            () -> service.find(user("VIEWER"),null,"123",type,company,null,false));
        assertThrows(MasterDataForbiddenException.class,
            () -> service.find(user("REVIEWER"),null,"123",type,company,null,false));
        assertEquals(0, r.calls);
    }

    @Test void missingOrEmptyKeyReturnsEmptyWithoutDatabase() {
        Recorder r = new Recorder();
        var service = new NoteDuplicateQueryService(r);
        assertTrue(service.find(user("ADMIN"),null," - ",type,company,null,false).isEmpty());
        assertTrue(service.find(user("ADMIN"),null,"123",null,company,null,false).isEmpty());
        assertTrue(service.find(user("DATA_ENTRY"),null,"123",type,null,null,false).isEmpty());
        assertEquals(0,r.calls);
    }

    @Test void arabicDigitsYearAndInvoiceAreNormalizedBeforeQuery() {
        Recorder r = new Recorder();
        LocalDate date=LocalDate.of(2026,11,20);
        new NoteDuplicateQueryService(r).find(
            user("DATA_ENTRY"),null," ۱۲٣ ",type,company,date,true);
        assertEquals(1,r.calls);
        assertEquals(123L,r.numeric);
        assertEquals(2026,r.year);
        assertEquals("۱۲٣",r.normalized);
        assertEquals(date,r.writing);
        assertTrue(r.invoice);
    }

    @Test void historicAlphanumericFallsBackToTextKey() {
        Recorder r = new Recorder();
        new NoteDuplicateQueryService(r).find(
            user("ADMIN"),null,"AB/١٢٣",type,company,null,false);
        assertNull(r.numeric);
        assertNull(r.year);
        assertEquals("AB١٢٣",r.normalized);
    }
}
