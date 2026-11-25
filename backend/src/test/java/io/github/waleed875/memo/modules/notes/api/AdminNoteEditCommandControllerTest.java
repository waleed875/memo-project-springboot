package io.github.waleed875.memo.modules.notes.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.notes.application.AdminApprovedMetadataEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminApprovedMetadataEditRepository;
import io.github.waleed875.memo.modules.notes.application.AdminNoteIndicatorEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteIndicatorEditRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminNoteEditCommandControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean AdminNoteIndicatorEditRepository indicatorRepository;
    @MockitoBean AdminApprovedMetadataEditRepository metadataRepository;
    @MockitoBean MasterDataWriteGate writeGate;

    private final UUID note = UUID.randomUUID();
    private final UUID company = UUID.randomUUID();
    private final UUID type = UUID.randomUUID();
    private final UUID field = UUID.randomUUID();
    private static final LocalDateTime VERSION = LocalDateTime.parse("2026-11-24T10:20:30");

    private String indicators() {
        return """
          {
            "expectedUpdatedAt":"2026-11-24T10:20:30",
            "markAllIndicatorsCorrect":false,
            "fieldValues":[{"fieldId":"%s","value":"Correct","inactiveReason":null}]
          }
          """.formatted(field);
    }

    private String metadata() {
        return """
          {
            "expectedUpdatedAt":"2026-11-24T10:20:30",
            "metadata":{
              "noteNumber":"1234",
              "companyId":"%s","noteTypeId":"%s",
              "factoryId":null,"productionLineId":null,
              "writingDate":"2026-11-22","arrivalAt":"2026-11-24"
            }
          }
          """.formatted(company, type);
    }

    private void actor(String role, UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(id, "test", "Test", role)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder indicatorsPut(String json) {
        return put("/api/internal/notes/{id}/admin/indicators", note)
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder metadataPut(String json) {
        return put("/api/internal/notes/{id}/admin/approved-metadata", note)
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test void bothRoutesRequireSessionAndCsrf() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(indicatorsPut(indicators())).andExpect(status().isUnauthorized());
        mvc.perform(metadataPut(metadata())).andExpect(status().isUnauthorized());
        actor("ADMIN", UUID.randomUUID());
        mvc.perform(put("/api/internal/notes/{id}/admin/indicators", note)
            .contentType(MediaType.APPLICATION_JSON).content(indicators()))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/internal/notes/{id}/admin/approved-metadata", note)
            .contentType(MediaType.APPLICATION_JSON).content(metadata()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(indicatorRepository, metadataRepository);
    }

    @Test void nonAdminRolesCannotMutateEitherPath() throws Exception {
        for (String role : new String[]{"DATA_ENTRY", "REVIEWER", "VIEWER", "UNKNOWN"}) {
            actor(role, UUID.randomUUID());
            mvc.perform(indicatorsPut(indicators())).andExpect(status().isForbidden());
            mvc.perform(metadataPut(metadata())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(indicatorRepository, metadataRepository);
    }

    @Test void adminIndicatorUpdateMapsOnlyNarrowCommandAndServerIdentity() throws Exception {
        UUID admin = UUID.randomUUID();
        actor("ADMIN", admin);
        when(indicatorRepository.updateIndicators(any(), eq(admin), any(LocalDate.class)))
            .thenReturn(VERSION.plusSeconds(1));
        mvc.perform(indicatorsPut(indicators())).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(note.toString()))
            .andExpect(jsonPath("$.message").value("تم تحديث مؤشرات المذكرة بنجاح."));
        var captor = ArgumentCaptor.forClass(AdminNoteIndicatorEditCommand.class);
        verify(indicatorRepository).updateIndicators(captor.capture(), eq(admin), any(LocalDate.class));
        assertEquals(note, captor.getValue().noteId());
        assertEquals(VERSION, captor.getValue().expectedUpdatedAt());
        assertFalse(captor.getValue().markAllIndicatorsCorrect());
        assertEquals(field, captor.getValue().fieldValues().getFirst().fieldId());
        assertEquals(IndicatorValue.Correct, captor.getValue().fieldValues().getFirst().value());
        verifyNoInteractions(metadataRepository);
    }

    @Test void adminApprovedMetadataCommandCarriesCompleteSnapshot() throws Exception {
        UUID admin = UUID.randomUUID();
        actor("ADMIN", admin);
        when(metadataRepository.edit(any(), eq(admin), any(LocalDate.class)))
            .thenReturn(VERSION.plusSeconds(1));
        mvc.perform(metadataPut(metadata())).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(note.toString()))
            .andExpect(jsonPath("$.message").value("تم تحديث بيانات المذكرة المعتمدة بنجاح."));
        var captor = ArgumentCaptor.forClass(AdminApprovedMetadataEditCommand.class);
        verify(metadataRepository).edit(captor.capture(), eq(admin), any(LocalDate.class));
        assertEquals(note, captor.getValue().noteId());
        assertEquals(VERSION, captor.getValue().expectedUpdatedAt());
        assertEquals(company, captor.getValue().metadata().companyId());
        assertEquals(type, captor.getValue().metadata().noteTypeId());
        assertEquals("1234", captor.getValue().metadata().noteNumber());
        assertEquals(LocalDate.of(2026, 11, 22), captor.getValue().metadata().writingDate());
        assertNull(captor.getValue().metadata().factoryId());
        verifyNoInteractions(indicatorRepository);
    }

    @Test void unknownFieldsDuplicateIdsAndWrongTypesFailBeforeAnyWrite() throws Exception {
        actor("ADMIN", UUID.randomUUID());
        mvc.perform(indicatorsPut(indicators().replace(
            "\"markAllIndicatorsCorrect\":false",
            "\"markAllIndicatorsCorrect\":false,\"status\":\"APPROVED\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(indicatorsPut(indicators().replace(
            "\"value\":\"Correct\"", "\"value\":\"Unknown\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(indicatorsPut(indicators().replace(
            "\"value\":\"Correct\"", "\"value\":\"Correct\",\"userId\":\"hijack\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(indicatorsPut(indicators().replace(
            "\"markAllIndicatorsCorrect\":false", "\"markAllIndicatorsCorrect\":\"false\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(indicatorsPut(indicators().replace(
            "\"expectedUpdatedAt\":\"2026-11-24T10:20:30\"",
            "\"expectedUpdatedAt\":\"2026-11-24T10:20:30Z\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(indicatorsPut(indicators().replace(
            "\"fieldValues\":[", "\"fieldValues\":[{\"fieldId\":\"" + field + "\",\"value\":\"Correct\"},")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(indicatorRepository, metadataRepository);
    }

    @Test void metadataRejectsOmissionsAndDestructiveOrUnknownFields() throws Exception {
        actor("ADMIN", UUID.randomUUID());
        mvc.perform(metadataPut(metadata().replace("\"factoryId\":null,", "")))
            .andExpect(status().isBadRequest());
        mvc.perform(metadataPut(metadata().replace(
            "\"noteNumber\":\"1234\"", "\"noteNumber\":\"1234\",\"approvedBy\":\"test\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(metadataPut(metadata().replace(
            "\"noteNumber\":\"1234\"", "\"noteNumber\":null")))
            .andExpect(status().isBadRequest());
        mvc.perform(metadataPut(metadata().replace(
            "\"companyId\":\"" + company + "\"",
            "\"companyId\":\"1-1-1-1-1\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(metadataPut(metadata().replace(
            "\"factoryId\":null",
            "\"factoryId\":null,\"sourceType\":\"Loot\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(metadataPut(metadata().replace(
            "\"productionLineId\":null",
            "\"productionLineId\":\"" + UUID.randomUUID() + "\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(metadataPut(metadata().replace(
            "\"arrivalAt\":\"2026-11-24\"", "\"arrivalAt\":\"bad-date\"")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(indicatorRepository, metadataRepository);
    }

    @Test void versionAndStateConflictsReturn409AndNeverMaskFailure() throws Exception {
        UUID admin = UUID.randomUUID();
        actor("ADMIN", admin);
        when(indicatorRepository.updateIndicators(any(), eq(admin), any(LocalDate.class)))
            .thenThrow(new NoteDraftEditConflict("stale-indicators"));
        when(metadataRepository.edit(any(), eq(admin), any(LocalDate.class)))
            .thenThrow(new NoteDraftEditConflict("not-approved"));
        mvc.perform(indicatorsPut(indicators())).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("stale-indicators"));
        mvc.perform(metadataPut(metadata())).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("not-approved"));
    }

    @Test void legacyPutRemainsDeniedBySecurityFilter() throws Exception {
        actor("ADMIN", UUID.randomUUID());
        mvc.perform(put("/api/notes/{id}", note).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(metadata()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(indicatorRepository, metadataRepository);
    }
}
