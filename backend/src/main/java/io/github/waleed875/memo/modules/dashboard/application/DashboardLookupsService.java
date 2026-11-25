package io.github.waleed875.memo.modules.dashboard.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.UUID;

/**
 * Restricted admin dashboard query. Mirrors the original IWebAdminAuthService
 * boundary rather than exposing admin-only lookups to any authenticated user.
 * The JDBC port independently rechecks the active DB role.
 */
public final class DashboardLookupsService {
    private static final UUID EMPTY = new UUID(0L, 0L);
    private final DashboardLookupsReader reader;

    public DashboardLookupsService(DashboardLookupsReader reader) {
        this.reader = reader;
    }

    public DashboardLookups read(UserProfile actor) {
        if (actor == null || actor.id() == null
            || EMPTY.equals(actor.id()) || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException unknownRole) {
            throw new MasterDataForbiddenException();
        }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        return reader.read(actor.id());
    }
}
