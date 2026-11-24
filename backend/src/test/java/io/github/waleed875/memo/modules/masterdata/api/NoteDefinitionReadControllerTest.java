package io.github.waleed875.memo.modules.masterdata.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.NoteDefinitionReader;
import io.github.waleed875.memo.modules.masterdata.domain.NoteType;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeField;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldImpactPreview;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
class NoteDefinitionReadControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean NoteDefinitionReader reader;

    private void user(String role) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(UUID.randomUUID(), "test", "Test", role)));
    }

    @Test void missingSessionReturnsUnauthorizedWithoutReading() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/master/note-types")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/master/note-types/{id}/fields", UUID.randomUUID()))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/master/note-types/fields/preview").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"noteTypeId\":\""+UUID.randomUUID()+"\"}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(reader);
    }

    @Test void viewerReadsLegacyJsonIncludingInactiveDefinitions() throws Exception {
        user("VIEWER");
        UUID typeId = UUID.randomUUID(), definitionId = UUID.randomUUID(), fieldId = UUID.randomUUID();
        when(reader.noteTypes()).thenReturn(List.of(new NoteType(typeId, "TEST", "نوع", null, true)));
        when(reader.fieldsForType(typeId)).thenReturn(List.of(
            new NoteTypeField(definitionId, typeId, fieldId, "F1", "حقل", 3, true, false)));
        mvc.perform(get("/api/master/note-types"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("TEST"))
            .andExpect(jsonPath("$[0].nameAr").value("نوع"))
            .andExpect(jsonPath("$[0].isActive").value(true));
        mvc.perform(get("/api/master/note-types/{id}/fields", typeId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].fieldCode").value("F1"))
            .andExpect(jsonPath("$[0].sortOrder").value(3))
            .andExpect(jsonPath("$[0].isRequired").value(true))
            .andExpect(jsonPath("$[0].isActive").value(false));
    }

    @Test void viewerCannotAccessPreviewOrMutationRoutes() throws Exception {
        user("VIEWER");
        mvc.perform(post("/api/master/note-types/fields/preview").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"noteTypeId\":\""+UUID.randomUUID()+"\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/master/note-types/fields").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(reader);
    }

    @Test void adminPreviewRequiresCsrfAndReturnsExpectedJson() throws Exception {
        user("ADMIN");
        UUID typeId = UUID.randomUUID();
        when(reader.preview(typeId, null)).thenReturn(
            new NoteTypeFieldImpactPreview(typeId, null, 2, 1, 2, true));
        String json = "{\"noteTypeId\":\""+typeId+"\"}";
        mvc.perform(post("/api/master/note-types/fields/preview")
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/master/note-types/fields/preview").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.affectedNoteCount").value(2))
            .andExpect(jsonPath("$.existingValueCount").value(1))
            .andExpect(jsonPath("$.missingOrUnreviewedCount").value(2))
            .andExpect(jsonPath("$.isNewField").value(true));
    }
}
