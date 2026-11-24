package io.github.waleed875.memo.modules.masterdata.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReader;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.HttpServletRequest;

@SpringBootTest
@AutoConfigureMockMvc
class MasterDataReadControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean MasterDataReader reader;
    @MockitoBean SessionUserResolver sessions;

    private void authenticatedViewer() {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(UUID.randomUUID(), "viewer", "Viewer", "VIEWER"))
        );
    }

    @Test void anonymousCannotReadMasterData() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/master/companies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/master/factories")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/master/production-lines")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reader);
    }

    @Test void authenticatedViewerReceivesLegacyJsonContracts() throws Exception {
        authenticatedViewer();
        UUID companyId = UUID.randomUUID();
        UUID factoryId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        when(reader.companies()).thenReturn(List.of(new Company(companyId, "شركة", false)));
        when(reader.company(companyId)).thenReturn(Optional.of(new Company(companyId, "شركة", false)));
        when(reader.factories(companyId)).thenReturn(List.of(
            new Factory(factoryId, "معمل", companyId, "شركة", true)));
        when(reader.productionLines(factoryId)).thenReturn(List.of(
            new ProductionLine(lineId, factoryId, "معمل", companyId, "شركة", "خط", true)
        ));

        mvc.perform(get("/api/master/companies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(companyId.toString()))
            .andExpect(jsonPath("$[0].name").value("شركة"))
            .andExpect(jsonPath("$[0].isActive").value(false));
        mvc.perform(get("/api/master/companies/{id}", companyId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(companyId.toString()));
        mvc.perform(get("/api/master/factories").param("companyId", companyId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].companyName").value("شركة"));
        mvc.perform(get("/api/master/production-lines").param("factoryId", factoryId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].factoryName").value("معمل"));
    }

    @Test void missingCompanyReturns404AndWritesRemainDeniedForViewer() throws Exception {
        authenticatedViewer();
        mvc.perform(get("/api/master/companies/{id}", UUID.randomUUID()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/master/companies").with(csrf())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"name\":\"شركة اختبار\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/master/companies/{id}", UUID.randomUUID()).with(csrf()))
            .andExpect(status().isForbidden());
        verify(reader, never()).companies();
    }
}
