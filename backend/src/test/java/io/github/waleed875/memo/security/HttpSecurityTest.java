package io.github.waleed875.memo.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Proves health visibility and deny-by-default for unfinished API routes. */
@SpringBootTest
@AutoConfigureMockMvc
class HttpSecurityTest {
    @Autowired
    MockMvc mvc;

    @Test
    void livenessProbeIsAccessible() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk());
    }

    @Test
    void unfinishedBusinessApisAreNotOpen() throws Exception {
        mvc.perform(get("/api/notes"))
            .andExpect(status().is4xxClientError());
    }
}
