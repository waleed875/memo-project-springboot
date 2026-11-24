package io.github.waleed875.memo.modules.masterdata.infrastructure;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/** Local and unset deployment profiles are read-only; explicit opt-in required. */
@Component
public class JdbcMasterDataWriteGate implements MasterDataWriteGate {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String profileType;
    public JdbcMasterDataWriteGate(JdbcTemplate jdbc,
        @Value("${memo.masterdata.writes-enabled:false}") boolean enabled,
        @Value("${memo.masterdata.connection-profile-type:LOCAL}") String profileType) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.profileType = profileType;
    }
    @Override public void requireWritable() {
        if (!enabled || !"SERVER".equalsIgnoreCase(profileType))
            throw new MasterDataReadOnlyException();
        String mode = jdbc.query(
            "select value from system.app_settings where key = 'APP_MODE'",
            rs -> rs.next() ? rs.getString(1) : null);
        if (!"SERVER".equalsIgnoreCase(mode)) throw new MasterDataReadOnlyException();
    }
}
