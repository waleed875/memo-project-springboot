package io.github.waleed875.memo.modules.masterdata.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldDefinition;
import java.util.UUID;

/** Framework-free command use cases for dynamic indicator definitions. */
public final class NoteDefinitionCommandService {
    private static final UUID EMPTY = new UUID(0L, 0L);
    private final NoteDefinitionWriter writer;
    private final MasterDataWriteGate gate;

    public NoteDefinitionCommandService(NoteDefinitionWriter writer, MasterDataWriteGate gate) {
        this.writer = writer;
        this.gate = gate;
    }

    private UUID authorizedAdmin(UserProfile user) {
        if (user == null || user.id() == null || user.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        if (!"ADMIN".equals(user.role())) throw new MasterDataForbiddenException();
        gate.requireWritable();
        return user.id();
    }

    public NoteTypeFieldDefinition save(UserProfile user, NoteTypeFieldDefinition value) {
        UUID actor = authorizedAdmin(user);
        if (value == null || value.noteTypeId() == null || EMPTY.equals(value.noteTypeId())
            || value.fieldCode() == null || value.fieldCode().isBlank()
            || value.fieldNameAr() == null || value.fieldNameAr().isBlank()) {
            throw new MasterDataOperationException("نوع المذكرة ورمز البند واسمه مطلوبة.");
        }
        return writer.save(new NoteTypeFieldDefinition(
            value.id(), value.noteTypeId(), value.fieldId(),
            value.fieldCode().trim(), value.fieldNameAr().trim(),
            value.description(), value.sortOrder(), value.isRequired(), value.isActive()
        ), actor);
    }

    public void deactivate(UserProfile user, UUID definitionId) {
        UUID actor = authorizedAdmin(user);
        if (definitionId == null || EMPTY.equals(definitionId)) {
            throw new MasterDataOperationException("تعريف البند غير موجود.");
        }
        writer.deactivate(definitionId, actor);
    }
}
