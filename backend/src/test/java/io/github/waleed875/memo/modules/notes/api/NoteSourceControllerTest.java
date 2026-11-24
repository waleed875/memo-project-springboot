package io.github.waleed875.memo.modules.notes.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.notes.application.NoteDetailsReader;
import io.github.waleed875.memo.modules.notes.application.NoteOwnership;
import io.github.waleed875.memo.modules.notes.application.NoteSourceStore;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceDraft;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NoteSourceControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean NoteDetailsReader details;
    @MockitoBean NoteSourceStore store;
    @MockitoBean MasterDataWriteGate gate;

    private void user(UUID id, String role) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(id, "test", "Test User", role)));
    }
    private static String path(UUID id) { return "/api/notes/" + id + "/source"; }

    @Test void anonymousCannotReadOrChangeSources() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        UUID note = UUID.randomUUID();
        mvc.perform(get(path(note))).andExpect(status().isUnauthorized());
        mvc.perform(post(path(note)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceType\":\"Loot\",\"lootDescription\":\"غنائم\"}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(store, gate);
    }

    @Test void csrfAndViewerBlockSourceMutation() throws Exception {
        UUID actor=UUID.randomUUID(), note=UUID.randomUUID();
        user(actor,"ADMIN");
        mvc.perform(post(path(note)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceType\":\"Loot\",\"lootDescription\":\"غنائم\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete(path(note))).andExpect(status().isForbidden());
        user(actor,"VIEWER");
        mvc.perform(post(path(note)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceType\":\"Loot\",\"lootDescription\":\"غنائم\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(store, gate);
    }

    @Test void ownerCanSaveLootAndRemoveWithLegacyResponses() throws Exception {
        UUID actor=UUID.randomUUID(), note=UUID.randomUUID();
        user(actor,"DATA_ENTRY");
        mvc.perform(post(path(note)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceType\":\"Loot\",\"lootDescription\":\"غنائم\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("تم حفظ مصدر مذكرة الإدخال بنجاح."));
        verify(store).save(eq(note),eq(actor),eq(true),any(NoteSourceDraft.class));
        mvc.perform(delete(path(note)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("تمت إزالة مصدر المذكرة بنجاح."));
        verify(store).remove(eq(note),eq(actor),eq(true));
    }

    @Test void malformedSourcePayloadFailsBeforeRepositoryWrite() throws Exception {
        user(UUID.randomUUID(),"ADMIN");
        UUID note = UUID.randomUUID();
        mvc.perform(post(path(note)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceType\":\"Unknown\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path(note)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceType\":\"Invoice\",\"invoices\":[{\"invoiceNumber\":\"A\",\"attachmentBase64\":\"%%%\"}]}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }

    @Test void readerChecksOwnershipBeforeSourceStore() throws Exception {
        UUID actor=UUID.randomUUID(), other=UUID.randomUUID(), note=UUID.randomUUID();
        user(actor,"DATA_ENTRY");
        when(details.ownership(note)).thenReturn(Optional.of(new NoteOwnership(other)));
        mvc.perform(get(path(note))).andExpect(status().isForbidden());
        verifyNoInteractions(store);
        user(other,"DATA_ENTRY");
        when(store.read(eq(note),eq(other),eq(true))).thenReturn(Optional.empty());
        mvc.perform(get(path(note))).andExpect(status().isOk());
    }

    @Test void invoiceAttachmentDownloadRemainsExplicitlyUnavailable() throws Exception {
        UUID note=UUID.randomUUID(), invoice=UUID.randomUUID();
        user(UUID.randomUUID(),"ADMIN");
        mvc.perform(get("/api/notes/{note}/source/invoices/{invoice}/attachment",note,invoice))
            .andExpect(status().isForbidden());
    }
}
