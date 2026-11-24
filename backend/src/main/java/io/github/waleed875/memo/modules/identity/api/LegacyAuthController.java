package io.github.waleed875.memo.modules.identity.api;

import io.github.waleed875.memo.modules.identity.application.IdentityApplicationService;
import io.github.waleed875.memo.modules.identity.application.LoginAttempt;
import io.github.waleed875.memo.modules.identity.application.PasswordChangeOutcome;
import io.github.waleed875.memo.platform.security.FixedWindowAttemptLimiter;
import io.github.waleed875.memo.modules.identity.application.SessionProof;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestAttribute;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compatibility slice for the original /api/auth and /api/admin login/session
 * endpoints. All other administrative routes stay closed by default.
 *
 * Password changing, login auditing, throttling and full Spring Security
 * authentication integration are pending; do not deploy as production auth.
 */
@RestController
@RequestMapping
public final class LegacyAuthController {
    private static final String SESSION_KEY = "memo.authenticated.session";

    private final IdentityApplicationService identity;
    private final FixedWindowAttemptLimiter limiter;

    public LegacyAuthController(IdentityApplicationService identity, FixedWindowAttemptLimiter limiter) {
        this.identity = identity;
        this.limiter = limiter;
    }

    @GetMapping({"/api/auth/csrf", "/api/admin/csrf"})
    public Map<String, String> csrf(@RequestAttribute(name = "_csrf") CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Optional<UserProfile> current = currentUser(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", current.isPresent());
        body.put("user", current.map(this::profileMap).orElse(null));
        return body;
    }

    @GetMapping("/api/admin/session")
    public Map<String, Object> adminSession(HttpServletRequest request) {
        Optional<UserProfile> current = currentUser(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", current.isPresent());
        body.put("mustChangePassword", false);
        current.ifPresent(user -> body.putAll(profileMap(user)));
        return body;
    }

    @PostMapping({"/api/auth/login", "/api/admin/login"})
    public ResponseEntity<Map<String, Object>> login(
        @RequestBody LoginRequest credentials, HttpServletRequest request
    ) {
        FixedWindowAttemptLimiter.Decision decision = limiter.acquire(request.getRemoteAddr());
        if (!decision.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()))
                .body(Map.of("message", "محاولات كثيرة. حاول مجددًا لاحقًا."));
        }

        boolean admin = request.getServletPath().startsWith("/api/admin/");
        LoginAttempt result = identity.login(credentials.username(), credentials.password());

        if (result.status() == LoginAttempt.Status.INVALID) {
            return admin
                ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "بيانات الدخول غير صحيحة."));
        }

        if (result.status() == LoginAttempt.Status.MUST_CHANGE_PASSWORD) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("authenticated", false);
            body.put("mustChangePassword", true);
            body.put("username", result.user().username());
            body.put("fullName", result.user().fullName());
            body.put("role", result.user().role());
            body.put("message", "يجب تغيير كلمة المرور قبل المتابعة.");
            return ResponseEntity.ok(body);
        }

        HttpSession session = request.getSession(true);
        request.changeSessionId(); // Prevent session fixation after authentication
        session.setAttribute(SESSION_KEY, result.session());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        body.put("mustChangePassword", false);
        if (admin) {
            body.putAll(profileMap(result.user()));
        } else {
            body.put("user", profileMap(result.user()));
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping({"/api/auth/logout", "/api/admin/logout"})
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object stored = session.getAttribute(SESSION_KEY);
            if (stored instanceof SessionProof proof) identity.logout(proof);
            session.invalidate();
        }

        if (request.getServletPath().startsWith("/api/admin/")) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of("message", "تم تسجيل الخروج بنجاح."));
    }


    @PostMapping("/api/auth/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
        @RequestBody ChangePasswordRequest body, HttpServletRequest request
    ) {
        FixedWindowAttemptLimiter.Decision decision = limiter.acquire(request.getRemoteAddr());
        if (!decision.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()))
                .body(Map.of("message", "محاولات كثيرة. حاول مجددًا لاحقًا."));
        }
        String username = body.username();
        if (username == null || username.isBlank()) {
            username = currentUser(request).map(UserProfile::username).orElse(null);
        }

        PasswordChangeOutcome result =
            identity.changePassword(username, body.currentPassword(), body.newPassword());

        if (result == PasswordChangeOutcome.SUCCESS) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            return ResponseEntity.ok(Map.of("success", true, "message", "تم تغيير كلمة المرور بنجاح."));
        }
        if (result == PasswordChangeOutcome.INVALID_NEW_PASSWORD) {
            return ResponseEntity.badRequest()
                .body(Map.of("message", "كلمة المرور يجب أن تكون من 8 إلى 512 محرفًا."));
        }
        return ResponseEntity.badRequest()
            .body(Map.of("message", "تعذر تغيير كلمة المرور. تحقق من البيانات."));
    }

    private Optional<UserProfile> currentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return Optional.empty();
        Object stored = session.getAttribute(SESSION_KEY);
        if (!(stored instanceof SessionProof proof)) return Optional.empty();
        Optional<UserProfile> current = identity.current(proof);
        if (current.isEmpty()) session.removeAttribute(SESSION_KEY);
        return current;
    }

    private Map<String, Object> profileMap(UserProfile user) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", user.id());
        profile.put("username", user.username());
        profile.put("fullName", user.fullName());
        profile.put("role", user.role());
        return profile;
    }

    public record LoginRequest(String username, String password) {}
    public record ChangePasswordRequest(String username, String currentPassword, String newPassword) {}
}
