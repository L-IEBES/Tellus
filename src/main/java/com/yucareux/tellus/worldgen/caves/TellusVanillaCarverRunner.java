package com.yucareux.tellus.worldgen.caves;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.server.level.WorldGenRegion;
import org.jspecify.annotations.NonNull;

public final class TellusVanillaCarverRunner {
	private static final int CARVER_RADIUS_CHUNKS = 8;

	private final @NonNull BiomeSource biomeSource;
	private final @NonNull NoiseBasedChunkGenerator carvingContextGenerator;
	private final @NonNull NoiseGeneratorSettings contextNoiseSettings;
	private final List<ConfiguredWorldCarver<?>> configuredCarvers;
	private final int chunkMinY;

	public TellusVanillaCarverRunner(
			@NonNull BiomeSource biomeSource,
			Registry<Block> blockRegistry,
			@NonNull Holder<NoiseGeneratorSettings> noiseSettings,
			int tellusMinY,
			int tellusHeight
	) {
		this.biomeSource = Objects.requireNonNull(biomeSource, "biomeSource");
		Objects.requireNonNull(blockRegistry, "blockRegistry");
		Holder<NoiseGeneratorSettings> contextSettings = Objects.requireNonNull(noiseSettings, "noiseSettings");
		this.chunkMinY = tellusMinY;
		this.contextNoiseSettings = Objects.requireNonNull(contextSettings.value(), "contextNoiseSettings");
		this.carvingContextGenerator = Objects.requireNonNull(
				new NoiseBasedChunkGenerator(this.biomeSource, contextSettings),
				"carvingContextGenerator"
		);
		this.configuredCarvers = TellusConfiguredCarvers.create(blockRegistry, tellusMinY, tellusHeight).orderedCarvers();
	}

	public void applyCarvers(
			WorldGenRegion level,
			long worldSeed,
			@NonNull RandomState randomState,
			BiomeManager biomeManager,
			@NonNull StructureManager structures,
			ChunkAccess chunk,
			int[] floodGuardYByColumn
	) {
		RandomState safeRandomState = Objects.requireNonNull(randomState, "randomState");
		StructureManager safeStructures = Objects.requireNonNull(structures, "structures");
		NoiseGeneratorSettings safeNoiseSettings = Objects.requireNonNull(this.contextNoiseSettings, "contextNoiseSettings");
		NoiseBasedChunkGenerator safeCarvingContextGenerator = Objects.requireNonNull(
				this.carvingContextGenerator,
				"carvingContextGenerator"
		);
		BiomeManager carvedBiomeManager = biomeManager.withDifferentSource((quartX, quartY, quartZ) ->
				this.biomeSource.getNoiseBiome(quartX, quartY, quartZ, safeRandomState.sampler())
		);
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
		ChunkPos targetPos = chunk.getPos();
		RegistryAccess registryAccess = level.registryAccess();
		Aquifer.@NonNull FluidPicker fluidPicker = Objects.requireNonNull(
				createFluidPicker(this.chunkMinY + 8),
				"fluidPicker"
		);
		NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(candidateChunk -> NoiseChunk.forChunk(
				candidateChunk,
				safeRandomState,
				Beardifier.forStructuresInChunk(safeStructures, candidateChunk.getPos()),
				safeNoiseSettings,
				fluidPicker,
				Blender.empty()
		));
		Aquifer aquifer = noiseChunk.aquifer();
		CarvingContext carvingContext = new CarvingContext(
				safeCarvingContextGenerator,
				registryAccess,
				chunk.getHeightAccessorForGeneration(),
				noiseChunk,
				safeRandomState,
				safeNoiseSettings.surfaceRule()
		);
		CarvingMask carvingMask = Objects.requireNonNull(
				getCarvingMask(chunk, floodGuardYByColumn),
				"carvingMask"
		);
		for (int offsetX = -CARVER_RADIUS_CHUNKS; offsetX <= CARVER_RADIUS_CHUNKS; offsetX++) {
			for (int offsetZ = -CARVER_RADIUS_CHUNKS; offsetZ <= CARVER_RADIUS_CHUNKS; offsetZ++) {
				ChunkPos sourcePos = new ChunkPos(targetPos.x + offsetX, targetPos.z + offsetZ);
				for (int carverIndex = 0; carverIndex < this.configuredCarvers.size(); carverIndex++) {
					ConfiguredWorldCarver<?> configured = this.configuredCarvers.get(carverIndex);
					random.setLargeFeatureSeed(worldSeed + carverIndex, sourcePos.x, sourcePos.z);
					if (configured.isStartChunk(random)) {
						configured.carve(
								carvingContext,
								chunk,
								pos -> Objects.requireNonNull(
										carvedBiomeManager.getBiome(Objects.requireNonNull(pos, "carveBiomePos")),
										"carveBiome"
								),
								random,
								aquifer,
								sourcePos,
								carvingMask
						);
					}
				}
			}
		}
		if (chunk instanceof ProtoChunk protoChunk && carvingMask != protoChunk.getCarvingMask()) {
			protoChunk.setCarvingMask(carvingMask);
		}
	}

	private static @NonNull CarvingMask getCarvingMask(ChunkAccess chunk, int[] floodGuardYByColumn) {
		CarvingMask baseMask;
		if (chunk instanceof ProtoChunk protoChunk) {
			baseMask = protoChunk.getOrCreateCarvingMask();
		} else {
			baseMask = new CarvingMask(chunk.getHeight(), chunk.getMinY());
		}
		if (floodGuardYByColumn == null) {
			return Objects.requireNonNull(baseMask, "baseMask");
		}
		CarvingMask guardedMask = new CarvingMask(baseMask.toArray(), chunk.getMinY());
		guardedMask.setAdditionalMask((x, y, z) ->
				y >= floodGuardYByColumn[chunkIndex(x & 15, z & 15)]
		);
		return Objects.requireNonNull(guardedMask, "guardedMask");
	}

	private static Aquifer.@NonNull FluidPicker createFluidPicker(int lavaLevel) {
		Aquifer.FluidStatus lava = new Aquifer.FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
		Aquifer.FluidStatus air = new Aquifer.FluidStatus(Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
		return Objects.requireNonNull((x, y, z) -> y < lavaLevel ? lava : air, "fluidPicker");
	}

	private static int chunkIndex(int localX, int localZ) {
		return (localZ << 4) | localX;
	}
}
