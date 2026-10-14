package io.github.viewerparrots.api;

import io.github.viewerparrots.domain.ViewerId;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Trusted Java adapters construct ActorContext; both methods run on the server thread. */
public interface ViewerParrotApi {
    CompletionStage<CommandResult> submit(CommandRequest request, ActorContext actor);
    Optional<ParrotSnapshot> get(ViewerId viewerId);
}
