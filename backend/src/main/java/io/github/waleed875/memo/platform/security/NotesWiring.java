package io.github.waleed875.memo.platform.security;

import io.github.waleed875.memo.modules.notes.application.NoteDetailsQueryService;
import io.github.waleed875.memo.modules.notes.application.NoteDetailsReader;
import io.github.waleed875.memo.modules.notes.application.NoteDeleteService;
import io.github.waleed875.memo.modules.notes.application.NoteDeleteRepository;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.notes.application.NoteSearchReader;
import io.github.waleed875.memo.modules.notes.application.NoteSearchService;
import io.github.waleed875.memo.modules.notes.application.NoteSavePreflight;
import io.github.waleed875.memo.modules.notes.application.NoteSaveCatalog;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateReader;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateQueryService;
import io.github.waleed875.memo.modules.notes.application.NoteCreateService;
import io.github.waleed875.memo.modules.notes.application.NoteCreator;
import java.time.Clock;
import java.time.ZoneId;
import io.github.waleed875.memo.modules.notes.domain.NoteReadPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Compose note read use cases without coupling domain to HTTP or JDBC. */
@Configuration
public class NotesWiring {
    @Bean
    NoteCreateService noteCreateService(NoteCreator creator, MasterDataWriteGate gate) {
        return new NoteCreateService(creator, gate, Clock.system(ZoneId.of("Asia/Damascus")));
    }

    @Bean
    NoteDuplicateQueryService noteDuplicateQueryService(NoteDuplicateReader reader) {
        return new NoteDuplicateQueryService(reader);
    }

    @Bean
    NoteSavePreflight noteSavePreflight(NoteSaveCatalog catalog, MasterDataWriteGate gate) {
        return new NoteSavePreflight(catalog, gate, Clock.system(ZoneId.of("Asia/Damascus")));
    }

    @Bean
    NoteDeleteService noteDeleteService(NoteDeleteRepository repository, MasterDataWriteGate gate) {
        return new NoteDeleteService(repository, gate);
    }

    @Bean
    NoteSearchService noteSearchService(NoteSearchReader reader) {
        return new NoteSearchService(reader);
    }

    @Bean
    NoteDetailsQueryService noteDetailsQueryService(NoteDetailsReader reader) {
        return new NoteDetailsQueryService(reader, new NoteReadPolicy());
    }
}
