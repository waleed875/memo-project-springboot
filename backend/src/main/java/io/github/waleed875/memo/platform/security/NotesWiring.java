package io.github.waleed875.memo.platform.security;

import io.github.waleed875.memo.modules.notes.application.NoteDetailsQueryService;
import io.github.waleed875.memo.modules.notes.application.NoteDetailsReader;
import io.github.waleed875.memo.modules.notes.domain.NoteReadPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Compose note read use cases without coupling domain to HTTP or JDBC. */
@Configuration
public class NotesWiring {
    @Bean
    NoteDetailsQueryService noteDetailsQueryService(NoteDetailsReader reader) {
        return new NoteDetailsQueryService(reader, new NoteReadPolicy());
    }
}
