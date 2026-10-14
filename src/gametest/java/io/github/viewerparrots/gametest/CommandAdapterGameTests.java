package io.github.viewerparrots.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.viewerparrots.api.*;
import io.github.viewerparrots.api.event.ParrotEvent;
import io.github.viewerparrots.bootstrap.CheckpointScheduler;
import io.github.viewerparrots.bootstrap.FabricLifecycleAdapter;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.command.CommandFeedback;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Actual registered Brigadier commands, not calls to private adapter helpers. */
public final class CommandAdapterGameTests implements FabricGameTest {
    @GameTest(batchId = "adapter-durable", templateName = EMPTY_STRUCTURE, tickLimit = 1800)
    public void rejectionAndDurableAcceptanceHaveResultsFeedbackAndEvents(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> rejectionAndDurableAcceptanceHaveResultsFeedbackAndEvents(test))) return;
        Scenario scenario = new Scenario(test); scenario.guard(scenario::durable);
    }

    @GameTest(batchId = "adapter-diagnostics", templateName = EMPTY_STRUCTURE)
    public void allDiagnosticsShareExactlyOneSourceBudget(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> allDiagnosticsShareExactlyOneSourceBudget(test))) return;
        Scenario scenario = new Scenario(test); scenario.guard(scenario::diagnostics);
    }

    @GameTest(batchId = "adapter-suggestions", templateName = EMPTY_STRUCTURE)
    public void prefixFilteringPrecedesTheHundredSuggestionLimit(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> prefixFilteringPrecedesTheHundredSuggestionLimit(test))) return;
        Scenario scenario = new Scenario(test); scenario.guard(scenario::suggestions);
    }

    @GameTest(batchId = "adapter-shutdown", templateName = EMPTY_STRUCTURE, tickLimit = 1800)
    public void completionListenerCannotSaveAfterTheShutdownFlush(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> completionListenerCannotSaveAfterTheShutdownFlush(test))) return;
        Scenario scenario = new Scenario(test); scenario.guard(scenario::shutdown);
    }

    @GameTest(batchId = "adapter-error", templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void unexpectedGameplayFailureReturnsOnlyTheCommandId(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> unexpectedGameplayFailureReturnsOnlyTheCommandId(test))) return;
        Scenario scenario = new Scenario(test); scenario.guard(scenario::unexpectedFailure);
    }

    private static final class Scenario implements CommandOutput {
        static final ActorContext ADMIN = ActorContext.console(4);
        static final Pattern COMMAND_ID = Pattern.compile("\\[([0-9a-f-]{36})]");
        final TestContext test;
        ServerContext context = ServerContext.current().orElseThrow();
        final ViewerId id = ViewerId.of("adapter-" + UUID.randomUUID().toString().substring(0, 8));
        final List<String> feedback = new ArrayList<>();
        final List<ParrotEvent> events = new ArrayList<>();
        final List<ViewerId> dormant = new ArrayList<>();
        final Consumer<ParrotEvent> listener = event -> { if (id.equals(event.viewerId())) events.add(event); };
        final CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        ServerPlayerEntity player;
        ServerCommandSource source;
        boolean failed;
        Scenario(TestContext test) { this.test = test; }
        void source() {
            for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++)
                test.getWorld().setBlockState(test.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
            player = test.createMockCreativeServerPlayerInWorld();
            Vec3d position = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(1, 2, 1)));
            player.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
            source = player.getCommandSource().withLevel(4).withOutput(this);
        }
        int execute(String command) {
            try { return context.server().getCommandManager().getDispatcher().execute(command, source); }
            catch (CommandSyntaxException error) { feedback.add(error.getMessage()); return 0; }
        }
        void durable() {
            source(); context.events().subscribe(listener);
            check(execute("vp rename " + id + " Missing") == 0, "Immediately rejected missing-viewer mutation returns Brigadier zero");
            check(events.size() == 1 && events.getFirst().type() == ParrotEvent.Type.COMMAND_REJECTED,
                    "Rejected actual command publishes its real command UUID exactly once");
            check(feedback.getLast().contains(events.getFirst().commandId().toString()), "Rejected feedback exposes the complete event command UUID");
            await(context.streamerService().assign(UUID.randomUUID(), ADMIN, player.getUuid()), assigned -> {
                check(assigned.succeeded(), "Adapter fixture assigns its live target");
                context.writer().submit(() -> {
                    entered.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("adapter writer gate timed out");
                    return null;
                });
                until(() -> entered.getCount() == 0, () -> {
                    int before = feedback.size();
                    check(execute("vp create " + id + " Adapter name") == 1, "Pending durable create returns accepted Brigadier one");
                    check(!context.registry().contains(id), "Writer gate keeps actual durable create unfinished");
                    check(feedback.size() == before + 1, "Accepted feedback is immediate while disk work remains blocked");
                    var match = COMMAND_ID.matcher(feedback.getLast());
                    check(match.find(), "Accepted feedback includes a complete command UUID");
                    UUID command = UUID.fromString(match.group(1));
                    check(events.stream().filter(event -> command.equals(event.commandId())).map(ParrotEvent::type).toList()
                                    .equals(List.of(ParrotEvent.Type.COMMAND_ACCEPTED)), "Actual create has one accepted phase before commit");
                    release.countDown();
                    until(() -> events.stream().anyMatch(event -> command.equals(event.commandId()) && event.type() == ParrotEvent.Type.COMMAND_COMPLETED), () -> {
                        check(context.loadedIndex().isLoaded(id), "Terminal create event follows real durable binding");
                        check(feedback.size() == before + 2 && feedback.getLast().contains(command.toString()), "Terminal feedback retains the accepted UUID");
                        check(execute("vp create " + id + " Duplicate") == 0, "Synchronous duplicate create returns Brigadier zero");
                        check(execute("vp remove " + id) == 1, "Actual durable remove is admitted");
                        until(() -> !context.registry().contains(id) && !context.parrotService().operationPending(id), this::finish);
                    });
                });
            });
        }
        void diagnostics() {
            source(); dormant(id);
            await(context.streamerService().assign(UUID.randomUUID(), ADMIN, player.getUuid()), assigned -> {
                check(assigned.succeeded(), "Diagnostic fixture has a configured target");
                diagnosticRequests();
            });
        }
        void diagnosticRequests() {
            for (String command : List.of("vp help", "vp streamer", "vp list", "vp status", "vp help"))
                check(execute(command) > 0, "First five diagnostics share one token each: " + command);
            check(execute("vp streamer") == 0, "Sixth help/streamer diagnostic is rejected in the same source window");
            String message = feedback.getLast(); var match = COMMAND_ID.matcher(message);
            check(match.find(), "Diagnostic denial exposes the full actual command UUID");
            UUID command = UUID.fromString(match.group(1));
            CommandResult denied = context.api().submit(new CommandRequest(command, null, ParrotAction.STREAMER,
                    ActionParameters.None.INSTANCE), ActorContext.player(player.getUuid(), 4)).toCompletableFuture().getNow(null);
            check(denied.code() == ErrorCode.GLOBAL_RATE_LIMITED && denied.retryAfterTicks() > 0 && denied.retryAfterTicks() <= 20,
                    "The denied actual request carries its stable error code and bounded retry ticks");
            check(message.equals(CommandFeedback.render(denied).getString()), "CLI denial renders the actual retry result in the current locale");
            test.waitAndRun(20, () -> guard(() -> {
                check(execute("vp help") == 1, "Diagnostic budget replenishes after twenty actual server ticks"); finish();
            }));
        }
        void suggestions() {
            source(); String suffix = id.value();
            for (int i = 0; i < 110; i++) dormant(ViewerId.of("aa-" + suffix + "-" + i));
            ViewerId match = ViewerId.of("zz-" + suffix); dormant(match);
            var dispatcher = context.server().getCommandManager().getDispatcher();
            // Brigadier deliberately omits suggestions equal to an already complete token.
            String prefix = match.value().substring(0, match.value().length() - 1);
            var parsed = dispatcher.parse("vp info " + prefix, source);
            await(dispatcher.getCompletionSuggestions(parsed), suggestions -> {
                check(suggestions.getList().stream().anyMatch(value -> value.getText().equals(match.value())),
                        "A matching viewer beyond the first hundred sorted records is suggested: actual=" + suggestions.getList()
                                + ", expected=" + match.value() + ", parsed=" + parsed.getContext().getNodes()
                                + ", child=" + (parsed.getContext().getChild() == null ? "none" : parsed.getContext().getChild().getNodes())
                                + ", active=" + context.acceptingCommands() + ", permission=" + source.hasPermissionLevel(2));
                check(suggestions.getList().size() <= 100, "Suggestions remain bounded after matching");
                finish();
            });
        }
        void dormant(ViewerId viewer) {
            dormant.add(viewer);
            context.registry().put(ViewerParrotRecord.created(new Viewer(viewer, DisplayName.of("Suggestion"), context.clock().now()),
                    UUID.randomUUID(), UUID.randomUUID(), ParrotVariant.BLUE, ParrotTrait.NORMAL, null, context.clock().now()).asDead(context.clock().now()));
        }
        void unexpectedFailure() {
            source();
            await(context.streamerService().assign(UUID.randomUUID(), ADMIN, player.getUuid()), assigned -> {
                check(assigned.succeeded(), "Error-boundary fixture assigns target");
                await(context.parrotService().create(UUID.randomUUID(), ADMIN, id, DisplayName.of("Error boundary")), created -> {
                    check(created.succeeded(), "Error-boundary fixture creates its real entity");
                    try {
                        var field = ServerContext.class.getDeclaredField("rateLimiter");
                        field.setAccessible(true); Object original = field.get(context);
                        try {
                            // Fail actual validation before acceptance/reservation; no production hook.
                            field.set(context, null);
                            check(execute("vp sit " + id) == 0, "Unexpected gameplay failure returns synchronous zero instead of escaping Brigadier");
                        } finally { field.set(context, original); }
                    } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                    String message = feedback.getLast(); var match = COMMAND_ID.matcher(message);
                    check(match.find(), "Unexpected failure feedback includes the full actual command UUID");
                    UUID command = UUID.fromString(match.group(1));
                    check(!message.contains("rateLimiter") && !message.contains("NullPointerException") && !message.contains("at io.github"),
                            "Exception details and stack stay out of user feedback");
                    check(context.commandService().diagnostics().get("pendingCommands") == 0, "Failure before admission leaves no pending command");
                    check(context.loadedIndex().isLoaded(id), "Internal error does not discard the valid physical entity");
                    await(context.api().submit(new CommandRequest(command, id, ParrotAction.SIT, ActionParameters.None.INSTANCE), ADMIN).toCompletableFuture(), cached -> {
                        check(cached.status() == CommandStatus.FAILED && cached.code() == ErrorCode.INTERNAL_ERROR,
                                "The actual adapter command ID resolves to its terminal internal-error result");
                        check(message.equals(CommandFeedback.render(cached).getString()), "User feedback renders that canonical result in the current locale");
                        await(context.parrotService().remove(UUID.randomUUID(), ADMIN, id), removed -> {
                            check(removed.succeeded(), "Error-boundary fixture cleans up durably"); finish();
                        });
                    });
                });
            });
        }
        void shutdown() {
            source();
            await(context.streamerService().assign(UUID.randomUUID(), ADMIN, player.getUuid()), assigned -> {
                check(assigned.succeeded(), "Shutdown adapter target is assigned");
                await(context.parrotService().create(UUID.randomUUID(), ADMIN, id, DisplayName.of("Shutdown adapter")), created -> {
                    check(created.succeeded(), "Shutdown adapter fixture is created");
                    await(context.parrotService().setTrait(UUID.randomUUID(), ADMIN, id, ParrotTrait.NORMAL), trait -> {
                        check(trait.succeeded(), "Shutdown command has no deferred trait modifier");
                        UUID command = UUID.randomUUID();
                        check(context.commandService().submit(command, ADMIN, ParrotAction.DANCE, id).status() == CommandStatus.ACCEPTED,
                                "Real unfinished dance is cancelled by shutdown after the final flush");
                        AtomicReference<CompletableFuture<?>> checkpoint = new AtomicReference<>();
                        int[] brigadier = {-1}, pending = {-1, -1}; long[] epoch = {-1, -1}, sequence = {-1, -1};
                        context.commandService().completion(command).thenAccept(cancelled -> {
                            pending[0] = context.writer().pendingTasks(); epoch[0] = context.registry().changeEpoch();
                            sequence[0] = context.repository().checkpointSequence();
                            brigadier[0] = execute("vp save"); checkpoint.set(CheckpointScheduler.request(context));
                            pending[1] = context.writer().pendingTasks(); epoch[1] = context.registry().changeEpoch();
                            sequence[1] = context.repository().checkpointSequence();
                        });
                        ServerContext.stop(context.server());
                        check(brigadier[0] == 0, "Brigadier cannot admit late SAVE during shutdown completion");
                        check(checkpoint.get() != null && checkpoint.get().isCompletedExceptionally(), "Direct checkpoint ingress also rejects before snapshot capture");
                        check(pending[0] == 0 && pending[1] == 0 && epoch[0] == epoch[1] && sequence[0] == sequence[1],
                                "Late ingress queues no I/O and changes neither captured state nor final checkpoint");
                        context = restart();
                        until(() -> context.storageHealth() != ServerContext.StorageHealth.LOADING, () -> {
                            check(context.allowsMutations(), "Replacement context initializes after shutdown regression");
                            await(context.parrotService().remove(UUID.randomUUID(), ADMIN, id), removed -> {
                                check(removed.succeeded(), "Shutdown fixture cleans up durably"); finish();
                            });
                        });
                    });
                });
            });
        }
        ServerContext restart() {
            try {
                var method = FabricLifecycleAdapter.class.getDeclaredMethod("onServerStarted", net.minecraft.server.MinecraftServer.class);
                method.setAccessible(true); method.invoke(null, test.getWorld().getServer()); return ServerContext.current().orElseThrow();
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void until(java.util.function.BooleanSupplier ready, Runnable next) {
            if (ready.getAsBoolean()) guard(next); else test.waitAndRun(1, () -> guard(() -> until(ready, next)));
        }
        <T> void await(CompletableFuture<T> work, Consumer<T> next) {
            if (work.isDone()) guard(() -> next.accept(work.getNow(null))); else test.waitAndRun(1, () -> guard(() -> await(work, next)));
        }
        void guard(Runnable work) {
            if (failed) return;
            try { work.run(); }
            catch (RuntimeException | Error error) {
                failed = true; release.countDown(); detach();
                // A negative shutdown assertion must not poison every later test batch.
                if (ServerContext.current().isEmpty()) context = restart();
                throw error;
            }
        }
        void check(boolean condition, String message) { test.assertTrue(condition, message); }
        void finish() { release.countDown(); detach(); test.complete(); }
        void detach() {
            context.events().unsubscribe(listener);
            for (ViewerId viewer : dormant) context.registry().remove(viewer, context.clock().now());
            if (player != null && context.server().getPlayerManager().getPlayer(player.getUuid()) != null) context.server().getPlayerManager().remove(player);
        }
        @Override public void sendMessage(Text message) { feedback.add(message.getString()); }
        @Override public boolean shouldReceiveFeedback() { return true; }
        @Override public boolean shouldTrackOutput() { return true; }
        @Override public boolean shouldBroadcastConsoleToOps() { return false; }
    }
}
