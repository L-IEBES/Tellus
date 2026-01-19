package com.yucareux.tellus.worldgen.geology;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.StructureCarveMask;
import com.yucareux.tellus.worldgen.WaterSurfaceResolver;
import java.util.ArrayDeque;
import java.util.Objects;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class TellusGeologyGenerator {
	private static final int MIN_CAVE_ROOF = 4;
	private static final int SURFACE_CAVE_BUFFER = 6;
	private static final int SURFACE_ENTRANCE_BUFFER = 0;

	private static final double ENTRANCE_FREQ = 0.008;
	private static final double ENTRANCE_THRESHOLD = 0.65;
	private static final int WORM_REGION_SIZE = 4;
	private static final int WORM_WALKERS_PER_CHUNK = 2;
	private static final int WORM_MIN_STEPS = 48;
	private static final int WORM_MAX_STEPS = 160;
	private static final int WORM_MIN_BRANCH_STEPS = 24;
	private static final int WORM_MAX_BRANCHES = 2;
	private static final float WORM_BRANCH_CHANCE = 0.02f;
	private static final double WORM_STEP_SIZE = 1.1;
	private static final double WORM_MIN_RADIUS = 1.4;
	private static final double WORM_MAX_RADIUS = 3.4;
	private static final double WORM_RADIUS_JITTER = 0.2;
	private static final float WORM_YAW_JITTER = 0.35f;
	private static final float WORM_PITCH_JITTER = 0.12f;
	private static final float WORM_MAX_PITCH = 0.5f;
	private static final float WORM_BRANCH_YAW = 0.9f;
	private static final float WORM_BRANCH_PITCH = 0.25f;
	private static final double WORM_MIN_Y_BUFFER = 2.0;
	private static final double WORM_SURFACE_BUFFER = 24.0;
	private static final int CHEESE_REGION_SIZE = 8;
	private static final float CHEESE_CHANCE = 0.12f;
	private static final int CHEESE_MAX_PER_REGION = 2;
	private static final double CHEESE_MIN_RADIUS = 6.0;
	private static final double CHEESE_MAX_RADIUS = 14.0;
	private static final double CHEESE_BLOB_OFFSET = 5.0;
	private static final int CHEESE_BLOB_MIN = 2;
	private static final int CHEESE_BLOB_MAX = 5;
	private static final double CHEESE_MIN_Y_BUFFER = 6.0;
	private static final double CHEESE_TOP_BUFFER = 10.0;

	private static final double FULL_DETAIL_SCALE_MAX = 5.0;
	private static final double MAJOR_DETAIL_SCALE_MAX = 50.0;
	private static final double REGIONAL_DETAIL_SCALE_MAX = 500.0;
	private final boolean cavesEnabled;
	private final int minCaveRoof;
	private final int surfaceCaveBuffer;
	private final int surfaceEntranceBuffer;
	private final double entranceFreq;
	private final int wormWalkersPerChunk;
	private final int wormMinSteps;
	private final int wormMaxSteps;
	private final int wormMinBranchSteps;
	private final int wormMaxBranches;
	private final float wormBranchChance;
	private final double wormStepSize;
	private final double wormMinRadius;
	private final double wormMaxRadius;
	private final double wormRadiusJitter;
	private final float wormYawJitter;
	private final float wormPitchJitter;
	private final float wormMaxPitch;
	private final float wormBranchYaw;
	private final float wormBranchPitch;
	private final int wormMinY;
	private final int wormMaxY;
	private final float cheeseChance;
	private final int cheeseMaxPerRegion;
	private final double cheeseMinRadius;
	private final double cheeseMaxRadius;
	private final double cheeseBlobOffset;
	private final int cheeseBlobMin;
	private final int cheeseBlobMax;
	private final int cheeseMinY;
	private final int cheeseMaxY;
	private final int minY;
	private final int maxY;
	private final int seaLevel;
	private final long seedSalt;
	private final NormalNoise entranceNoise;

	public TellusGeologyGenerator(EarthGeneratorSettings settings, int minY, int height, int seaLevel, long seed) {
		this.minY = minY;
		this.maxY = minY + height - 1;
		this.seaLevel = seaLevel;
		double metersPerBlock = resolveMetersPerBlock(settings.worldScale());
		double verticalMetersPerBlock = resolveVerticalMetersPerBlock(settings, metersPerBlock);
		CaveDetail detail = resolveDetail(settings, metersPerBlock);
		this.cavesEnabled = settings.caveGeneration() && detail != CaveDetail.OFF;
		int minRoof = minCaveRoofBlocks(detail);
		this.minCaveRoof = scaleMetersToBlocks(MIN_CAVE_ROOF, verticalMetersPerBlock, minRoof);
		this.surfaceCaveBuffer = scaleMetersToBlocks(SURFACE_CAVE_BUFFER, verticalMetersPerBlock, Math.max(4, minRoof));
		this.surfaceEntranceBuffer = scaleMetersToBlocks(SURFACE_ENTRANCE_BUFFER, verticalMetersPerBlock, 0);
		double maxFreq = resolveMaxFrequency(detail);
		this.entranceFreq = scaleFrequency(ENTRANCE_FREQ, metersPerBlock, maxFreq);
		double walkerScale = switch (detail) {
			case FULL -> 1.0;
			case MAJOR -> 0.8;
			case REGIONAL -> 0.6;
			case OFF -> 0.0;
		};
		this.wormWalkersPerChunk = Math.max(1, (int) Math.round(WORM_WALKERS_PER_CHUNK * walkerScale));
		this.wormMinSteps = Math.max(8, (int) Math.round(WORM_MIN_STEPS * walkerScale));
		this.wormMaxSteps = Math.max(this.wormMinSteps + 8, (int) Math.round(WORM_MAX_STEPS * walkerScale));
		this.wormMinBranchSteps = Math.max(8, (int) Math.round(WORM_MIN_BRANCH_STEPS * walkerScale));
		this.wormMaxBranches = WORM_MAX_BRANCHES;
		this.wormBranchChance = WORM_BRANCH_CHANCE;
		this.wormStepSize = scaleMetersToBlocks(WORM_STEP_SIZE, metersPerBlock, 0.8);
		this.wormMinRadius = scaleMetersToBlocks(WORM_MIN_RADIUS, metersPerBlock, 1.0);
		this.wormMaxRadius = scaleMetersToBlocks(WORM_MAX_RADIUS, metersPerBlock, 2.5);
		this.wormRadiusJitter = scaleMetersToBlocks(WORM_RADIUS_JITTER, metersPerBlock, 0.05);
		this.wormYawJitter = WORM_YAW_JITTER;
		this.wormPitchJitter = WORM_PITCH_JITTER;
		this.wormMaxPitch = WORM_MAX_PITCH;
		this.wormBranchYaw = WORM_BRANCH_YAW;
		this.wormBranchPitch = WORM_BRANCH_PITCH;
		int wormMinYBuffer = scaleMetersToBlocks(WORM_MIN_Y_BUFFER, verticalMetersPerBlock, 1);
		int wormSurfaceBuffer = scaleMetersToBlocks(WORM_SURFACE_BUFFER, verticalMetersPerBlock, 8);
		this.wormMinY = this.minY + wormMinYBuffer;
		int maxWormY = Math.min(this.maxY - 1, this.seaLevel + wormSurfaceBuffer);
		this.wormMaxY = Math.max(this.wormMinY, maxWormY);
		double cheeseChance = CHEESE_CHANCE * walkerScale;
		this.cheeseChance = (float) Mth.clamp(cheeseChance, 0.0, 1.0);
		this.cheeseMaxPerRegion = Math.max(1, CHEESE_MAX_PER_REGION);
		double minCheeseRadius = scaleMetersToBlocks(CHEESE_MIN_RADIUS, metersPerBlock, 4.0);
		double maxCheeseRadius = scaleMetersToBlocks(CHEESE_MAX_RADIUS, metersPerBlock, minCheeseRadius + 2.0);
		this.cheeseMinRadius = minCheeseRadius;
		this.cheeseMaxRadius = Math.max(minCheeseRadius + 1.0, maxCheeseRadius);
		this.cheeseBlobOffset = scaleMetersToBlocks(CHEESE_BLOB_OFFSET, metersPerBlock, 2.0);
		this.cheeseBlobMin = Math.max(1, CHEESE_BLOB_MIN);
		this.cheeseBlobMax = Math.max(this.cheeseBlobMin, CHEESE_BLOB_MAX);
		int cheeseMinYBuffer = scaleMetersToBlocks(CHEESE_MIN_Y_BUFFER, verticalMetersPerBlock, 2);
		int cheeseTopBuffer = scaleMetersToBlocks(CHEESE_TOP_BUFFER, verticalMetersPerBlock, 4);
		this.cheeseMinY = Math.max(this.minY + 1, this.minY + cheeseMinYBuffer);
		int maxCheeseY = Math.min(this.maxY - 1, this.wormMaxY - cheeseTopBuffer);
		this.cheeseMaxY = Math.max(this.cheeseMinY, maxCheeseY);
		this.seedSalt = seed ^ 0x6F1D5E3A2B9C4D1EL;
		this.entranceNoise = createNoise(seed ^ 0x3E5A7C91B2D4F618L, -2, 1.0, 0.5);
	}

	public boolean shouldRun() {
		return this.cavesEnabled;
	}

	public void carveChunk(
			ChunkAccess chunk,
			WaterSurfaceResolver.WaterChunkData waterData,
			@Nullable StructureCarveMask structureMask
	) {
		if (!shouldRun()) {
			return;
		}
		boolean canWorm = this.wormMaxY > this.wormMinY;
		boolean canCheese = this.cheeseChance > 0.0f && this.cheeseMaxY > this.cheeseMinY && this.cheeseMaxPerRegion > 0;
		if (!canWorm && !canCheese) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		int chunkMinY = chunk.getMinY();
		int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
		int chunkMinX = pos.getMinBlockX();
		int chunkMinZ = pos.getMinBlockZ();
		int chunkMaxX = chunkMinX + 15;
		int chunkMaxZ = chunkMinZ + 15;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		BlockState air = Blocks.AIR.defaultBlockState();
		int[] surfaceHeights = new int[16 * 16];
		boolean[] entranceFlags = new boolean[16 * 16];
		for (int localX = 0; localX < 16; localX++) {
			int worldX = chunkMinX + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int worldZ = chunkMinZ + localZ;
				int idx = localZ * 16 + localX;
				int surface = Mth.clamp(waterData.terrainSurface(localX, localZ), chunkMinY, chunkMaxY);
				surfaceHeights[idx] = surface;
				boolean hasWater = waterData.hasWater(localX, localZ);
				entranceFlags[idx] = !hasWater && allowSurfaceEntrances(worldX, worldZ);
			}
		}

		if (canCheese) {
			carveCheeseCaves(
					chunk,
					cursor,
					structureMask,
					air,
					chunkMinX,
					chunkMinZ,
					chunkMaxX,
					chunkMaxZ,
					chunkMinY,
					chunkMaxY,
					surfaceHeights,
					pos
			);
		}
		if (!canWorm) {
			return;
		}

		int regionX = Math.floorDiv(pos.x, WORM_REGION_SIZE);
		int regionZ = Math.floorDiv(pos.z, WORM_REGION_SIZE);
		int regionSizeBlocks = WORM_REGION_SIZE * 16;
		int regionMinX = regionX * regionSizeBlocks;
		int regionMinZ = regionZ * regionSizeBlocks;
		long regionSeed = seedFromCoords(regionX, 0, regionZ) ^ this.seedSalt ^ 0x4C3B2A1908F7E6D5L;
		RandomSource random = RandomSource.create(regionSeed);

		int walkers = this.wormWalkersPerChunk * WORM_REGION_SIZE * WORM_REGION_SIZE;
		ArrayDeque<WormWalker> queue = new ArrayDeque<>(walkers);
		for (int i = 0; i < walkers; i++) {
			double startX = regionMinX + random.nextInt(regionSizeBlocks) + random.nextDouble();
			double startZ = regionMinZ + random.nextInt(regionSizeBlocks) + random.nextDouble();
			double startY = pickWormStartY(random);
			int steps = randomSteps(random);
			double radius = randomRadius(random);
			float yaw = (float) (random.nextDouble() * Math.PI * 2.0);
			float pitch = (random.nextFloat() * 2.0f - 1.0f) * (this.wormMaxPitch * 0.35f);
			queue.add(new WormWalker(startX, startY, startZ, yaw, pitch, radius, steps, 0));
		}

		while (!queue.isEmpty()) {
			WormWalker worm = queue.removeLast();
			for (int step = 0; step < worm.steps; step++) {
				carveSphere(
						chunk,
						cursor,
						structureMask,
						air,
						chunkMinX,
						chunkMinZ,
						chunkMaxX,
						chunkMaxZ,
						chunkMinY,
						chunkMaxY,
						surfaceHeights,
						entranceFlags,
						worm.x,
						worm.y,
						worm.z,
						worm.radius
				);
				if (worm.branches < this.wormMaxBranches
						&& random.nextFloat() < this.wormBranchChance
						&& (worm.steps - step) > this.wormMinBranchSteps) {
					int remaining = worm.steps - step;
					int branchSteps = Math.max(
							this.wormMinBranchSteps,
							(int) Math.round(remaining * (0.4 + random.nextDouble() * 0.3))
					);
					float branchYaw = worm.yaw + (random.nextFloat() * 2.0f - 1.0f) * this.wormBranchYaw;
					float branchPitch = worm.pitch + (random.nextFloat() * 2.0f - 1.0f) * this.wormBranchPitch;
					branchPitch = Mth.clamp(branchPitch, -this.wormMaxPitch, this.wormMaxPitch);
					double branchRadius = Mth.clamp(
							worm.radius * (0.85 + random.nextDouble() * 0.2),
							this.wormMinRadius,
							this.wormMaxRadius
					);
					queue.add(new WormWalker(worm.x, worm.y, worm.z, branchYaw, branchPitch, branchRadius, branchSteps, worm.branches + 1));
					worm.branches++;
				}

				worm.radius = Mth.clamp(
						worm.radius + (random.nextDouble() * 2.0 - 1.0) * this.wormRadiusJitter,
						this.wormMinRadius,
						this.wormMaxRadius
				);
				worm.yaw += (random.nextFloat() * 2.0f - 1.0f) * this.wormYawJitter;
				worm.pitch = Mth.clamp(
						worm.pitch + (random.nextFloat() * 2.0f - 1.0f) * this.wormPitchJitter,
						-this.wormMaxPitch,
						this.wormMaxPitch
				);
				float cosPitch = Mth.cos(worm.pitch);
				worm.x += Mth.cos(worm.yaw) * cosPitch * this.wormStepSize;
				worm.y += Mth.sin(worm.pitch) * this.wormStepSize;
				worm.z += Mth.sin(worm.yaw) * cosPitch * this.wormStepSize;

				if (worm.y < this.wormMinY || worm.y > this.wormMaxY) {
					worm.pitch = -worm.pitch * 0.6f;
					worm.y = Mth.clamp(worm.y, (double) this.wormMinY, (double) this.wormMaxY);
				}
			}
		}
	}

	private double pickWormStartY(RandomSource random) {
		if (this.wormMaxY <= this.wormMinY) {
			return this.wormMinY;
		}
		double t = random.nextDouble();
		return this.wormMinY + t * (this.wormMaxY - this.wormMinY);
	}

	private int randomSteps(RandomSource random) {
		if (this.wormMaxSteps <= this.wormMinSteps) {
			return this.wormMinSteps;
		}
		return this.wormMinSteps + random.nextInt(this.wormMaxSteps - this.wormMinSteps + 1);
	}

	private double randomRadius(RandomSource random) {
		if (this.wormMaxRadius <= this.wormMinRadius) {
			return this.wormMinRadius;
		}
		return this.wormMinRadius + random.nextDouble() * (this.wormMaxRadius - this.wormMinRadius);
	}

	private double pickCheeseCenterY(RandomSource random) {
		if (this.cheeseMaxY <= this.cheeseMinY) {
			return this.cheeseMinY;
		}
		double t = random.nextDouble();
		return this.cheeseMinY + t * (this.cheeseMaxY - this.cheeseMinY);
	}

	private double randomCheeseRadius(RandomSource random) {
		if (this.cheeseMaxRadius <= this.cheeseMinRadius) {
			return this.cheeseMinRadius;
		}
		return this.cheeseMinRadius + random.nextDouble() * (this.cheeseMaxRadius - this.cheeseMinRadius);
	}

	private void carveCheeseCaves(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			@Nullable StructureCarveMask structureMask,
			@NonNull BlockState air,
			int chunkMinX,
			int chunkMinZ,
			int chunkMaxX,
			int chunkMaxZ,
			int chunkMinY,
			int chunkMaxY,
			int[] surfaceHeights,
			ChunkPos pos
	) {
		if (this.cheeseChance <= 0.0f || this.cheeseMaxPerRegion <= 0 || this.cheeseMaxY <= this.cheeseMinY) {
			return;
		}
		int regionX = Math.floorDiv(pos.x, CHEESE_REGION_SIZE);
		int regionZ = Math.floorDiv(pos.z, CHEESE_REGION_SIZE);
		int regionSizeBlocks = CHEESE_REGION_SIZE * 16;
		int regionMinX = regionX * regionSizeBlocks;
		int regionMinZ = regionZ * regionSizeBlocks;
		long regionSeed = seedFromCoords(regionX, 0, regionZ) ^ this.seedSalt ^ 0x7A8B9C0D1E2F3041L;
		RandomSource random = RandomSource.create(regionSeed);
		int blobRange = Math.max(0, this.cheeseBlobMax - this.cheeseBlobMin);

		for (int i = 0; i < this.cheeseMaxPerRegion; i++) {
			if (random.nextFloat() > this.cheeseChance) {
				continue;
			}
			double radius = randomCheeseRadius(random);
			double margin = radius + this.cheeseBlobOffset + 2.0;
			if (margin * 2.0 >= regionSizeBlocks) {
				margin = Math.max(1.0, regionSizeBlocks * 0.25);
			}
			double centerX = regionMinX + margin + random.nextDouble() * (regionSizeBlocks - margin * 2.0);
			double centerZ = regionMinZ + margin + random.nextDouble() * (regionSizeBlocks - margin * 2.0);
			double centerY = pickCheeseCenterY(random);
			double influenceRadius = radius + this.cheeseBlobOffset + radius * 0.9;
			if (!sphereIntersectsChunk(
					centerX,
					centerY,
					centerZ,
					influenceRadius,
					chunkMinX,
					chunkMinZ,
					chunkMaxX,
					chunkMaxZ,
					chunkMinY,
					chunkMaxY
			)) {
				continue;
			}
			if (sphereIntersectsChunk(
					centerX,
					centerY,
					centerZ,
					radius,
					chunkMinX,
					chunkMinZ,
					chunkMaxX,
					chunkMaxZ,
					chunkMinY,
					chunkMaxY
			)) {
				carveCheeseSphere(
						chunk,
						cursor,
						structureMask,
						air,
						chunkMinX,
						chunkMinZ,
						chunkMaxX,
						chunkMaxZ,
						chunkMinY,
						chunkMaxY,
						surfaceHeights,
						centerX,
						centerY,
						centerZ,
						radius
				);
			}
			int blobs = this.cheeseBlobMin + (blobRange > 0 ? random.nextInt(blobRange + 1) : 0);
			for (int blobIndex = 0; blobIndex < blobs; blobIndex++) {
				double blobRadius = radius * (0.55 + random.nextDouble() * 0.35);
				double offset = this.cheeseBlobOffset * (0.6 + random.nextDouble() * 0.8);
				double theta = random.nextDouble() * Math.PI * 2.0;
				double pitch = (random.nextDouble() * 2.0 - 1.0) * 0.6;
				double cosPitch = Math.cos(pitch);
				double blobX = centerX + Math.cos(theta) * cosPitch * offset;
				double blobY = centerY + Math.sin(pitch) * offset;
				double blobZ = centerZ + Math.sin(theta) * cosPitch * offset;
				blobY = Mth.clamp(blobY, (double) this.cheeseMinY, (double) this.cheeseMaxY);
				if (!sphereIntersectsChunk(
						blobX,
						blobY,
						blobZ,
						blobRadius,
						chunkMinX,
						chunkMinZ,
						chunkMaxX,
						chunkMaxZ,
						chunkMinY,
						chunkMaxY
				)) {
					continue;
				}
				carveCheeseSphere(
						chunk,
						cursor,
						structureMask,
						air,
						chunkMinX,
						chunkMinZ,
						chunkMaxX,
						chunkMaxZ,
						chunkMinY,
						chunkMaxY,
						surfaceHeights,
						blobX,
						blobY,
						blobZ,
						blobRadius
				);
			}
		}
	}

	private static boolean sphereIntersectsChunk(
			double centerX,
			double centerY,
			double centerZ,
			double radius,
			int chunkMinX,
			int chunkMinZ,
			int chunkMaxX,
			int chunkMaxZ,
			int chunkMinY,
			int chunkMaxY
	) {
		return !(centerX + radius < chunkMinX
				|| centerX - radius > chunkMaxX
				|| centerZ + radius < chunkMinZ
				|| centerZ - radius > chunkMaxZ
				|| centerY + radius < chunkMinY
				|| centerY - radius > chunkMaxY);
	}

	private void carveCheeseSphere(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			@Nullable StructureCarveMask structureMask,
			@NonNull BlockState air,
			int chunkMinX,
			int chunkMinZ,
			int chunkMaxX,
			int chunkMaxZ,
			int chunkMinY,
			int chunkMaxY,
			int[] surfaceHeights,
			double centerX,
			double centerY,
			double centerZ,
			double radius
	) {
		@NonNull BlockState fill = Objects.requireNonNull(air, "air");
		double radiusSq = radius * radius;
		int minX = Math.max(chunkMinX, Mth.floor(centerX - radius));
		int maxX = Math.min(chunkMaxX, Mth.floor(centerX + radius));
		int minZ = Math.max(chunkMinZ, Mth.floor(centerZ - radius));
		int maxZ = Math.min(chunkMaxZ, Mth.floor(centerZ + radius));
		if (minX > maxX || minZ > maxZ) {
			return;
		}

		for (int x = minX; x <= maxX; x++) {
			double dx = (x + 0.5) - centerX;
			for (int z = minZ; z <= maxZ; z++) {
				double dz = (z + 0.5) - centerZ;
				int localX = x - chunkMinX;
				int localZ = z - chunkMinZ;
				int idx = localZ * 16 + localX;
				int surface = surfaceHeights[idx];
				int surfaceCap = surface - this.minCaveRoof - this.surfaceCaveBuffer;
				int minY = Math.max(chunkMinY + 1, Mth.floor(centerY - radius));
				int maxY = Math.min(chunkMaxY - 1, Mth.floor(centerY + radius));
				if (surfaceCap < minY || minY > maxY) {
					continue;
				}
				for (int y = minY; y <= maxY; y++) {
					if (y > surfaceCap) {
						continue;
					}
					double dy = (y + 0.5) - centerY;
					if (dx * dx + dy * dy + dz * dz > radiusSq) {
						continue;
					}
					if (structureMask != null && structureMask.contains(localX, localZ, y)) {
						continue;
					}
					cursor.set(x, y, z);
					BlockState existing = chunk.getBlockState(cursor);
					if (existing.is(Blocks.BEDROCK)) {
						continue;
					}
					if (!existing.isAir()) {
						chunk.setBlockState(cursor, fill);
					}
				}
			}
		}
	}

	private void carveSphere(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			@Nullable StructureCarveMask structureMask,
			@NonNull BlockState air,
			int chunkMinX,
			int chunkMinZ,
			int chunkMaxX,
			int chunkMaxZ,
			int chunkMinY,
			int chunkMaxY,
			int[] surfaceHeights,
			boolean[] entranceFlags,
			double centerX,
			double centerY,
			double centerZ,
			double radius
	) {
		@NonNull BlockState fill = Objects.requireNonNull(air, "air");
		double radiusSq = radius * radius;
		int minX = Math.max(chunkMinX, Mth.floor(centerX - radius));
		int maxX = Math.min(chunkMaxX, Mth.floor(centerX + radius));
		int minZ = Math.max(chunkMinZ, Mth.floor(centerZ - radius));
		int maxZ = Math.min(chunkMaxZ, Mth.floor(centerZ + radius));
		if (minX > maxX || minZ > maxZ) {
			return;
		}

		for (int x = minX; x <= maxX; x++) {
			double dx = (x + 0.5) - centerX;
			for (int z = minZ; z <= maxZ; z++) {
				double dz = (z + 0.5) - centerZ;
				int localX = x - chunkMinX;
				int localZ = z - chunkMinZ;
				int idx = localZ * 16 + localX;
				int surfaceBuffer = entranceFlags[idx] ? this.surfaceEntranceBuffer : this.surfaceCaveBuffer;
				int roof = entranceFlags[idx] ? 0 : this.minCaveRoof;
				int surface = surfaceHeights[idx];
				int surfaceCap = surface - roof - surfaceBuffer;
				int wormRange = Math.max(1, this.wormMaxY - this.wormMinY);
				double surfaceBias = (centerY - this.wormMinY) / (double) wormRange;
				surfaceBias = Mth.clamp(surfaceBias, 0.0, 1.0);
				surfaceBias *= surfaceBias;
				double surfaceDelta = Math.max(0.0, surface - this.seaLevel);
				double adjustedCenterY = centerY + surfaceDelta * surfaceBias;
				int minY = Math.max(chunkMinY + 1, Mth.floor(centerY - radius));
				int maxY = Math.min(chunkMaxY - 1, Mth.floor(adjustedCenterY + radius));
				if (surfaceCap < minY || minY > maxY) {
					continue;
				}
				for (int y = minY; y <= maxY; y++) {
					if (y > surfaceCap) {
						continue;
					}
					double center = y <= centerY ? centerY : adjustedCenterY;
					double dy = (y + 0.5) - center;
					if (dx * dx + dy * dy + dz * dz > radiusSq) {
						continue;
					}
					if (structureMask != null && structureMask.contains(localX, localZ, y)) {
						continue;
					}
					cursor.set(x, y, z);
					BlockState existing = chunk.getBlockState(cursor);
					if (existing.is(Blocks.BEDROCK)) {
						continue;
					}
					if (!existing.isAir()) {
						chunk.setBlockState(cursor, fill);
					}
				}
			}
		}
	}

	private boolean allowSurfaceEntrances(int worldX, int worldZ) {
		double value = this.entranceNoise.getValue(worldX * this.entranceFreq, 0.0, worldZ * this.entranceFreq);
		return value > ENTRANCE_THRESHOLD;
	}

	private static final class WormWalker {
		private double x;
		private double y;
		private double z;
		private float yaw;
		private float pitch;
		private double radius;
		private final int steps;
		private int branches;

		private WormWalker(double x, double y, double z, float yaw, float pitch, double radius, int steps, int branches) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.radius = radius;
			this.steps = steps;
			this.branches = branches;
		}
	}

	private static double resolveMetersPerBlock(double worldScale) {
		if (worldScale <= 0.0) {
			return 1.0;
		}
		return worldScale;
	}

	private static double resolveVerticalMetersPerBlock(EarthGeneratorSettings settings, double metersPerBlock) {
		double heightScale = settings.terrestrialHeightScale();
		if (heightScale <= 0.0) {
			return metersPerBlock;
		}
		return metersPerBlock / heightScale;
	}

	private static CaveDetail resolveDetail(EarthGeneratorSettings settings, double metersPerBlock) {
		if (!settings.caveGeneration()) {
			return CaveDetail.OFF;
		}
		if (metersPerBlock <= FULL_DETAIL_SCALE_MAX) {
			return CaveDetail.FULL;
		}
		if (metersPerBlock <= MAJOR_DETAIL_SCALE_MAX) {
			return CaveDetail.MAJOR;
		}
		if (metersPerBlock <= REGIONAL_DETAIL_SCALE_MAX) {
			return CaveDetail.REGIONAL;
		}
		return CaveDetail.OFF;
	}

	private static double resolveMaxFrequency(CaveDetail detail) {
		return switch (detail) {
			case FULL -> 1.0;
			case MAJOR -> 0.25;
			case REGIONAL -> 0.08;
			case OFF -> 0.0;
		};
	}

	private static int minCaveRoofBlocks(CaveDetail detail) {
		return switch (detail) {
			case FULL -> 2;
			case MAJOR -> 4;
			case REGIONAL -> 6;
			case OFF -> 2;
		};
	}

	private static double scaleFrequency(double baseFreq, double metersPerBlock, double maxFreq) {
		if (metersPerBlock <= 0.0) {
			return baseFreq;
		}
		return Math.min(baseFreq * metersPerBlock, maxFreq);
	}

	private static int scaleMetersToBlocks(double meters, double metersPerBlock, int minBlocks) {
		if (metersPerBlock <= 0.0) {
			return Math.max(minBlocks, (int) Math.round(meters));
		}
		return Math.max(minBlocks, (int) Math.round(meters / metersPerBlock));
	}

	private static double scaleMetersToBlocks(double meters, double metersPerBlock, double minBlocks) {
		if (metersPerBlock <= 0.0) {
			return Math.max(minBlocks, meters);
		}
		return Math.max(minBlocks, meters / metersPerBlock);
	}

	private enum CaveDetail {
		FULL,
		MAJOR,
		REGIONAL,
		OFF
	}

	private static NormalNoise createNoise(long seed, int firstOctave, double... amplitudes) {
		DoubleList list = new DoubleArrayList(amplitudes);
		return NormalNoise.create(RandomSource.create(seed), new NormalNoise.NoiseParameters(firstOctave, list));
	}

	private static long seedFromCoords(int x, int y, int z) {
		long seed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
		seed = seed * seed * 42317861L + seed * 11L;
		return seed >> 16;
	}
}
