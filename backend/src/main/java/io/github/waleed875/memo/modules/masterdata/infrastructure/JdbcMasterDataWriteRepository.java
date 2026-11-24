package io.github.waleed875.memo.modules.masterdata.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteRepository;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundary for the complete command: business SQL + audit.logs.
 * All commands use the legacy schema; no DDL or migration is performed.
 *
 * Protects parent rows with FOR UPDATE before checks to serialize writes
 * against other commands that follow the same locking convention. Legacy
 * PostgreSQL FK/trigger rules remain the ultimate data-integrity boundary.
 */
@Repository
public class JdbcMasterDataWriteRepository implements MasterDataWriteRepository {
    private final JdbcTemplate jdbc;

    public JdbcMasterDataWriteRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private boolean lock(String table, UUID id) {
        String sql = "select id from core." + table + " where id = ? and not is_deleted for update";
        return !jdbc.query(sql, (rs, n) -> rs.getObject(1, UUID.class), id).isEmpty();
    }

    private void required(boolean ok, String message) {
        if (!ok) throw new MasterDataOperationException(message);
    }

    private boolean used(String table, String foreignKey, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from core." + table +
            " where " + foreignKey + " = ? and not is_deleted)", Boolean.class, id));
    }

    private String activeName(String table, UUID id, String message) {
        List<String> names = jdbc.query(
            "select name from core." + table +
            " where id = ? and is_active and not is_deleted for update",
            (rs, n) -> rs.getString(1), id);
        if (names.isEmpty()) throw new MasterDataOperationException(message);
        return names.get(0);
    }

    private void audit(UUID actor, String action, String table, UUID id, String description) {
        jdbc.update("""
            insert into audit.logs(user_id, action_type, schema_name,
                                   table_name, record_id, description)
            values (?, ?, 'core', ?, ?, ?)
            """, actor, action, table, id, description);
    }

    @Override @Transactional
    public Company saveCompany(Company value, UUID actor, boolean create) {
        if (!create) {
            required(lock("companies", value.id()), "الشركة غير موجودة أو حُذفت مسبقاً.");
        }
        if (create) {
            jdbc.update("insert into core.companies(id, name, is_active) values (?, ?, ?)",
                value.id(), value.name(), value.isActive());
        } else {
            required(jdbc.update("""
                update core.companies set name = ?, is_active = ?, updated_at = now()
                where id = ? and not is_deleted
                """, value.name(), value.isActive(), value.id()) == 1,
                "الشركة غير موجودة أو حُذفت مسبقاً.");
        }
        audit(actor, create ? "CREATE_COMPANY" : "UPDATE_COMPANY",
            "companies", value.id(), value.name());
        return value;
    }

    @Override @Transactional
    public void deleteCompany(UUID id, UUID actor) {
        required(lock("companies", id), "الشركة غير موجودة أو حُذفت مسبقًا.");
        required(!used("notes", "company_id", id),
            "لا يمكن حذف شركة مرتبطة بمذكرات. يمكن تعطيلها بدلًا من ذلك.");
        required(!used("factories", "company_id", id),
            "لا يمكن حذف شركة مرتبطة بمصانع. انقل المصانع أو احذفها أولًا.");
        required(jdbc.update("update core.companies set is_deleted = true where id = ? and not is_deleted", id) == 1,
            "الشركة غير موجودة أو حُذفت مسبقًا.");
        audit(actor, "DELETE_COMPANY", "companies", id, "حذف منطقي للشركة");
    }

    @Override @Transactional
    public Factory saveFactory(Factory value, UUID actor, boolean create) {
        String companyName = activeName(
            "companies", value.companyId(), "الشركة المحددة غير موجودة أو غير فعالة.");
        if (!create) {
            required(lock("factories", value.id()), "المعمل غير موجود أو حُذف مسبقًا.");
            UUID previous = jdbc.queryForObject(
                "select company_id from core.factories where id = ?", UUID.class, value.id());
            if (!value.companyId().equals(previous)) {
                required(!used("notes", "factory_id", value.id()),
                    "لا يمكن نقل مصنع مستخدم في مذكرات إلى شركة أخرى.");
                required(!used("production_lines", "factory_id", value.id()),
                    "لا يمكن نقل معمل مرتبط بخطوط إنتاج إلى شركة أخرى.");
            }
        }
        try {
            if (create) {
                jdbc.update("""
                    insert into core.factories(id, name, company_id, is_active)
                    values (?, ?, ?, ?)
                    """, value.id(), value.name(), value.companyId(), value.isActive());
            } else {
                required(jdbc.update("""
                    update core.factories
                    set name = ?, company_id = ?, is_active = ?, updated_at = now()
                    where id = ? and not is_deleted
                    """, value.name(), value.companyId(), value.isActive(), value.id()) == 1,
                    "المعمل غير موجود أو حُذف مسبقًا.");
            }
        } catch (DataIntegrityViolationException e) {
            throw new MasterDataOperationException("يوجد معمل بالاسم نفسه ضمن هذه الشركة أو توجد قيود ارتباط.");
        }
        audit(actor, create ? "CREATE_FACTORY" : "UPDATE_FACTORY",
            "factories", value.id(), companyName + " - " + value.name());
        return new Factory(value.id(), value.name(), value.companyId(), companyName, value.isActive());
    }

    @Override @Transactional
    public void deleteFactory(UUID id, UUID actor) {
        required(lock("factories", id), "المعمل غير موجود أو حُذف مسبقًا.");
        required(!used("notes", "factory_id", id),
            "لا يمكن حذف معمل مستخدم في مذكرات. يمكن تعطيله بدلًا من ذلك.");
        required(!used("production_lines", "factory_id", id),
            "لا يمكن حذف معمل مرتبط بخطوط إنتاج. احذف خطوط الإنتاج أولًا.");
        required(jdbc.update("""
            update core.factories set is_deleted = true, updated_at = now()
            where id = ? and not is_deleted
            """, id) == 1, "المعمل غير موجود أو حُذف مسبقًا.");
        audit(actor, "DELETE_FACTORY", "factories", id, "حذف منطقي للمعمل");
    }

    @Override @Transactional
    public ProductionLine saveProductionLine(ProductionLine value, UUID actor, boolean create) {
        String factoryName = activeName(
            "factories", value.factoryId(), "المعمل المحدد غير موجود أو غير فعال.");
        if (!create) {
            required(lock("production_lines", value.id()), "خط الإنتاج غير موجود أو حُذف مسبقاً.");
            UUID previous = jdbc.queryForObject("""
                select factory_id from core.production_lines where id = ?
                """, UUID.class, value.id());
            if (!value.factoryId().equals(previous)) {
                required(!used("notes", "production_line_id", value.id()),
                    "لا يمكن نقل خط إنتاج مستخدم في مذكرات.");
            }
        }
        try {
            if (create) {
                jdbc.update("""
                    insert into core.production_lines(id, factory_id, name, is_active)
                    values (?, ?, ?, ?)
                    """, value.id(), value.factoryId(), value.name(), value.isActive());
            } else {
                required(jdbc.update("""
                    update core.production_lines
                    set factory_id = ?, name = ?, is_active = ?, updated_at = now()
                    where id = ? and not is_deleted
                    """, value.factoryId(), value.name(), value.isActive(), value.id()) == 1,
                    "خط الإنتاج غير موجود أو حُذف مسبقاً.");
            }
        } catch (DataIntegrityViolationException e) {
            throw new MasterDataOperationException("يوجد خط إنتاج بالاسم نفسه ضمن المعمل أو توجد قيود ارتباط.");
        }
        List<UUID> companyIds = jdbc.query(
            "select company_id from core.factories where id = ?",
            (rs, row) -> rs.getObject(1, UUID.class), value.factoryId());
        UUID companyId = companyIds.isEmpty() ? null : companyIds.get(0);
        String companyName = companyId == null ? null : jdbc.queryForObject(
            "select name from core.companies where id = ?", String.class, companyId);
        audit(actor, create ? "CREATE_PRODUCTION_LINE" : "UPDATE_PRODUCTION_LINE",
            "production_lines", value.id(), value.name());
        return new ProductionLine(value.id(), value.factoryId(), factoryName,
            companyId, companyName, value.name(), value.isActive());
    }

    @Override @Transactional
    public void deleteProductionLine(UUID id, UUID actor) {
        required(lock("production_lines", id), "خط الإنتاج غير موجود.");
        required(!used("notes", "production_line_id", id),
            "لا يمكن حذف خط إنتاج مستخدم في مذكرات.");
        required(jdbc.update("""
            update core.production_lines set is_deleted = true, updated_at = now()
            where id = ? and not is_deleted
            """, id) == 1, "خط الإنتاج غير موجود.");
        audit(actor, "DELETE_PRODUCTION_LINE", "production_lines", id, "Soft delete production line");
    }

    @Override @Transactional
    public void toggleProductionLine(UUID id, boolean active, UUID actor) {
        required(lock("production_lines", id), "خط الإنتاج غير موجود.");
        required(jdbc.update("""
            update core.production_lines set is_active = ?, updated_at = now()
            where id = ? and not is_deleted
            """, active, id) == 1, "خط الإنتاج غير موجود.");
        audit(actor, "TOGGLE_PRODUCTION_LINE", "production_lines", id,
            active ? "تفعيل خط الإنتاج" : "تعطيل خط الإنتاج");
    }
}
