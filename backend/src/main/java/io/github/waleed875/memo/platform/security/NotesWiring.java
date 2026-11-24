package io.github.waleed875.memo.platform.security;

import io.github.waleed875.memo.modules.notes.application.NoteDetailsQueryService;
import io.github.waleed875.memo.modules.notes.application.NoteDetailsReader;
import io.github.waleed875.memo.modules.notes.application.NoteDeleteService;
import io.github.waleed875.memo.modules.notes.application.NoteDeleteRepository;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.notes.application.NoteSearchReader;
import io.github.waleed875.memo.modules.notes.application.NoteSearchService;
import io.github.waleed875.memo.modules.notes.domain.NoteReadPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Compose note read use cases without coupling domain to HTTP or JDBC. */
@Configuration
public class NotesWiring {
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
