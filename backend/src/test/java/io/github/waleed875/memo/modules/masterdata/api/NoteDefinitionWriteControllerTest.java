package io.github.waleed875.memo.modules.masterdata.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.NoteDefinitionWriter;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldDefinition;
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
class NoteDefinitionWriteControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean NoteDefinitionWriter writer;
    @MockitoBean MasterDataWriteGate gate;
    @MockitoBean SessionUserResolver sessions;

    private void session(String role) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(UUID.randomUUID(), "test", "Test", role)));
    }

    @Test void noSessionBlocksSaveAndDeactivate() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        UUID type = UUID.randomUUID();
        mvc.perform(post("/api/master/note-types/fields").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"noteTypeId\":\""+type+"\",\"fieldCode\":\"F1\",\"fieldNameAr\":\"حقل\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/master/note-types/fields/{id}", UUID.randomUUID()).with(csrf()))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(writer, gate);
    }

    @Test void nonAdminAndMissingCsrfAreForbiddenBeforeRepository() throws Exception {
        UUID type = UUID.randomUUID();
        String payload = "{\"noteTypeId\":\""+type+"\",\"fieldCode\":\"F1\",\"fieldNameAr\":\"حقل\"}";
        session("VIEWER");
        mvc.perform(post("/api/master/note-types/fields").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isForbidden());
        session("ADMIN");
        mvc.perform(post("/api/master/note-types/fields")
                .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/master/note-types/fields/{id}", UUID.randomUUID()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(writer, gate);
    }

    @Test void readOnlyGateBlocksSave() throws Exception {
        session("ADMIN");
        doThrow(new MasterDataReadOnlyException()).when(gate).requireWritable();
        mvc.perform(post("/api/master/note-types/fields").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"noteTypeId\":\""+UUID.randomUUID()+
                    "\",\"fieldCode\":\"F1\",\"fieldNameAr\":\"حقل\"}"))
            .andExpect(status().isConflict());
        verifyNoInteractions(writer);
    }

    @Test void adminSaveAndDeactivateReturnOriginalJson() throws Exception {
        session("ADMIN");
        UUID definition = UUID.randomUUID(), type = UUID.randomUUID(), field = UUID.randomUUID();
        when(writer.save(any(NoteTypeFieldDefinition.class), any(UUID.class)))
            .thenReturn(new NoteTypeFieldDefinition(
                definition, type, field, "F1", "حقل", null, 2, true, false));
        mvc.perform(post("/api/master/note-types/fields").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"noteTypeId\":\""+type+"\",\"fieldCode\":\"F1\",\"fieldNameAr\":\"حقل\",\"sortOrder\":2,\"isActive\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(definition.toString()))
            .andExpect(jsonPath("$.fieldId").value(field.toString()))
            .andExpect(jsonPath("$.isRequired").value(true))
            .andExpect(jsonPath("$.isActive").value(false));
        mvc.perform(delete("/api/master/note-types/fields/{id}", definition).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("تم تعطيل حقل المؤشر بنجاح."));
        verify(writer).deactivate(eq(definition), any(UUID.class));
    }
}
