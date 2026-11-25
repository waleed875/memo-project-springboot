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
import io.github.waleed875.memo.modules.notes.application.NoteSourceStore;
import io.github.waleed875.memo.modules.notes.application.NoteSourceService;
import io.github.waleed875.memo.modules.notes.application.NoteReviewQueueReader;
import io.github.waleed875.memo.modules.notes.application.NoteReviewQueueService;
import io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionService;
import io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditService;
import io.github.waleed875.memo.modules.notes.application.AdminNoteTextEditRepository;
import io.github.waleed875.memo.modules.notes.application.AdminNoteTextEditService;
import io.github.waleed875.memo.modules.notes.application.AdminNoteIndicatorEditRepository;
import io.github.waleed875.memo.modules.notes.application.AdminNoteIndicatorEditService;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentUploadRepository;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentUploadService;
import java.time.Clock;
import java.time.ZoneId;
import io.github.waleed875.memo.modules.notes.domain.NoteReadPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Compose note read use cases without coupling domain to HTTP or JDBC. */
@Configuration
public class NotesWiring {
    @Bean
    AdminNoteAttachmentUploadService adminNoteAttachmentUploadService(
        AdminNoteAttachmentUploadRepository repository, MasterDataWriteGate gate
    ) {
        return new AdminNoteAttachmentUploadService(repository, gate);
    }


    @Bean
    AdminNoteIndicatorEditService adminNoteIndicatorEditService(
        AdminNoteIndicatorEditRepository repository, MasterDataWriteGate gate
    ) {
        return new AdminNoteIndicatorEditService(
            repository, gate, Clock.system(ZoneId.of("Asia/Damascus")));
    }


    @Bean
    AdminNoteTextEditService adminNoteTextEditService(
        AdminNoteTextEditRepository repository, MasterDataWriteGate gate
    ) {
        return new AdminNoteTextEditService(repository, gate);
    }


    @Bean
    NoteDraftEditService noteDraftEditService(
        NoteDraftEditRepository repository, MasterDataWriteGate gate
    ) {
        return new NoteDraftEditService(
            repository, gate, Clock.system(ZoneId.of("Asia/Damascus")));
    }


    @Bean
    NoteReviewTransitionService noteReviewTransitionService(
        NoteReviewTransitionRepository repository, MasterDataWriteGate gate
    ) {
        return new NoteReviewTransitionService(repository, gate);
    }

    @Bean
    NoteReviewQueueService noteReviewQueueService(NoteReviewQueueReader reader) {
        return new NoteReviewQueueService(reader);
    }

    @Bean
    NoteSourceService noteSourceService(
        NoteDetailsReader details, NoteSourceStore source, MasterDataWriteGate gate
    ) {
        return new NoteSourceService(details, source, gate);
    }

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
