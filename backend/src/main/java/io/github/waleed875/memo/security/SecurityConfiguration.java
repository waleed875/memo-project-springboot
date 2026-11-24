package io.github.waleed875.memo.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
            .anyRequest().denyAll()
        );
        http.httpBasic(basic -> basic.disable());
        http.formLogin(form -> form.disable());
        return http.build();
    }
}
