package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.HashSet;
import java.util.UUID;

/**
 * Spring-free, admin-only application layer; intentionally no HTTP mapping.
 * Replaces existing links only when the client supplies an explicit set.
 */
public final class AdminNoteLinkSyncService {
    public static final int MAX_LINKS = 100;
    private static final UUID EMPTY = new UUID(0L, 0L);
    private final AdminNoteLinkSyncRepository repository;
    private final MasterDataWriteGate writeGate;

    public AdminNoteLinkSyncService(
        AdminNoteLinkSyncRepository repository, MasterDataWriteGate writeGate
    ) {
        this.repository = repository;
        this.writeGate = writeGate;
    }

    public AdminNoteLinkSyncRepository.SyncResult sync(
        UserProfile actor, AdminNoteLinkSyncCommand command
    ) {
        if (actor == null || actor.id() == null || EMPTY.equals(actor.id())
            || actor.role() == null) throw new UnauthenticatedMasterDataAccess();
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException invalid) {
            throw new MasterDataForbiddenException();
        }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        writeGate.requireWritable();
        validate(command);
        return repository.sync(command, actor.id());
    }

    public static void validate(AdminNoteLinkSyncCommand command) {
        if (command == null || command.noteId() == null
            || EMPTY.equals(command.noteId()) || command.expectedUpdatedAt() == null
            || command.expectedRelatedNoteIds() == null
            || command.expectedRelatedNoteIds().size() > MAX_LINKS
            || command.relatedNoteIds() == null
            || command.relatedNoteIds().size() > MAX_LINKS) {
            throw new NoteSaveValidationException(
                "معرّف المذكرة ونسخة التعديل وقائمة الروابط الصريحة مطلوبة.");
        }
        var expected = new HashSet<UUID>();
        for (UUID id : command.expectedRelatedNoteIds()) {
            if (id == null || EMPTY.equals(id) || id.equals(command.noteId())
                || !expected.add(id)) {
                throw new NoteSaveValidationException(
                    "قائمة الروابط السابقة غير صالحة أو تحتوي على تكرار.");
            }
        }
        var ids = new HashSet<UUID>();
        for (UUID id : command.relatedNoteIds()) {
            if (id == null || EMPTY.equals(id) || id.equals(command.noteId())
                || !ids.add(id)) {
                throw new NoteSaveValidationException(
                    "لا يمكن ربط المذكرة بنفسها أو تكرار معرف مذكرة مرتبطة.");
            }
        }
    }
}
