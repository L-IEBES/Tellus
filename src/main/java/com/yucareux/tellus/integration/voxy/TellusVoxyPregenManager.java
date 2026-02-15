package com.yucareux.tellus.integration.voxy;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

public final class TellusVoxyPregenManager {
	private static final long TICK_NANOS = 50_000_000L;
	private static final long SOFT_OVERLOAD_TICK_NANOS = 55_000_000L;
	private static final long HARD_OVERLOAD_TICK_NANOS = 70_000_000L;
	private static final int MAX_QUEUE_SIZE = 50_000;
	private static final int IN_FLIGHT_MULTIPLIER = 4;
	private static final int ABSOLUTE_MAX_IN_FLIGHT = 256;
	private static final int COMPLETED_CACHE_MAX = 200_000;
	private static final int TELEPORT_REPRIORITIZE_DISTANCE_CHUNKS = 64;
	private static final int MOVEMENT_REPRIORITIZE_DISTANCE_CHUNKS = 16;
	private static final int REPRIORITIZE_IN_FLIGHT_KEEP_RADIUS_CHUNKS = 16;
	private static final int REPRIORITIZE_RADIUS_PADDING_CHUNKS = 8;
	private static final int PRIORITY_RADIUS_CHUNKS = 2;
	private static final int STALE_QUEUE_PRUNE_TRIGGER = 10_000;

	private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
	private final LongOpenHashSet queued = new LongOpenHashSet();
	private final LongArrayFIFOQueue priorityQueue = new LongArrayFIFOQueue();
	private final LongOpenHashSet priorityQueued = new LongOpenHashSet();
	private final LongLinkedOpenHashSet completedChunks = new LongLinkedOpenHashSet();
	private final Map<UUID, PlayerPregenState> playerStates = new HashMap<>();
	private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

	private volatile @Nullable Boolean enabledOverride;
	private volatile @Nullable Integer maxRadiusOverride;
	private volatile @Nullable Integer chunksPerTickOverride;

	private volatile int lastConfiguredVoxyRadiusChunks;
	private volatile int lastEffectiveRadiusChunks;

	public void onServerTick(MinecraftServer server) {
		ServerLevel level = server.getLevel(Level.OVERWORLD);
		if (level == null) {
			clearQueueAndPlayerState();
			lastConfiguredVoxyRadiusChunks = 0;
			lastEffectiveRadiusChunks = 0;
			return;
		}

		if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator)) {
			clearQueueAndPlayerState();
			lastConfiguredVoxyRadiusChunks = 0;
			lastEffectiveRadiusChunks = 0;
			return;
		}

		EarthGeneratorSettings settings = earthGenerator.settings();
		if (!effectiveEnabled(settings)) {
			clearQueueAndPlayerState();
			lastConfiguredVoxyRadiusChunks = 0;
			lastEffectiveRadiusChunks = 0;
			return;
		}

		if (!VoxyBridge.isVoxyLoaded()) {
			clearQueueAndPlayerState();
			lastConfiguredVoxyRadiusChunks = 0;
			lastEffectiveRadiusChunks = 0;
			return;
		}

		int configuredVoxyRadius = Math.max(0, VoxyBridge.configuredChunkRadius());
		lastConfiguredVoxyRadiusChunks = configuredVoxyRadius;

		int targetRadius = Math.min(configuredVoxyRadius, effectiveMaxRadius(settings));
		lastEffectiveRadiusChunks = targetRadius;
		if (targetRadius <= 0) {
			clearQueueAndPlayerState();
			return;
		}

		List<ServerPlayer> players = overworldPlayers(server, level);
		if (players.isEmpty()) {
			clearQueueAndPlayerState();
			return;
		}

		boolean movedPlayers = enqueueAroundPlayers(players, targetRadius);
		if (movedPlayers) {
			refreshPriorityQueue(players, targetRadius);
			if (queue.size() >= STALE_QUEUE_PRUNE_TRIGGER) {
				pruneNormalQueue(players, targetRadius + REPRIORITIZE_RADIUS_PADDING_CHUNKS);
			}
		}
		long averageTickNanos = server.getAverageTickTimeNanos();
		processQueue(server, level.getChunkSource(), effectiveChunksPerTick(settings), averageTickNanos);
	}

	public void shutdown() {
		clearQueueAndPlayerState();
		inFlight.clear();
		lastConfiguredVoxyRadiusChunks = 0;
		lastEffectiveRadiusChunks = 0;
	}

	public void clearOverrides() {
		enabledOverride = null;
		maxRadiusOverride = null;
		chunksPerTickOverride = null;
	}

	public void setEnabledOverride(@Nullable Boolean enabled) {
		enabledOverride = enabled;
	}

	public void setMaxRadiusOverride(@Nullable Integer maxRadius) {
		maxRadiusOverride = maxRadius;
	}

	public void setChunksPerTickOverride(@Nullable Integer chunksPerTick) {
		chunksPerTickOverride = chunksPerTick;
	}

	public boolean effectiveEnabled(EarthGeneratorSettings settings) {
		Boolean override = enabledOverride;
		return override != null ? override.booleanValue() : settings.voxyChunkPregenEnabled();
	}

	public int effectiveMaxRadius(EarthGeneratorSettings settings) {
		Integer override = maxRadiusOverride;
		return Math.max(0, override != null ? override.intValue() : settings.voxyChunkPregenMaxRadius());
	}

	public int effectiveChunksPerTick(EarthGeneratorSettings settings) {
		Integer override = chunksPerTickOverride;
		return Math.max(1, override != null ? override.intValue() : settings.voxyChunkPregenChunksPerTick());
	}

	public @Nullable Boolean enabledOverride() {
		return enabledOverride;
	}

	public @Nullable Integer maxRadiusOverride() {
		return maxRadiusOverride;
	}

	public @Nullable Integer chunksPerTickOverride() {
		return chunksPerTickOverride;
	}

	public int queuedChunkCount() {
		return queue.size() + priorityQueue.size();
	}

	public int inFlightChunkCount() {
		return inFlight.size();
	}

	public int lastConfiguredVoxyRadiusChunks() {
		return lastConfiguredVoxyRadiusChunks;
	}

	public int lastEffectiveRadiusChunks() {
		return lastEffectiveRadiusChunks;
	}

	private static List<ServerPlayer> overworldPlayers(MinecraftServer server, ServerLevel level) {
		List<ServerPlayer> players = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() == level) {
				players.add(player);
			}
		}
		return players;
	}

	private boolean enqueueAroundPlayers(List<ServerPlayer> players, int radius) {
		Set<UUID> active = new HashSet<>();
		boolean reprioritize = false;
		boolean movedPlayers = false;
		for (ServerPlayer player : players) {
			active.add(player.getUUID());
			PlayerPregenState state = playerStates.computeIfAbsent(player.getUUID(), ignored -> new PlayerPregenState());
			ChunkPos center = player.chunkPosition();
			if (state.centerX != center.x || state.centerZ != center.z) {
				movedPlayers = true;
				if (state.centerX == Integer.MIN_VALUE || state.centerZ == Integer.MIN_VALUE) {
					state.centerX = center.x;
					state.centerZ = center.z;
					state.anchorX = center.x;
					state.anchorZ = center.z;
					state.nextRing = 0;
					continue;
				}
				int jumpDistance = chebyshevChunkDistance(state.centerX, state.centerZ, center.x, center.z);
				boolean requiresReprioritize = jumpDistance >= TELEPORT_REPRIORITIZE_DISTANCE_CHUNKS;
				if (!requiresReprioritize) {
					if (state.anchorX == Integer.MIN_VALUE || state.anchorZ == Integer.MIN_VALUE) {
						state.anchorX = state.centerX;
						state.anchorZ = state.centerZ;
					}
					int driftFromAnchor = chebyshevChunkDistance(state.anchorX, state.anchorZ, center.x, center.z);
					requiresReprioritize = driftFromAnchor >= MOVEMENT_REPRIORITIZE_DISTANCE_CHUNKS;
				}
				if (requiresReprioritize) {
					reprioritize = true;
					state.nextRing = 0;
					state.anchorX = center.x;
					state.anchorZ = center.z;
				} else {
					// Preserve outward progress when moving slowly across chunks.
					state.nextRing = Math.max(0, state.nextRing - jumpDistance);
				}
				state.centerX = center.x;
				state.centerZ = center.z;
			}
		}

		playerStates.entrySet().removeIf(entry -> !active.contains(entry.getKey()));

		if (reprioritize) {
			reprioritizeForCurrentPlayers(players, radius);
		}

		for (ServerPlayer player : players) {
			PlayerPregenState state = playerStates.get(player.getUUID());
			if (state == null) {
				continue;
			}
			if (state.nextRing > radius || queue.size() >= MAX_QUEUE_SIZE) {
				continue;
			}
			enqueueRing(state.centerX, state.centerZ, state.nextRing);
			state.nextRing++;
		}
		return movedPlayers;
	}

	private void reprioritizeForCurrentPlayers(List<ServerPlayer> players, int radius) {
		// Movement-based recentering should behave like a soft reset around the current location.
		queue.clear();
		queued.clear();
		int keepRadius = Math.min(
				radius + REPRIORITIZE_RADIUS_PADDING_CHUNKS,
				REPRIORITIZE_IN_FLIGHT_KEEP_RADIUS_CHUNKS
		);
		releaseFarInFlight(players, keepRadius);
		priorityQueue.clear();
		priorityQueued.clear();

		for (ServerPlayer player : players) {
			ChunkPos pos = player.chunkPosition();
			enqueueChunk(pos.x, pos.z);
			enqueuePriorityChunk(pos.x, pos.z);
		}
	}

	private void refreshPriorityQueue(List<ServerPlayer> players, int radius) {
		int localRadius = Math.min(PRIORITY_RADIUS_CHUNKS, radius);
		prunePriorityQueue(players, localRadius + 2);
		for (ServerPlayer player : players) {
			ChunkPos center = player.chunkPosition();
			for (int ring = 0; ring <= localRadius; ring++) {
				enqueuePriorityRing(center.x, center.z, ring);
			}
		}
	}

	private void prunePriorityQueue(List<ServerPlayer> players, int keepRadius) {
		if (priorityQueue.isEmpty()) {
			return;
		}
		int size = priorityQueue.size();
		LongArrayFIFOQueue retained = new LongArrayFIFOQueue(size);
		priorityQueued.clear();
		for (int i = 0; i < size; i++) {
			long key = priorityQueue.dequeueLong();
			if (!isNearAnyPlayer(players, key, keepRadius)) {
				continue;
			}
			if (priorityQueued.add(key)) {
				retained.enqueue(key);
			}
		}
		priorityQueue.clear();
		while (!retained.isEmpty()) {
			priorityQueue.enqueue(retained.dequeueLong());
		}
	}

	private void pruneNormalQueue(List<ServerPlayer> players, int keepRadius) {
		if (queue.isEmpty()) {
			return;
		}
		int size = queue.size();
		LongArrayFIFOQueue retained = new LongArrayFIFOQueue(size);
		for (int i = 0; i < size; i++) {
			long key = queue.dequeueLong();
			if (!queued.contains(key)) {
				continue;
			}
			if (!isNearAnyPlayer(players, key, keepRadius)) {
				queued.remove(key);
				continue;
			}
			retained.enqueue(key);
		}
		queue.clear();
		while (!retained.isEmpty()) {
			long key = retained.dequeueLong();
			if (queued.contains(key)) {
				queue.enqueue(key);
			}
		}
	}

	private void releaseFarInFlight(List<ServerPlayer> players, int keepRadius) {
		// Requests already launched for far-away chunks should not block new local launches.
		for (long key : inFlight) {
			if (!isNearAnyPlayer(players, key, keepRadius)) {
				inFlight.remove(key);
			}
		}
	}

	private static boolean isNearAnyPlayer(List<ServerPlayer> players, long chunkKey, int radius) {
		int chunkX = ChunkPos.getX(chunkKey);
		int chunkZ = ChunkPos.getZ(chunkKey);
		for (ServerPlayer player : players) {
			ChunkPos center = player.chunkPosition();
			if (chebyshevChunkDistance(center.x, center.z, chunkX, chunkZ) <= radius) {
				return true;
			}
		}
		return false;
	}

	private static int chebyshevChunkDistance(int xA, int zA, int xB, int zB) {
		long dx = Math.abs((long) xA - xB);
		long dz = Math.abs((long) zA - zB);
		return (int) Math.min(Integer.MAX_VALUE, Math.max(dx, dz));
	}

	private void enqueueRing(int centerX, int centerZ, int ring) {
		if (ring <= 0) {
			enqueueChunk(centerX, centerZ);
			return;
		}
		int minX = centerX - ring;
		int maxX = centerX + ring;
		int minZ = centerZ - ring;
		int maxZ = centerZ + ring;

		for (int x = minX; x <= maxX; x++) {
			enqueueChunk(x, minZ);
			if (maxZ != minZ) {
				enqueueChunk(x, maxZ);
			}
		}
		for (int z = minZ + 1; z < maxZ; z++) {
			enqueueChunk(minX, z);
			if (maxX != minX) {
				enqueueChunk(maxX, z);
			}
		}
	}

	private void enqueueChunk(int chunkX, int chunkZ) {
		long key = ChunkPos.asLong(chunkX, chunkZ);
		if (completedChunks.contains(key)) {
			return;
		}
		if (queued.add(key)) {
			queue.enqueue(key);
		}
	}

	private void enqueuePriorityRing(int centerX, int centerZ, int ring) {
		if (ring <= 0) {
			enqueuePriorityChunk(centerX, centerZ);
			return;
		}
		int minX = centerX - ring;
		int maxX = centerX + ring;
		int minZ = centerZ - ring;
		int maxZ = centerZ + ring;

		for (int x = minX; x <= maxX; x++) {
			enqueuePriorityChunk(x, minZ);
			if (maxZ != minZ) {
				enqueuePriorityChunk(x, maxZ);
			}
		}
		for (int z = minZ + 1; z < maxZ; z++) {
			enqueuePriorityChunk(minX, z);
			if (maxX != minX) {
				enqueuePriorityChunk(maxX, z);
			}
		}
	}

	private void enqueuePriorityChunk(int chunkX, int chunkZ) {
		long key = ChunkPos.asLong(chunkX, chunkZ);
		if (completedChunks.contains(key)) {
			return;
		}
		if (priorityQueued.add(key)) {
			priorityQueue.enqueue(key);
		}
	}

	private void processQueue(
			MinecraftServer server,
			ServerChunkCache source,
			int chunksPerTick,
			long averageTickNanos
	) {
		int launchBudget = effectiveLaunchBudget(chunksPerTick, averageTickNanos);
		if (launchBudget <= 0) {
			return;
		}
		int maxInFlight = Math.min(
				ABSOLUTE_MAX_IN_FLIGHT,
				Math.max(launchBudget + 1, launchBudget * IN_FLIGHT_MULTIPLIER)
		);

		int launched = 0;
		while (launched < launchBudget) {
			if (inFlight.size() >= maxInFlight) {
				return;
			}
			Long polled = pollNextChunk();
			if (polled == null) {
				return;
			}
			long key = polled.longValue();
			if (inFlight.contains(key)) {
				continue;
			}
			if (!inFlight.add(key)) {
				continue;
			}

			final long chunkKey = key;
			int chunkX = ChunkPos.getX(chunkKey);
			int chunkZ = ChunkPos.getZ(chunkKey);

			source.getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true)
					.whenComplete((result, error) -> {
						inFlight.remove(chunkKey);
						if (error != null || result == null || !result.isSuccess()) {
							return;
						}
						result.ifSuccess(chunk -> ingestIfLevelChunk(server, chunk, chunkKey));
					});
			launched++;
		}
	}

	private int effectiveLaunchBudget(int chunksPerTick, long averageTickNanos) {
		int base = Math.max(1, chunksPerTick);
		if (averageTickNanos >= HARD_OVERLOAD_TICK_NANOS) {
			// Let the server recover when it is heavily behind.
			return 0;
		}
		if (averageTickNanos >= SOFT_OVERLOAD_TICK_NANOS) {
			return 1;
		}
		if (averageTickNanos >= TICK_NANOS) {
			return Math.max(1, base / 2);
		}
		// If the server is healthy, opportunistically use a bit more throughput.
		if (averageTickNanos < TICK_NANOS - 10_000_000L) {
			return Math.min(base + Math.max(1, base / 2), 256);
		}
		return base;
	}

	private @Nullable Long pollNextChunk() {
		while (!priorityQueue.isEmpty()) {
			long key = priorityQueue.dequeueLong();
			priorityQueued.remove(key);
			// If the key is also in the normal queue, drop that later duplicate.
			queued.remove(key);
			return Long.valueOf(key);
		}
		while (!queue.isEmpty()) {
			long key = queue.dequeueLong();
			if (!queued.remove(key)) {
				continue;
			}
			return Long.valueOf(key);
		}
		return null;
	}

	private void ingestIfLevelChunk(MinecraftServer server, ChunkAccess chunk, long chunkKey) {
		if (chunk instanceof LevelChunk levelChunk) {
			recordCompletedChunk(chunkKey);
			VoxyBridge.tryIngestChunkAsync(server, levelChunk);
		}
	}

	private void recordCompletedChunk(long chunkKey) {
		if (completedChunks.addAndMoveToLast(chunkKey) && completedChunks.size() > COMPLETED_CACHE_MAX) {
			completedChunks.removeFirstLong();
		}
	}

	private void clearQueueAndPlayerState() {
		queue.clear();
		queued.clear();
		priorityQueue.clear();
		priorityQueued.clear();
		playerStates.clear();
	}

	private static final class PlayerPregenState {
		private int centerX = Integer.MIN_VALUE;
		private int centerZ = Integer.MIN_VALUE;
		private int anchorX = Integer.MIN_VALUE;
		private int anchorZ = Integer.MIN_VALUE;
		private int nextRing;
	}
}
