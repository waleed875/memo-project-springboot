package io.github.waleed875.memo.modules.notes.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteSourcePolicyTest {
    @Test void exitRequiresUniqueNonzeroReferences() {
        assertThrows(NoteSaveValidationException.class, () -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Exit,null,null,List.of(),List.of())));
        UUID id = UUID.randomUUID();
        assertThrows(NoteSaveValidationException.class, () -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Exit,null,null,List.of(
                new NoteSourceDraft.ExitReference(id,1),
                new NoteSourceDraft.ExitReference(id,2)),List.of())));
        assertDoesNotThrow(() -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Exit,null,null,List.of(
                new NoteSourceDraft.ExitReference(id,1)),List.of())));
    }

    @Test void invoiceRequiresNumberAndValidatedAttachment() {
        assertThrows(NoteSaveValidationException.class, () -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Invoice,null,null,List.of(),List.of())));
        assertThrows(NoteSaveValidationException.class, () -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Invoice,null,null,List.of(),
                List.of(new NoteSourceDraft.Invoice(" ",null,null,null)))));
        assertThrows(NoteSaveValidationException.class, () -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Invoice,null,null,List.of(),
                List.of(new NoteSourceDraft.Invoice("INV-1",null,null,
                    new NoteAttachment("bad.pdf",new byte[]{1,2,3}))))));
        assertDoesNotThrow(() -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Invoice,null,null,List.of(),
                List.of(new NoteSourceDraft.Invoice("INV-1",null,null,null)))));
    }

    @Test void lootAndOtherRequireDescriptionsAndRejectMixedPayloads() {
        assertThrows(NoteSaveValidationException.class, () -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Loot,null,"  ",List.of(),List.of())));
        assertThrows(NoteSaveValidationException.class, () -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Other," ",null,List.of(),List.of())));
        assertThrows(NoteSaveValidationException.class, () -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Loot,null,"غنائم",List.of(
                new NoteSourceDraft.ExitReference(UUID.randomUUID(),1)),List.of())));
        assertDoesNotThrow(() -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Loot,null,"غنائم",List.of(),List.of())));
        assertDoesNotThrow(() -> NoteSourcePolicy.validate(
            new NoteSourceDraft(NoteSourceType.Other,"أخرى",null,List.of(),List.of())));
    }
}
