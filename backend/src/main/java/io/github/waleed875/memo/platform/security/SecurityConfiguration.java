package io.github.waleed875.memo.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    /**
     * Migration-safe default: do not accidentally expose unimplemented legacy APIs.
     * Only health probes are public until session authentication and per-resource
     * authorization have been ported and tested.
     *
     * CSRF remains enabled for state-changing requests.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
            .requestMatchers(
                "/actuator/health", "/actuator/health/**",
                "/api/auth/csrf", "/api/auth/login", "/api/auth/me", "/api/auth/logout",
                "/api/auth/change-password",
                "/api/admin/csrf", "/api/admin/login", "/api/admin/session", "/api/admin/logout"
            ).permitAll()
            // Public at the filter boundary only: MasterDataQueryService
            // performs application-level session authorization before any SQL.
            .requestMatchers(HttpMethod.GET,
                "/api/master/companies",
                "/api/master/companies/{id}",
                "/api/master/factories",
                "/api/master/production-lines",
                "/api/master/note-types",
                "/api/master/note-types/{id}/fields"
            ).permitAll()
            // These collection endpoints are not implemented yet. A wildcard
            // /api/notes/{id} must never accidentally open them.
            .requestMatchers(HttpMethod.GET,
                "/api/notes/incomplete-indicators", "/api/notes/link-candidates"
            ).denyAll()
            // Dashboard lookups only: controller/service require an active ADMIN session,
            // and JDBC re-checks the current DB role. All other dashboard paths stay denied.
            .requestMatchers(HttpMethod.GET, "/api/dashboard/lookups").permitAll()
            // Internal scalar-only dashboard diagnostics; never open legacy snapshot.
            .requestMatchers(HttpMethod.GET, "/api/internal/dashboard/summary").permitAll()
            .requestMatchers(HttpMethod.GET,
                "/api/internal/dashboard/distributions").permitAll()
            .requestMatchers(HttpMethod.GET,
                "/api/internal/dashboard/quality").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/notes/review-queue").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/notes/{id}/source").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/notes/{id}/source").permitAll()
            .requestMatchers(HttpMethod.DELETE, "/api/notes/{id}/source").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/notes", "/api/notes/{id}",
                "/api/notes/duplicates/check").permitAll()
            // Explicit stable-ID create command; NOT legacy POST /api/notes.
            // CSRF still applies and the application service checks live role.
            .requestMatchers(HttpMethod.POST, "/api/internal/notes").permitAll()
            // Narrow edit commands. Legacy PUT /api/notes/{id} remains denied.
            .requestMatchers(HttpMethod.PUT,
                "/api/internal/notes/{id}/draft",
                "/api/internal/notes/{id}/admin/text",
                "/api/internal/notes/{id}/admin/indicators",
                "/api/internal/notes/{id}/admin/approved-metadata").permitAll()
            // Internal note media: downloads are authorized by active DB role and ownership.
            .requestMatchers(HttpMethod.GET,
                "/api/internal/notes/{id}/attachments/{attachmentId}").permitAll()
            .requestMatchers(HttpMethod.POST,
                "/api/internal/notes/{id}/admin/attachments").permitAll()
            .requestMatchers(HttpMethod.DELETE,
                "/api/internal/notes/{id}/admin/attachments/{attachmentId}").permitAll()
            .requestMatchers(HttpMethod.PUT,
                "/api/internal/notes/{id}/admin/links").permitAll()
            .requestMatchers(HttpMethod.DELETE, "/api/notes/{id}").permitAll()
            .requestMatchers(HttpMethod.POST,
                "/api/notes/{id}/start-review", "/api/notes/{id}/return",
                "/api/notes/{id}/release-review", "/api/notes/{id}/approve").permitAll()
            .requestMatchers(HttpMethod.POST,
                "/api/master/note-types/fields/preview",
                "/api/master/note-types/fields").permitAll()
            .requestMatchers(HttpMethod.DELETE,
                "/api/master/note-types/fields/{fieldId}").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/master/companies", "/api/master/factories",
                "/api/master/production-lines", "/api/master/production-lines/{id}/toggle"
            ).permitAll()
            .requestMatchers(HttpMethod.PUT, "/api/master/companies/{id}",
                "/api/master/factories/{id}", "/api/master/production-lines/{id}"
            ).permitAll()
            .requestMatchers(HttpMethod.DELETE, "/api/master/companies/{id}",
                "/api/master/factories/{id}", "/api/master/production-lines/{id}"
            ).permitAll()
            .anyRequest().denyAll()
        );
        http.httpBasic(basic -> basic.disable());
        http.formLogin(form -> form.disable());
        return http.build();
    }
}
