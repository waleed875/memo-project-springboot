package io.github.waleed875.memo.modules.masterdata.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteRepository;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
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
class MasterDataWriteControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean MasterDataWriteGate gate;
    @MockitoBean MasterDataWriteRepository writer;

    private void user(String role) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(UUID.randomUUID(), "test", "Test User", role)));
    }

    @Test void withoutSessionReturns401AndDoesNotWrite() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(post("/api/master/companies").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"شركة\"}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(writer, gate);
    }

    @Test void nonAdminReturns403AndDoesNotWrite() throws Exception {
        user("VIEWER");
        mvc.perform(post("/api/master/companies").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"شركة\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(writer, gate);
    }

    @Test void missingCsrfReturns403BeforeApplication() throws Exception {
        user("ADMIN");
        mvc.perform(post("/api/master/companies")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"شركة\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(writer, gate);
    }

    @Test void localReadOnlyGateReturns409AndDoesNotWrite() throws Exception {
        user("ADMIN");
        doThrow(new MasterDataReadOnlyException()).when(gate).requireWritable();
        mvc.perform(post("/api/master/companies").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"شركة\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(writer);
    }

    @Test void authorizedAdminCanCreateAndReceivesLegacyJsonFields() throws Exception {
        user("ADMIN");
        UUID id = UUID.randomUUID();
        when(writer.saveCompany(any(Company.class), any(UUID.class), eq(true)))
            .thenReturn(new Company(id, "شركة", false));
        mvc.perform(post("/api/master/companies").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"شركة\",\"isActive\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.name").value("شركة"))
            .andExpect(jsonPath("$.isActive").value(false));
        verify(gate).requireWritable();
        verify(writer).saveCompany(any(Company.class), any(UUID.class), eq(true));
    }

    @Test void unauthenticatedDeletionIsDenied() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(delete("/api/master/companies/{id}", UUID.randomUUID()).with(csrf()))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(writer, gate);
    }
}
