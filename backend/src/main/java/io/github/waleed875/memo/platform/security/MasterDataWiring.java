package io.github.waleed875.memo.platform.security;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataQueryService;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Compose the pure application use case with its JDBC port. */
@Configuration
public class MasterDataWiring {
    @Bean
    MasterDataQueryService masterDataQueryService(MasterDataReader reader) {
        return new MasterDataQueryService(reader);
    }
}
