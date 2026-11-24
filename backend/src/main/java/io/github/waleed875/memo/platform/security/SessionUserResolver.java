package io.github.waleed875.memo.platform.security;

import io.github.waleed875.memo.modules.identity.application.IdentityApplicationService;
import io.github.waleed875.memo.modules.identity.application.SessionProof;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Web adapter shared by authenticated HTTP slices.
 *
 * The existing auth service validates the user, role and security stamp against
 * the database on every request. HTTP controllers must not trust a bare cookie.
 */
@Component
public class SessionUserResolver {
    public static final String SESSION_KEY = "memo.authenticated.session";

    private final IdentityApplicationService identity;

    public SessionUserResolver(IdentityApplicationService identity) {
        this.identity = identity;
    }

    public Optional<UserProfile> resolve(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return Optional.empty();
        Object stored = session.getAttribute(SESSION_KEY);
        if (!(stored instanceof SessionProof proof)) return Optional.empty();
        Optional<UserProfile> current = identity.current(proof);
        if (current.isEmpty()) session.removeAttribute(SESSION_KEY);
        return current;
    }
}
