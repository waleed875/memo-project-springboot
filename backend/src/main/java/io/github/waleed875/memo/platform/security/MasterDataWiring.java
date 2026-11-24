package io.github.waleed875.memo.platform.security;

import io.github.waleed875.memo.modules.masterdata.application.NoteDefinitionReader;
import io.github.waleed875.memo.modules.masterdata.application.NoteDefinitionCommandService;
import io.github.waleed875.memo.modules.masterdata.application.NoteDefinitionWriter;
import io.github.waleed875.memo.modules.masterdata.application.NoteDefinitionQueryService;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataQueryService;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReader;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataCommandService;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteRepository;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Compose the pure application use case with its JDBC port. */
@Configuration
public class MasterDataWiring {
    @Bean
    NoteDefinitionCommandService noteDefinitionCommandService(
        NoteDefinitionWriter writer, MasterDataWriteGate gate
    ) {
        return new NoteDefinitionCommandService(writer, gate);
    }

    @Bean
    NoteDefinitionQueryService noteDefinitionQueryService(NoteDefinitionReader reader) {
        return new NoteDefinitionQueryService(reader);
    }

    @Bean
    MasterDataQueryService masterDataQueryService(MasterDataReader reader) {
        return new MasterDataQueryService(reader);
    }
    @Bean
    MasterDataCommandService masterDataCommandService(
        MasterDataWriteRepository writer, MasterDataWriteGate gate
    ) {
        return new MasterDataCommandService(writer, gate);
    }
}
