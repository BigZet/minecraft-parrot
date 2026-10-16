package io.github.viewerparrots.network;

import io.github.viewerparrots.ViewerParrotsMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One server-chosen reel, its final item, and the world-clock animation interval. */
public record RoulettePayload(UUID spinId, UUID parrotUuid, String viewerName, long startTick,
		int durationTicks, int resultTicks, List<String> itemIds, int prizeIndex,
		int prizeCount, int rarityColor, boolean jackpot) implements CustomPayload {
	public static final int MAX_ITEMS = 96;
	private static final int MAX_NAME_LENGTH = 64;
	private static final int MAX_ITEM_ID_LENGTH = 128;
	public static final Id<RoulettePayload> ID = new Id<>(ViewerParrotsMod.id("roulette"));
	public static final PacketCodec<RegistryByteBuf, RoulettePayload> CODEC = PacketCodec.of(RoulettePayload::write, RoulettePayload::read);

	public RoulettePayload {
		Objects.requireNonNull(spinId);
		Objects.requireNonNull(parrotUuid);
		Objects.requireNonNull(viewerName);
		itemIds = List.copyOf(itemIds);
	}

	public static void register() { PayloadTypeRegistry.playS2C().register(ID, CODEC); }
	@Override public Id<RoulettePayload> getId() { return ID; }

	public static RoulettePayload cancel(UUID spinId, UUID parrotUuid) {
		return new RoulettePayload(spinId, parrotUuid, "", 0, 0, 0, List.of(), 0, 0, 0, false);
	}

	public boolean isCancellation() { return durationTicks == 0 && itemIds.isEmpty(); }

	public boolean isValid() {
		if (isCancellation()) return true;
		if (viewerName.isBlank() || viewerName.length() > MAX_NAME_LENGTH || startTick < 0
				|| durationTicks < 1 || durationTicks > 1200 || resultTicks < 0 || resultTicks > 600
				|| itemIds.isEmpty() || itemIds.size() > MAX_ITEMS || prizeIndex < 0 || prizeIndex >= itemIds.size()
				|| prizeCount < 1 || prizeCount > 64) return false;
		for (String itemId : itemIds) {
			if (itemId.length() > MAX_ITEM_ID_LENGTH || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return false;
		}
		return true;
	}

	public static void send(ServerPlayerEntity player, RoulettePayload payload) {
		if (!payload.isValid()) throw new IllegalArgumentException("Invalid roulette reel");
		if (ServerPlayNetworking.canSend(player, ID)) ServerPlayNetworking.send(player, payload);
	}

	private static RoulettePayload read(RegistryByteBuf buffer) {
		UUID spinId = buffer.readUuid();
		UUID parrotUuid = buffer.readUuid();
		String viewerName = buffer.readString(MAX_NAME_LENGTH);
		long startTick = buffer.readLong();
		int durationTicks = buffer.readVarInt();
		int resultTicks = buffer.readVarInt();
		int size = buffer.readVarInt();
		if (size < 0 || size > MAX_ITEMS) throw new IllegalArgumentException("Roulette reel exceeds its item bound");
		List<String> items = new ArrayList<>(size);
		for (int index = 0; index < size; index++) items.add(buffer.readString(MAX_ITEM_ID_LENGTH));
		return new RoulettePayload(spinId, parrotUuid, viewerName, startTick, durationTicks, resultTicks,
				items, buffer.readVarInt(), buffer.readVarInt(), buffer.readInt(), buffer.readBoolean());
	}

	private void write(RegistryByteBuf buffer) {
		if (!isValid()) throw new IllegalArgumentException("Invalid roulette reel");
		buffer.writeUuid(spinId);
		buffer.writeUuid(parrotUuid);
		buffer.writeString(viewerName, MAX_NAME_LENGTH);
		buffer.writeLong(startTick);
		buffer.writeVarInt(durationTicks);
		buffer.writeVarInt(resultTicks);
		buffer.writeVarInt(itemIds.size());
		for (String itemId : itemIds) buffer.writeString(itemId, MAX_ITEM_ID_LENGTH);
		buffer.writeVarInt(prizeIndex);
		buffer.writeVarInt(prizeCount);
		buffer.writeInt(rarityColor);
		buffer.writeBoolean(jackpot);
	}
}
