package io.github.waleed875.memo.modules.dashboard.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Preview metadata is OUTSIDE the original DashboardSnapshot JSON shape. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DashboardLegacySnapshotPreview(
    DashboardLegacySnapshot snapshot,
    List<String> parityGaps,
    boolean readyForPublicApi
) {
    public DashboardLegacySnapshotPreview {
        parityGaps = List.copyOf(parityGaps);
    }
}
