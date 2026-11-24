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
                "/api/master/production-lines"
            ).permitAll()
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
