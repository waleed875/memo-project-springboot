package io.github.waleed875.memo.modules.masterdata.application;

import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldDefinition;
import java.util.UUID;

/** Write port: persistence mutation and structured audit must be one transaction. */
public interface NoteDefinitionWriter {
    NoteTypeFieldDefinition save(NoteTypeFieldDefinition definition, UUID actorId);
    void deactivate(UUID definitionId, UUID actorId);
}
