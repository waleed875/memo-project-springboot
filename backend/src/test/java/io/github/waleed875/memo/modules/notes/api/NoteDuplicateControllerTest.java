package io.github.waleed875.memo.modules.notes.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateReader;
import io.github.waleed875.memo.modules.notes.domain.NoteDuplicate;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NoteDuplicateControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean NoteDuplicateReader reader;

    private String path(UUID type, UUID company) {
        return "/api/notes/duplicates/check?noteNumber=123&noteTypeId="+type+
            "&companyId="+company+"&writingDate=2026-11-15";
    }
    private void role(String role) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(UUID.randomUUID(),"test","Tester",role)));
    }

    @Test void sessionIsRequiredAndViewerCannotCallDuplicateCheck() throws Exception {
        UUID type=UUID.randomUUID(), company=UUID.randomUUID();
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get(path(type,company))).andExpect(status().isUnauthorized());
        role("VIEWER");
        mvc.perform(get(path(type,company))).andExpect(status().isForbidden());
        verifyNoInteractions(reader);
    }

    @Test void dataEntryGetsLegacyJsonModelWithoutAttachmentBytes() throws Exception {
        UUID type=UUID.randomUUID(), company=UUID.randomUUID(), existing=UUID.randomUUID();
        role("DATA_ENTRY");
        when(reader.find(any(), eq(company), eq(type), eq("123"), eq(123L),
                eq(2026), eq(LocalDate.of(2026,11,15)), eq(false)))
            .thenReturn(List.of(new NoteDuplicate(existing,"123",company,"شركة",
                null,null,null,null,type,"نوع",LocalDate.of(2026,11,15),
                LocalDate.of(2026,11,17),LocalDateTime.of(2026,11,17,12,0),
                "file.pdf","application/pdf")));
        mvc.perform(get(path(type,company))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].noteId").value(existing.toString()))
            .andExpect(jsonPath("$[0].noteNumber").value("123"))
            .andExpect(jsonPath("$[0].companyName").value("شركة"))
            .andExpect(jsonPath("$[0].attachmentFileName").value("file.pdf"));
    }

    @Test void missingRequiredIdsReturnBadRequestBeforeRepository() throws Exception {
        role("ADMIN");
        mvc.perform(get("/api/notes/duplicates/check").param("noteNumber","123"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }
}
