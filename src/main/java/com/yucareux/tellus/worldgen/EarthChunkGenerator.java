package com.yucareux.tellus.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.worldgen.caves.TellusNoiseSettingsAdapter;
import com.yucareux.tellus.worldgen.caves.TellusVanillaCarverRunner;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.NonNull;

public final class EarthChunkGenerator extends ChunkGenerator {
	public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
			EarthGeneratorSettings.CODEC.fieldOf("settings").forGetter(EarthChunkGenerator::settings)
	).apply(instance, EarthChunkGenerator::new));

	private static final double EQUATOR_CIRCUMFERENCE = 40075017.0;
	private static final TellusElevationSource ELEVATION_SOURCE = TellusWorldgenSources.elevation();
	private static final TellusLandCoverSource LAND_COVER_SOURCE = TellusWorldgenSources.landCover();
	private static final TellusLandMaskSource LAND_MASK_SOURCE = TellusWorldgenSources.landMask();
	private static final int COVER_ROLL_RANGE = 200;
	private static final int SNOW_ICE_CHANCE = 3;
	private static final int POWDER_SNOW_CHANCE = 30;
	private static final int MAX_POWDER_DEPTH = 5;
	private static final int ESA_NO_DATA = 0;
	private static final int ESA_TREE_COVER = 10;
	private static final int ESA_SHRUBLAND = 20;
	private static final int ESA_GRASSLAND = 30;
	private static final int ESA_CROPLAND = 40;
	private static final int ESA_BUILT_UP = 50;
	private static final int ESA_BARE_SPARSE = 60;
	private static final int ESA_SNOW_ICE = 70;
	private static final int ESA_WATER = 80;
	private static final int ESA_HERBACEOUS_WETLAND = 90;
	private static final int ESA_MANGROVES = 95;
	private static final int ESA_MOSS_LICHEN = 100;
	private static final byte CLIMATE_UNKNOWN = 0;
	private static final byte CLIMATE_TROPICAL = 1;
	private static final byte CLIMATE_ARID = 2;
	private static final byte CLIMATE_TEMPERATE = 3;
	private static final byte CLIMATE_COLD = 4;
	private static final byte CLIMATE_POLAR = 5;
	private static final int TREE_CELL_SIZE = 5;
	private static final int SURFACE_DEPTH = 4;
	private static final int SLOPE_SAMPLE_STEP = 4;
	private static final int STONY_SLOPE_DIFF = 3;
	private static final int SNOW_SLOPE_DIFF = 4;
	private static final int TALUS_SLOPE_DIFF = 4;
	private static final int CLIFF_SLOPE_DIFF = 6;
	private static final int SNOW_EXPOSE_MIN_HEIGHT_ABOVE_SEA = 110;
	private static final int MOUNTAIN_DARK_ROCK_MIN_HEIGHT_ABOVE_SEA = 120;
	private static final int MOUNTAIN_DARK_MASS_MIN_HEIGHT_ABOVE_SEA = 180;
	private static final int FROZEN_PEAKS_DARK_START_HEIGHT_ABOVE_SEA = 135;
	private static final int FROZEN_PEAKS_DARK_FULL_HEIGHT_ABOVE_SEA = 235;
	private static final int FROZEN_PEAKS_FORCE_MASS_HEIGHT_ABOVE_SEA = 220;
	private static final int STONY_PEAKS_DARK_START_HEIGHT_ABOVE_SEA = 155;
	private static final int STONY_PEAKS_DARK_FULL_HEIGHT_ABOVE_SEA = 255;
	private static final int STONY_PEAKS_FORCE_MASS_HEIGHT_ABOVE_SEA = 245;
	private static final int GENERIC_MOUNTAIN_DARK_START_HEIGHT_ABOVE_SEA = 115;
	private static final int GENERIC_MOUNTAIN_DARK_FULL_HEIGHT_ABOVE_SEA = 225;
	private static final int GENERIC_MOUNTAIN_FORCE_MASS_HEIGHT_ABOVE_SEA = 205;
	private static final int GENERIC_MOUNTAIN_FORCE_MASS_MIN_SLOPE = 2;
	private static final float MOUNTAIN_TREE_TRANSITION_MIN_WEIGHT = 0.18f;
	private static final int MOUNTAIN_TREE_TRANSITION_NEAR = 16;
	private static final int MOUNTAIN_TREE_TRANSITION_MID = 32;
	private static final int MOUNTAIN_TREE_TRANSITION_FAR = 48;
	private static final int TERRAIN_ANOMALY_REPAIR_MIN_HEIGHT_ABOVE_SEA = 50;
	private static final int TERRAIN_ANOMALY_AXIS_DROP_THRESHOLD = 34;
	private static final int TERRAIN_ANOMALY_MEAN_DROP_THRESHOLD = 26;
	private static final int TERRAIN_ANOMALY_MIN_EDGE_DROP = 20;
	private static final int TERRAIN_ANOMALY_CARDINAL_SPAN_MAX = 44;
	private static final int TERRAIN_ANOMALY_LINEAR_DROP_THRESHOLD = 30;
	private static final int TERRAIN_ANOMALY_LINEAR_ALONG_MAX = 10;
	private static final int TERRAIN_ANOMALY_REPAIR_MARGIN = 0;
	private static final int TERRAIN_ANOMALY_REPAIR_PASSES = 4;
	private static final int TERRAIN_ANOMALY_RING_DROP_THRESHOLD = 24;
	private static final int TERRAIN_ANOMALY_RING_MIN_HIGH_NEIGHBORS = 5;
	private static final int TERRAIN_ANOMALY_RING_MAX_HIGH_SPAN = 36;
	private static final int STEEP_WATER_SLOPE_THRESHOLD = 8;
	private static final int STEEP_WATER_MIN_HEIGHT_ABOVE_SEA = 18;
	private static final int STEEP_WATER_COVER_SEARCH_RADIUS = 24;
	private static final int MOUNTAIN_SNOW_RETENTION_THRESHOLD = 52;
	private static final int MOUNTAIN_DARK_BLEND_THRESHOLD = 54;
	private static final int MOUNTAIN_DARK_MASS_THRESHOLD = 74;
	private static final int MOUNTAIN_SNOW_BREAKUP_CELL = 96;
	private static final int MOUNTAIN_DARK_ROCK_CELL = 192;
	private static final long MOUNTAIN_SNOW_BREAKUP_SALT = 0x5A7C1E29D3B4F061L;
	private static final long MOUNTAIN_DARK_ROCK_SALT = 0x31C5BF8AD947E26DL;
	private static final int BADLANDS_BAND_SLOPE_DIFF = 3;
	private static final int BADLANDS_BAND_HEIGHT = 3;
	private static final int BADLANDS_BAND_DEPTH = 16;
	private static final int BADLANDS_BAND_OFFSET_CELL = 32;
	private static final int VILLAGE_FLATNESS_STEP = 4;
	private static final int VILLAGE_FLATNESS_PADDING = 4;
	private static final int VILLAGE_MAX_HEIGHT_DELTA = 6;
	private static final int MINESHAFT_TARGET_MIN_DEPTH = 30;
	private static final int MINESHAFT_TARGET_DEPTH_VARIATION = 20;
	private static final int MINESHAFT_MIN_FLOOR_CLEARANCE = 14;
	private static final int MINESHAFT_MIN_SHIFT_DELTA = 3;
	private static final long MINESHAFT_HEIGHT_ADJUST_SALT = 0x1D5AA3C7E9B4F281L;
	private static final int STRONGHOLD_TARGET_MIN_DEPTH = 42;
	private static final int STRONGHOLD_TARGET_DEPTH_VARIATION = 16;
	private static final int STRONGHOLD_MIN_FLOOR_CLEARANCE = 20;
	private static final int STRONGHOLD_MIN_SHIFT_DELTA = 3;
	private static final long STRONGHOLD_HEIGHT_ADJUST_SALT = 0x7BE41D3F6A259C80L;
	private static final int TRIAL_CHAMBER_TARGET_MIN_DEPTH = 72;
	private static final int TRIAL_CHAMBER_TARGET_DEPTH_VARIATION = 24;
	private static final int TRIAL_CHAMBER_MIN_FLOOR_CLEARANCE = 26;
	private static final int TRIAL_CHAMBER_MIN_SHIFT_DELTA = 4;
	private static final long TRIAL_CHAMBER_HEIGHT_ADJUST_SALT = 0x3AC8F0D1BE274695L;
	private static final int STRUCTURE_CLEARANCE_CORE_PADDING_XZ = 1;
	private static final int STRUCTURE_CLEARANCE_CORE_PADDING_Y = 0;
	private static final int STRUCTURE_CLEARANCE_SHELL_RADIUS_XZ = 6;
	private static final int STRUCTURE_CLEARANCE_SHELL_RADIUS_Y_ABOVE = 4;
	private static final int STRUCTURE_CLEARANCE_SHELL_RADIUS_Y_BELOW = 0;
	private static final int STRUCTURE_CLEARANCE_MIN_DEPTH = 20;
	private static final double STRUCTURE_CLEARANCE_NOISE_STRENGTH = 0.22;
	private static final long STRUCTURE_CLEARANCE_NOISE_SALT = 0x6E5A9D14C3B27F41L;
	private static final int SPAGHETTI_WATER_GUARD_RADIUS = 2;
	private static final int SPAGHETTI_WATER_GUARD_DEPTH = 4;
	private static final double OCEAN_CHUNK_CARVER_GUARD_RATIO = 0.7;
	private static final int OCEAN_CARVER_FLOOR_BUFFER = 3;
	private static final float AXOLOTL_CHUNK_CHANCE = 0.55f;
	private static final float AXOLOTL_POND_CHANCE = 0.68f;
	private static final int MAX_AXOLOTLS_PER_CHUNK = 2;
	private static final @NonNull BlockState[] BADLANDS_BANDS = {
			Blocks.TERRACOTTA.defaultBlockState(),
			Blocks.ORANGE_TERRACOTTA.defaultBlockState(),
			Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
			Blocks.BROWN_TERRACOTTA.defaultBlockState(),
			Blocks.RED_TERRACOTTA.defaultBlockState(),
			Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState(),
			Blocks.WHITE_TERRACOTTA.defaultBlockState()
	};
	private static final int CHUNK_SIDE = 16;
	private static final int CHUNK_MASK = CHUNK_SIDE - 1;
	private static final int CHUNK_AREA = CHUNK_SIDE * CHUNK_SIDE;
	private static final int TREE_MAX_SURFACE_DROP = 2;
	private static final int LOD_MIN_WATER_DEPTH = 25;
	private static final int SURFACE_ALPINE_HEIGHT_ABOVE_SEA = 200;
	private static final int SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA = 120;
	private static final @NonNull BlockState AIR_STATE = Blocks.AIR.defaultBlockState();
	private static final @NonNull BlockState STONE_STATE = Blocks.STONE.defaultBlockState();
	private static final @NonNull BlockState DEEPSLATE_STATE = Blocks.DEEPSLATE.defaultBlockState();
	private static final @NonNull BlockState WATER_STATE = Blocks.WATER.defaultBlockState();
	private static final @NonNull BlockState BEDROCK_STATE = Blocks.BEDROCK.defaultBlockState();
	private static final @NonNull BlockState CAVE_AIR_STATE = Blocks.CAVE_AIR.defaultBlockState();
	private static final @NonNull BlockState DIRT_STATE = Blocks.DIRT.defaultBlockState();
	private static final @NonNull BlockState SAND_STATE = Blocks.SAND.defaultBlockState();
	private static final @NonNull BlockState RED_SAND_STATE = Blocks.RED_SAND.defaultBlockState();
	private static final @NonNull BlockState SANDSTONE_STATE = Blocks.SANDSTONE.defaultBlockState();
	private static final @NonNull BlockState TERRACOTTA_STATE = Blocks.TERRACOTTA.defaultBlockState();
	private static final @NonNull BlockState GRASS_BLOCK_STATE = Blocks.GRASS_BLOCK.defaultBlockState();
	private static final @NonNull BlockState PODZOL_STATE = Blocks.PODZOL.defaultBlockState();
	private static final @NonNull BlockState COARSE_DIRT_STATE = Blocks.COARSE_DIRT.defaultBlockState();
	private static final @NonNull BlockState ROOTED_DIRT_STATE = Blocks.ROOTED_DIRT.defaultBlockState();
	private static final @NonNull BlockState MUD_STATE = Blocks.MUD.defaultBlockState();
	private static final @NonNull BlockState PACKED_MUD_STATE = Blocks.PACKED_MUD.defaultBlockState();
	private static final @NonNull BlockState MOSS_BLOCK_STATE = Blocks.MOSS_BLOCK.defaultBlockState();
	private static final @NonNull BlockState COBBLESTONE_STATE = Blocks.COBBLESTONE.defaultBlockState();
	private static final @NonNull BlockState ANDESITE_STATE = Blocks.ANDESITE.defaultBlockState();
	private static final @NonNull BlockState DIORITE_STATE = Blocks.DIORITE.defaultBlockState();
	private static final @NonNull BlockState TUFF_STATE = Blocks.TUFF.defaultBlockState();
	private static final @NonNull BlockState GRAVEL_STATE = Blocks.GRAVEL.defaultBlockState();
	private static final @NonNull BlockState CLAY_STATE = Blocks.CLAY.defaultBlockState();
	private static final @NonNull BlockState ICE_STATE = Blocks.ICE.defaultBlockState();
	private static final @NonNull BlockState POWDER_SNOW_STATE = Blocks.POWDER_SNOW.defaultBlockState();
	private static final @NonNull BlockState SNOW_BLOCK_STATE = Blocks.SNOW_BLOCK.defaultBlockState();
	private static final @NonNull BlockState SNOW_LAYER_STATE = Objects.requireNonNull(
			Blocks.SNOW.defaultBlockState(),
			"snowLayerState"
	);
	private static final AtomicBoolean LOGGED_CHUNK_LAYOUT = new AtomicBoolean(false);

	private static final Map<BiomeSettingsKey, BiomeGenerationSettings> FILTERED_SETTINGS = new ConcurrentHashMap<>();
	private static final Map<Holder<Biome>, List<ConfiguredFeature<?, ?>>> TREE_FEATURES = new ConcurrentHashMap<>();

	private final EarthGeneratorSettings settings;
	private final int seaLevel;
	private final int minY;
	private final int height;
	private final WaterSurfaceResolver waterResolver;
	private volatile TellusVanillaCarverRunner tellusCarverRunner;
	private final ThreadLocal<WaterChunkCache> waterChunkCache = ThreadLocal.withInitial(WaterChunkCache::new);
	private volatile long worldSeed = 0L;
	private final AtomicBoolean fastSpawnMode = new AtomicBoolean(true);

	public EarthChunkGenerator(BiomeSource biomeSource, EarthGeneratorSettings settings) {
		super(biomeSource, biome -> generationSettingsForBiome(biome, settings));
		this.settings = settings;
		this.seaLevel = settings.resolveSeaLevel();
		EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
		this.minY = limits.minY();
		this.height = limits.height();
		this.waterResolver = TellusWorldgenSources.waterResolver(settings);
		if (biomeSource instanceof EarthBiomeSource earthBiomeSource) {
			earthBiomeSource.setFastSpawnMode(true);
		}
		if (Tellus.LOGGER.isInfoEnabled()) {
			Tellus.LOGGER.info(
					"EarthChunkGenerator init: scale={}, minAltitude={}, maxAltitude={}, heightOffset={}, limits=[minY={}, height={}, logicalHeight={}], seaLevel={}",
					settings.worldScale(),
					settings.minAltitude(),
					settings.maxAltitude(),
					settings.heightOffset(),
					limits.minY(),
					limits.height(),
					limits.logicalHeight(),
					this.seaLevel
			);
		}
	}

	public static EarthChunkGenerator create(HolderLookup.Provider registries, EarthGeneratorSettings settings) {
		return new EarthChunkGenerator(new EarthBiomeSource(registries.lookupOrThrow(Registries.BIOME), settings), settings);
	}

	public EarthGeneratorSettings settings() {
		return this.settings;
	}

	@Override
	public @NonNull ChunkGeneratorStructureState createState(
			@NonNull HolderLookup<StructureSet> structureSets,
			@NonNull RandomState randomState,
			long seed
	) {
		this.worldSeed = seed;
		HolderLookup<StructureSet> filtered = new FilteredStructureLookup(structureSets, this::isStructureSetEnabled);
		return ChunkGeneratorStructureState.createForNormal(randomState, seed, this.biomeSource, filtered);
	}

	public BlockPos getSpawnPosition(LevelHeightAccessor heightAccessor) {
		return getSurfacePosition(heightAccessor, this.settings.spawnLatitude(), this.settings.spawnLongitude());
	}

	public BlockPos getSurfacePosition(LevelHeightAccessor heightAccessor, double latitude, double longitude) {
		double blocksPerDegree = blocksPerDegree(this.settings.worldScale());
		int spawnX = Mth.floor(longitude * blocksPerDegree);
		int spawnZ = Mth.floor(-latitude * blocksPerDegree);
		WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(spawnX, spawnZ);
		int surface = column.terrainSurface();
		if (column.hasWater()) {
			surface = Math.max(surface, column.waterSurface());
		}
		int maxY = heightAccessor.getMaxY() - 1;
		int spawnY = Mth.clamp(surface + 1, heightAccessor.getMinY(), maxY);
		return new BlockPos(spawnX, spawnY, spawnZ);
	}

	public double longitudeFromBlock(double blockX) {
		return blockX / blocksPerDegree(this.settings.worldScale());
	}

	public double latitudeFromBlock(double blockZ) {
		return -blockZ / blocksPerDegree(this.settings.worldScale());
	}

	private static double blocksPerDegree(double worldScale) {
		if (worldScale <= 0.0) {
			return 0.0;
		}
		return (EQUATOR_CIRCUMFERENCE / 360.0) / worldScale;
	}

	@Override
	protected @NonNull MapCodec<? extends ChunkGenerator> codec() {
		return Objects.requireNonNull(CODEC, "CODEC");
	}

	@Override
	public void applyCarvers(
			@NonNull WorldGenRegion level,
			long seed,
			@NonNull RandomState random,
			@NonNull BiomeManager biomeManager,
			@NonNull StructureManager structures,
			@NonNull ChunkAccess chunk
	) {
		if (SharedConstants.DEBUG_DISABLE_CARVERS || !this.settings.caveGeneration()) {
			return;
		}
		boolean[] waterFlags = new boolean[CHUNK_AREA];
		WaterSurfaceResolver.WaterChunkData waterData = resolveChunkWaterData(chunk.getPos());
		int waterColumnCount = 0;
		for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
			for (int localX = 0; localX < CHUNK_SIDE; localX++) {
				boolean hasWater = waterData.hasWater(localX, localZ);
				waterFlags[chunkIndex(localX, localZ)] = hasWater;
				if (hasWater) {
					waterColumnCount++;
				}
			}
		}
		boolean[] floodGuardColumns = computeFloodGuardColumns(waterFlags);
		int defaultFloodGuardY = this.seaLevel - SPAGHETTI_WATER_GUARD_DEPTH;
		int[] floodGuardYByColumn = new int[CHUNK_AREA];
		Arrays.fill(floodGuardYByColumn, Integer.MAX_VALUE);
		for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
			for (int localX = 0; localX < CHUNK_SIDE; localX++) {
				int index = chunkIndex(localX, localZ);
				if (floodGuardColumns[index]) {
					floodGuardYByColumn[index] = defaultFloodGuardY;
				}
			}
		}
		if (waterColumnCount >= Math.ceil(CHUNK_AREA * OCEAN_CHUNK_CARVER_GUARD_RATIO)) {
			// In ocean-dominant chunks, preserve deep caves by guarding from local sea-floor columns instead
			// of blocking the entire vertical range.
			for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
				for (int localX = 0; localX < CHUNK_SIDE; localX++) {
					int index = chunkIndex(localX, localZ);
					if (!floodGuardColumns[index] || !waterData.hasWater(localX, localZ)) {
						continue;
					}
					int terrainSurface = waterData.terrainSurface(localX, localZ);
					int oceanFloorGuardY = Math.max(chunk.getMinY(), terrainSurface - OCEAN_CARVER_FLOOR_BUFFER);
					floodGuardYByColumn[index] = Math.min(floodGuardYByColumn[index], oceanFloorGuardY);
				}
			}
		}
		getTellusCarverRunner(level.registryAccess()).applyCarvers(
				level,
				seed,
				random,
				biomeManager,
				structures,
				chunk,
				floodGuardYByColumn
		);
	}

	@Override
	public void buildSurface(
			@NonNull WorldGenRegion level,
			@NonNull StructureManager structures,
			@NonNull RandomState random,
			@NonNull ChunkAccess chunk
	) {
	}

	@Override
	public void spawnOriginalMobs(@NonNull WorldGenRegion level) {
	}

	@Override
	public void applyBiomeDecoration(
			@NonNull WorldGenLevel level,
			@NonNull ChunkAccess chunk,
			@NonNull StructureManager structures
	) {
		super.applyBiomeDecoration(level, chunk, structures);
		if (this.settings.caveGeneration()) {
			spawnAxolotlsInLushPonds(level, chunk);
		}
		placeTrees(level, chunk);
		applyRealtimeSnowCover(level, chunk);
	}

	@Override
	public void createStructures(
			@NonNull RegistryAccess registryAccess,
			@NonNull ChunkGeneratorStructureState structureState,
			@NonNull StructureManager structures,
			@NonNull ChunkAccess chunk,
			@NonNull StructureTemplateManager templates,
			@NonNull ResourceKey<Level> levelKey
	) {
		super.createStructures(registryAccess, structureState, structures, chunk, templates, levelKey);
		filterVillageStarts(registryAccess, chunk);
		if (this.settings.addIgloos()
				&& !isFrozenPeaksChunk(chunk.getPos(), structureState.randomState())) {
			stripIglooStarts(registryAccess, chunk);
		}
		if (this.settings.addStrongholds()) {
			retargetStrongholdStarts(registryAccess, chunk);
		}
		if (this.settings.addMineshafts()) {
			retargetMineshaftStarts(registryAccess, chunk);
		}
		if (this.settings.addTrialChambers()) {
			retargetTrialChamberStarts(registryAccess, chunk);
		}
	}

	@Override
	public void createReferences(
			@NonNull WorldGenLevel level,
			@NonNull StructureManager structures,
			@NonNull ChunkAccess chunk
	) {
		super.createReferences(level, structures, chunk);
	}

	@Override
	public @NonNull CompletableFuture<ChunkAccess> fillFromNoise(
			@NonNull Blender blender,
			@NonNull RandomState random,
			@NonNull StructureManager structures,
			@NonNull ChunkAccess chunk
	) {
		disableFastSpawnMode();
		fillTellusSurface(random, structures, chunk);
		return Objects.requireNonNull(CompletableFuture.<ChunkAccess>completedFuture(chunk), "completedFuture");
	}

	private void fillTellusSurface(
			@NonNull RandomState random,
			@NonNull StructureManager structures,
			@NonNull ChunkAccess chunk
	) {
		ChunkPos pos = chunk.getPos();
		TellusWorldgenSources.prefetchForChunk(pos, this.settings);
		int chunkMinY = chunk.getMinY();
		int chunkHeight = chunk.getHeight();
		int chunkMaxY = chunkMinY + chunkHeight;
		if (LOGGED_CHUNK_LAYOUT.compareAndSet(false, true) && Tellus.LOGGER.isInfoEnabled()) {
			Tellus.LOGGER.info(
					"fillFromNoise layout: chunkPos={}, minY={}, height={}, maxY={}, sections={}, genMinY={}, genHeight={}, seaLevel={}, settingsMinAlt={}, settingsMaxAlt={}",
					pos,
					chunkMinY,
					chunkHeight,
					chunkMinY + chunkHeight - 1,
					chunkHeight >> 4,
					this.minY,
					this.height,
					this.seaLevel,
					this.settings.minAltitude(),
					this.settings.maxAltitude()
			);
		}
		WaterSurfaceResolver.WaterChunkData waterData = resolveChunkWaterData(pos);
		int deepslateStart = this.minY + 64;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		int step = SLOPE_SAMPLE_STEP;
		int gridSize = CHUNK_SIDE + step * 2;
		int[] heightGrid = new int[gridSize * gridSize];
		int gridMinX = pos.getMinBlockX() - step;
		int gridMinZ = pos.getMinBlockZ() - step;
		for (int dz = 0; dz < gridSize; dz++) {
			int worldZ = gridMinZ + dz;
			int row = dz * gridSize;
			for (int dx = 0; dx < gridSize; dx++) {
				int worldX = gridMinX + dx;
				heightGrid[row + dx] = sampleSurfaceHeight(worldX, worldZ);
			}
		}

		int[] coverClasses = new int[CHUNK_AREA];
		int[] terrainSurfaces = new int[CHUNK_AREA];
		int[] waterSurfaces = new int[CHUNK_AREA];
		boolean[] waterFlags = new boolean[CHUNK_AREA];

		int chunkMinX = pos.getMinBlockX();
		int chunkMinZ = pos.getMinBlockZ();
		int bedrockY = this.minY;
		boolean bedrockInChunk = bedrockY >= chunkMinY && bedrockY < chunkMaxY;
			for (int localX = 0; localX < CHUNK_SIDE; localX++) {
				int worldX = chunkMinX + localX;
				for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
				int worldZ = chunkMinZ + localZ;
				int index = chunkIndex(localX, localZ);
				int sampledCoverClass = LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
				int gridIndex = (localZ + step) * gridSize + (localX + step);
				int cachedSurface = heightGrid[gridIndex];
				int clampedCachedSurface = Mth.clamp(cachedSurface, chunkMinY, chunkMaxY - 1);
				int waterSlope = sampleSlopeDiffCached(heightGrid, gridSize, step, gridIndex, clampedCachedSurface);
				int coverClass = resolveEffectiveCoverClassForTerrain(
						worldX,
						worldZ,
						sampledCoverClass,
						clampedCachedSurface,
						waterSlope
				);
				boolean suppressWater = sampledCoverClass == ESA_WATER && coverClass != sampledCoverClass;
				ColumnHeights column = resolveColumnHeights(
						worldX,
						worldZ,
						localX,
						localZ,
						chunkMinY,
						chunkMaxY,
						coverClass,
						waterData,
						cachedSurface,
						suppressWater
				);
				int surface = column.terrainSurface();
				coverClasses[index] = coverClass;
				terrainSurfaces[index] = surface;
				waterSurfaces[index] = column.waterSurface();
					waterFlags[index] = column.hasWater();
				}
			}
			repairAnomalousChunkTerrain(
					terrainSurfaces,
					waterSurfaces,
					waterFlags,
					coverClasses,
					heightGrid,
					gridSize,
					step,
					chunkMinY,
					chunkMaxY - 1
			);

			int[] slopeDiffs = new int[CHUNK_AREA];
			int[] convexities = new int[CHUNK_AREA];
		@SuppressWarnings("unchecked")
		Holder<Biome>[] biomeCache = (Holder<Biome>[]) new Holder[CHUNK_AREA];
		for (int localX = 0; localX < CHUNK_SIDE; localX++) {
			int worldX = chunkMinX + localX;
			for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
				int worldZ = chunkMinZ + localZ;
				int index = chunkIndex(localX, localZ);
				int surface = terrainSurfaces[index];
				int gridIndex = (localZ + step) * gridSize + (localX + step);
				int slopeDiff = sampleSlopeDiffCached(heightGrid, gridSize, step, gridIndex, surface);
				int convexity = sampleConvexityCached(heightGrid, gridSize, step, gridIndex, surface);
				slopeDiffs[index] = slopeDiff;
				convexities[index] = convexity;
				biomeCache[index] = this.biomeSource.getNoiseBiome(
						QuartPos.fromBlock(worldX),
						QuartPos.fromBlock(surface),
						QuartPos.fromBlock(worldZ),
						random.sampler()
				);
			}
		}

		int minSurface = minSurfaceHeight(terrainSurfaces);
		LevelChunkSection[] sections = chunk.getSections();
		int sectionCount = sections.length;
		int[] sectionTopYs = new int[sectionCount];
		boolean[] solidSections = new boolean[sectionCount];
		for (int i = 0; i < sectionCount; i++) {
			sectionTopYs[i] = chunkMinY + (i << 4) + CHUNK_MASK;
		}
		int solidMaxIndex = resolveSolidSectionMaxIndex(chunk, chunkMinY, minSurface, sectionCount);
		if (solidMaxIndex >= 0) {
			fillSolidSections(sections, solidSections, sectionTopYs, solidMaxIndex, STONE_STATE, DEEPSLATE_STATE, deepslateStart);
		}

		for (int localX = 0; localX < CHUNK_SIDE; localX++) {
			int worldX = chunkMinX + localX;
			for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
				int worldZ = chunkMinZ + localZ;
				int index = chunkIndex(localX, localZ);
				int surface = terrainSurfaces[index];
				int waterSurface = waterSurfaces[index];
				boolean hasWater = waterFlags[index];
				int coverClass = coverClasses[index];
				Holder<Biome> biome = biomeCache[index];

				for (int y = chunkMinY; y <= surface; ) {
					int sectionIndex = chunk.getSectionIndex(y);
					if (sectionIndex >= 0 && sectionIndex < sectionCount && solidSections[sectionIndex]) {
						y = sectionTopYs[sectionIndex] + 1;
						continue;
					}
					cursor.set(worldX, y, worldZ);
					chunk.setBlockState(cursor, y < deepslateStart ? DEEPSLATE_STATE : STONE_STATE);
					y++;
				}
				if (bedrockInChunk) {
					cursor.set(worldX, bedrockY, worldZ);
					chunk.setBlockState(cursor, BEDROCK_STATE);
				}
				if (hasWater && surface < waterSurface) {
					for (int y = surface + 1; y <= waterSurface; y++) {
						cursor.set(worldX, y, worldZ);
						chunk.setBlockState(cursor, WATER_STATE);
				}
			}
					boolean underwater = hasWater && waterSurface > surface;
					int slopeDiff = slopeDiffs[index];
					int convexity = convexities[index];
					applySurface(chunk, cursor, worldX, worldZ, surface, chunkMinY, underwater, biome, slopeDiff, convexity, coverClass);
					if (surface >= this.seaLevel && coverClass == ESA_SNOW_ICE) {
						if (shouldRetainMountainSnow(coverClass, surface - this.seaLevel, slopeDiff, convexity, worldX, worldZ)) {
							boolean reduceIce = biome.is(Biomes.FROZEN_PEAKS);
							applySnowCover(chunk, cursor, worldX, worldZ, surface, chunkMinY, reduceIce);
						}
					}
			}
		}

		carveStructureClearanceVolumes(structures, chunk);

	}

	private static boolean[] computeFloodGuardColumns(boolean[] waterFlags) {
		boolean[] result = new boolean[CHUNK_AREA];
		for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
			for (int localX = 0; localX < CHUNK_SIDE; localX++) {
				boolean nearWater = false;
				for (int dz = -SPAGHETTI_WATER_GUARD_RADIUS; dz <= SPAGHETTI_WATER_GUARD_RADIUS && !nearWater; dz++) {
					int z = localZ + dz;
					if (z < 0 || z >= CHUNK_SIDE) {
						continue;
					}
					for (int dx = -SPAGHETTI_WATER_GUARD_RADIUS; dx <= SPAGHETTI_WATER_GUARD_RADIUS; dx++) {
						int x = localX + dx;
						if (x < 0 || x >= CHUNK_SIDE) {
							continue;
						}
						if (waterFlags[chunkIndex(x, z)]) {
							nearWater = true;
							break;
						}
					}
				}
				result[chunkIndex(localX, localZ)] = nearWater;
			}
		}
		return result;
	}

	private static boolean isReplaceableCaveBlock(BlockState state) {
		return isSolidCaveAnchor(state) && !state.is(Blocks.BEDROCK);
	}

	private static boolean isSolidCaveAnchor(BlockState state) {
		return !state.isAir()
				&& state.getFluidState().isEmpty()
				&& !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	private static int chunkIndex(int localX, int localZ) {
		return localZ * CHUNK_SIDE + localX;
	}

	private void carveStructureClearanceVolumes(StructureManager structures, ChunkAccess chunk) {
		List<StructureStart> starts = structures.startsForStructure(
				chunk.getPos(),
				structure -> shouldApplyStructureTerrainAdjustment(structure.terrainAdaptation())
		);
		if (starts.isEmpty()) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		int chunkMinX = pos.getMinBlockX();
		int chunkMinZ = pos.getMinBlockZ();
		int chunkMaxX = chunkMinX + CHUNK_MASK;
		int chunkMaxZ = chunkMinZ + CHUNK_MASK;
		int chunkMinY = chunk.getMinY();
		int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (StructureStart start : starts) {
			if (start == null || !start.isValid()) {
				continue;
			}
			for (StructurePiece piece : start.getPieces()) {
				BoundingBox box = piece.getBoundingBox();
				if (!box.intersects(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ)) {
					continue;
				}
				int centerX = (box.minX() + box.maxX()) >> 1;
				int centerZ = (box.minZ() + box.maxZ()) >> 1;
				int terrainSurface = this.waterResolver.resolveColumnData(centerX, centerZ).terrainSurface();
				if (box.maxY() > terrainSurface - STRUCTURE_CLEARANCE_MIN_DEPTH) {
					// Keep surface structures (villages, etc.) untouched; clearance is for deep underground structures.
					continue;
				}
				int coreMinX = box.minX() - STRUCTURE_CLEARANCE_CORE_PADDING_XZ;
				int coreMaxX = box.maxX() + STRUCTURE_CLEARANCE_CORE_PADDING_XZ;
				int coreMinZ = box.minZ() - STRUCTURE_CLEARANCE_CORE_PADDING_XZ;
				int coreMaxZ = box.maxZ() + STRUCTURE_CLEARANCE_CORE_PADDING_XZ;
				int coreMinY = box.minY() - STRUCTURE_CLEARANCE_CORE_PADDING_Y;
				int coreMaxY = box.maxY() + STRUCTURE_CLEARANCE_CORE_PADDING_Y;
				int minX = Math.max(chunkMinX, coreMinX - STRUCTURE_CLEARANCE_SHELL_RADIUS_XZ);
				int maxX = Math.min(chunkMaxX, coreMaxX + STRUCTURE_CLEARANCE_SHELL_RADIUS_XZ);
				int minZ = Math.max(chunkMinZ, coreMinZ - STRUCTURE_CLEARANCE_SHELL_RADIUS_XZ);
				int maxZ = Math.min(chunkMaxZ, coreMaxZ + STRUCTURE_CLEARANCE_SHELL_RADIUS_XZ);
					int minY = Math.max(chunkMinY + 1, coreMinY - STRUCTURE_CLEARANCE_SHELL_RADIUS_Y_BELOW);
					int maxY = Math.min(chunkMaxY - 1, coreMaxY + STRUCTURE_CLEARANCE_SHELL_RADIUS_Y_ABOVE);
					if (maxY < minY || maxX < minX || maxZ < minZ) {
						continue;
					}
				for (int z = minZ; z <= maxZ; z++) {
					for (int x = minX; x <= maxX; x++) {
						for (int y = minY; y <= maxY; y++) {
							double nx = axisDistanceNormalized(
									x,
									coreMinX,
									coreMaxX,
									STRUCTURE_CLEARANCE_SHELL_RADIUS_XZ
							);
							double nz = axisDistanceNormalized(
									z,
									coreMinZ,
									coreMaxZ,
									STRUCTURE_CLEARANCE_SHELL_RADIUS_XZ
							);
								double ny = axisDistanceNormalized(
										y,
										coreMinY,
										coreMaxY,
										STRUCTURE_CLEARANCE_SHELL_RADIUS_Y_BELOW,
										STRUCTURE_CLEARANCE_SHELL_RADIUS_Y_ABOVE
								);
							double distance = Math.sqrt(nx * nx + ny * ny + nz * nz);
							double threshold = 1.0 + structureClearanceNoiseJitter(x, y, z) * STRUCTURE_CLEARANCE_NOISE_STRENGTH;
							if (distance > threshold) {
								continue;
							}
							cursor.set(x, y, z);
							BlockState state = chunk.getBlockState(cursor);
							if (isReplaceableCaveBlock(state)) {
								chunk.setBlockState(cursor, CAVE_AIR_STATE);
							}
						}
					}
				}
			}
		}
	}

	private double structureClearanceNoiseJitter(int x, int y, int z) {
		long seed = seedFromCoords(x, y, z) ^ this.worldSeed ^ STRUCTURE_CLEARANCE_NOISE_SALT;
		double t = Math.floorMod(seed, 2048L) / 2047.0;
		return t * 2.0 - 1.0;
	}

	private static double axisDistanceNormalized(int value, int coreMin, int coreMax, int shellRadius) {
		if (value < coreMin) {
			return (coreMin - value) / (double) Math.max(1, shellRadius);
		}
		if (value > coreMax) {
			return (value - coreMax) / (double) Math.max(1, shellRadius);
		}
		return 0.0;
	}

	private static double axisDistanceNormalized(
			int value,
			int coreMin,
			int coreMax,
			int shellRadiusBelow,
			int shellRadiusAbove
	) {
		if (value < coreMin) {
			if (shellRadiusBelow <= 0) {
				return Double.POSITIVE_INFINITY;
			}
			return (coreMin - value) / (double) shellRadiusBelow;
		}
		if (value > coreMax) {
			if (shellRadiusAbove <= 0) {
				return Double.POSITIVE_INFINITY;
			}
			return (value - coreMax) / (double) shellRadiusAbove;
		}
		return 0.0;
	}

	private static boolean shouldApplyStructureTerrainAdjustment(TerrainAdjustment adjustment) {
		return adjustment == TerrainAdjustment.BEARD_THIN || adjustment == TerrainAdjustment.BEARD_BOX;
	}

	private static int minSurfaceHeight(int[] terrainSurfaces) {
		int min = Integer.MAX_VALUE;
		for (int surface : terrainSurfaces) {
			if (surface < min) {
				min = surface;
			}
		}
		return min == Integer.MAX_VALUE ? 0 : min;
	}

	private void repairAnomalousChunkTerrain(
			int[] terrainSurfaces,
			int[] waterSurfaces,
			boolean[] waterFlags,
			int[] coverClasses,
			int[] heightGrid,
			int gridSize,
			int step,
			int minY,
			int maxY
	) {
		int[] repaired = terrainSurfaces.clone();
		for (int pass = 0; pass < TERRAIN_ANOMALY_REPAIR_PASSES; pass++) {
			int[] source = repaired.clone();
			boolean changed = false;
			for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
				for (int localX = 0; localX < CHUNK_SIDE; localX++) {
					int index = chunkIndex(localX, localZ);
					int surface = source[index];
					if (!shouldRepairTerrainAnomaly(coverClasses[index], surface, waterFlags[index])) {
						continue;
					}
					int gridIndex = (localZ + step) * gridSize + (localX + step);
					int east = sampleNeighborHeight(source, localX + 1, localZ, heightGrid, gridIndex + 1);
					int west = sampleNeighborHeight(source, localX - 1, localZ, heightGrid, gridIndex - 1);
					int north = sampleNeighborHeight(source, localX, localZ - 1, heightGrid, gridIndex - gridSize);
					int south = sampleNeighborHeight(source, localX, localZ + 1, heightGrid, gridIndex + gridSize);
					int northEast = sampleNeighborHeight(source, localX + 1, localZ - 1, heightGrid, gridIndex - gridSize + 1);
					int northWest = sampleNeighborHeight(source, localX - 1, localZ - 1, heightGrid, gridIndex - gridSize - 1);
					int southEast = sampleNeighborHeight(source, localX + 1, localZ + 1, heightGrid, gridIndex + gridSize + 1);
					int southWest = sampleNeighborHeight(source, localX - 1, localZ + 1, heightGrid, gridIndex + gridSize - 1);
					int repairedHeight = repairAnomalousTerrainHeightFromNeighbors(
							surface,
							east,
							west,
							north,
							south,
							northEast,
							northWest,
							southEast,
							southWest
					);
					if (repairedHeight > surface) {
						repaired[index] = Mth.clamp(repairedHeight, minY, maxY);
						changed = true;
					}
				}
			}
			for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
				for (int localX = 0; localX < CHUNK_SIDE; localX++) {
					int index = chunkIndex(localX, localZ);
					int gridIndex = (localZ + step) * gridSize + (localX + step);
					heightGrid[gridIndex] = repaired[index];
				}
			}
			if (!changed) {
				break;
			}
		}
		System.arraycopy(repaired, 0, terrainSurfaces, 0, repaired.length);
		for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
			for (int localX = 0; localX < CHUNK_SIDE; localX++) {
				int index = chunkIndex(localX, localZ);
				if (!waterFlags[index]) {
					waterSurfaces[index] = terrainSurfaces[index];
				} else if (terrainSurfaces[index] >= waterSurfaces[index]) {
					waterFlags[index] = false;
					waterSurfaces[index] = terrainSurfaces[index];
				}
				int gridIndex = (localZ + step) * gridSize + (localX + step);
				heightGrid[gridIndex] = terrainSurfaces[index];
			}
		}
	}

	private static int sampleNeighborHeight(
			int[] chunkSurfaces,
			int localX,
			int localZ,
			int[] heightGrid,
			int fallbackGridIndex
	) {
		if (localX >= 0 && localX < CHUNK_SIDE && localZ >= 0 && localZ < CHUNK_SIDE) {
			return chunkSurfaces[chunkIndex(localX, localZ)];
		}
		return heightGrid[fallbackGridIndex];
	}

	private int repairAnomalousSurfaceHeight(
			int worldX,
			int worldZ,
			int surface,
			int coverClass,
			int minY,
			int maxY
	) {
		if (!shouldRepairTerrainAnomaly(coverClass, surface, false)) {
			return Mth.clamp(surface, minY, maxY);
		}
		int east = sampleSurfaceHeight(worldX + 1, worldZ);
		int west = sampleSurfaceHeight(worldX - 1, worldZ);
		int north = sampleSurfaceHeight(worldX, worldZ - 1);
		int south = sampleSurfaceHeight(worldX, worldZ + 1);
		int northEast = sampleSurfaceHeight(worldX + 1, worldZ - 1);
		int northWest = sampleSurfaceHeight(worldX - 1, worldZ - 1);
		int southEast = sampleSurfaceHeight(worldX + 1, worldZ + 1);
		int southWest = sampleSurfaceHeight(worldX - 1, worldZ + 1);
		int repaired = repairAnomalousTerrainHeightFromNeighbors(
				surface,
				east,
				west,
				north,
				south,
				northEast,
				northWest,
				southEast,
				southWest
		);
		return Mth.clamp(repaired, minY, maxY);
	}

	private boolean shouldRepairTerrainAnomaly(int coverClass, int surface, boolean hasWater) {
		int heightAboveSea = surface - this.seaLevel;
		if (heightAboveSea < TERRAIN_ANOMALY_REPAIR_MIN_HEIGHT_ABOVE_SEA) {
			return false;
		}
		if (isWaterCoverClass(coverClass) && heightAboveSea < TERRAIN_ANOMALY_REPAIR_MIN_HEIGHT_ABOVE_SEA + 40) {
			return false;
		}
		return true;
	}

	private static boolean isWaterCoverClass(int coverClass) {
		return coverClass == ESA_WATER || coverClass == ESA_MANGROVES;
	}

	private static int repairAnomalousTerrainHeightFromNeighbors(
			int center,
			int east,
			int west,
			int north,
			int south,
			int northEast,
			int northWest,
			int southEast,
			int southWest
	) {
		int valid = 0;
		int sum = 0;
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		if (east != Integer.MIN_VALUE) {
			valid++;
			sum += east;
			min = Math.min(min, east);
			max = Math.max(max, east);
		}
		if (west != Integer.MIN_VALUE) {
			valid++;
			sum += west;
			min = Math.min(min, west);
			max = Math.max(max, west);
		}
		if (north != Integer.MIN_VALUE) {
			valid++;
			sum += north;
			min = Math.min(min, north);
			max = Math.max(max, north);
		}
		if (south != Integer.MIN_VALUE) {
			valid++;
			sum += south;
			min = Math.min(min, south);
			max = Math.max(max, south);
		}
		if (valid < 3) {
			return center;
		}
		int mean = sum / valid;
		int meanDrop = mean - center;
		int edgeDrop = min - center;
		int span = max - min;
		int axisDropEW = (east != Integer.MIN_VALUE && west != Integer.MIN_VALUE) ? Math.min(east, west) - center : Integer.MIN_VALUE;
		int axisDropNS = (north != Integer.MIN_VALUE && south != Integer.MIN_VALUE) ? Math.min(north, south) - center : Integer.MIN_VALUE;
		int axisDrop = Math.max(axisDropEW, axisDropNS);
		boolean linearSeamEW = axisDropEW >= TERRAIN_ANOMALY_LINEAR_DROP_THRESHOLD
				&& north != Integer.MIN_VALUE
				&& south != Integer.MIN_VALUE
				&& Math.abs(north - center) <= TERRAIN_ANOMALY_LINEAR_ALONG_MAX
				&& Math.abs(south - center) <= TERRAIN_ANOMALY_LINEAR_ALONG_MAX;
		boolean linearSeamNS = axisDropNS >= TERRAIN_ANOMALY_LINEAR_DROP_THRESHOLD
				&& east != Integer.MIN_VALUE
				&& west != Integer.MIN_VALUE
				&& Math.abs(east - center) <= TERRAIN_ANOMALY_LINEAR_ALONG_MAX
				&& Math.abs(west - center) <= TERRAIN_ANOMALY_LINEAR_ALONG_MAX;
		int axisDropNESW = (northEast != Integer.MIN_VALUE && southWest != Integer.MIN_VALUE) ? Math.min(northEast, southWest) - center : Integer.MIN_VALUE;
		int axisDropNWSE = (northWest != Integer.MIN_VALUE && southEast != Integer.MIN_VALUE) ? Math.min(northWest, southEast) - center : Integer.MIN_VALUE;
		boolean linearSeamNESW = axisDropNESW >= TERRAIN_ANOMALY_LINEAR_DROP_THRESHOLD
				&& east != Integer.MIN_VALUE
				&& west != Integer.MIN_VALUE
				&& Math.abs(east - center) <= TERRAIN_ANOMALY_LINEAR_ALONG_MAX
				&& Math.abs(west - center) <= TERRAIN_ANOMALY_LINEAR_ALONG_MAX;
		boolean linearSeamNWSE = axisDropNWSE >= TERRAIN_ANOMALY_LINEAR_DROP_THRESHOLD
				&& north != Integer.MIN_VALUE
				&& south != Integer.MIN_VALUE
				&& Math.abs(north - center) <= TERRAIN_ANOMALY_LINEAR_ALONG_MAX
				&& Math.abs(south - center) <= TERRAIN_ANOMALY_LINEAR_ALONG_MAX;
		boolean severe = axisDrop >= TERRAIN_ANOMALY_AXIS_DROP_THRESHOLD
				|| meanDrop >= TERRAIN_ANOMALY_MEAN_DROP_THRESHOLD
				|| linearSeamEW
				|| linearSeamNS
				|| linearSeamNESW
				|| linearSeamNWSE;
		if (!severe || (edgeDrop < TERRAIN_ANOMALY_MIN_EDGE_DROP && !linearSeamEW && !linearSeamNS && !linearSeamNESW && !linearSeamNWSE)) {
			return center;
		}
		if (span > TERRAIN_ANOMALY_CARDINAL_SPAN_MAX
				&& axisDrop < TERRAIN_ANOMALY_AXIS_DROP_THRESHOLD + 10
				&& !linearSeamEW
				&& !linearSeamNS
				&& !linearSeamNESW
				&& !linearSeamNWSE) {
			return center;
		}
		boolean linearSeam = linearSeamEW || linearSeamNS || linearSeamNESW || linearSeamNWSE;
		int repairMargin = linearSeam ? 0 : TERRAIN_ANOMALY_REPAIR_MARGIN;
		int target = mean - repairMargin;
		if ((axisDropEW >= TERRAIN_ANOMALY_AXIS_DROP_THRESHOLD || linearSeamEW)
				&& east != Integer.MIN_VALUE
				&& west != Integer.MIN_VALUE) {
			target = Math.max(target, Math.min(east, west) - repairMargin);
		}
		if ((axisDropNS >= TERRAIN_ANOMALY_AXIS_DROP_THRESHOLD || linearSeamNS)
				&& north != Integer.MIN_VALUE
				&& south != Integer.MIN_VALUE) {
			target = Math.max(target, Math.min(north, south) - repairMargin);
		}
		if ((axisDropNESW >= TERRAIN_ANOMALY_AXIS_DROP_THRESHOLD || linearSeamNESW)
				&& northEast != Integer.MIN_VALUE
				&& southWest != Integer.MIN_VALUE) {
			target = Math.max(target, Math.min(northEast, southWest) - repairMargin);
		}
		if ((axisDropNWSE >= TERRAIN_ANOMALY_AXIS_DROP_THRESHOLD || linearSeamNWSE)
				&& northWest != Integer.MIN_VALUE
				&& southEast != Integer.MIN_VALUE) {
			target = Math.max(target, Math.min(northWest, southEast) - repairMargin);
		}

		int highNeighborCount = 0;
		int highNeighborMin = Integer.MAX_VALUE;
		int highNeighborMax = Integer.MIN_VALUE;
		int[] ring = {east, west, north, south, northEast, northWest, southEast, southWest};
		for (int value : ring) {
			if (value == Integer.MIN_VALUE) {
				continue;
			}
			if (value - center >= TERRAIN_ANOMALY_RING_DROP_THRESHOLD) {
				highNeighborCount++;
				highNeighborMin = Math.min(highNeighborMin, value);
				highNeighborMax = Math.max(highNeighborMax, value);
			}
		}
		if (highNeighborCount >= TERRAIN_ANOMALY_RING_MIN_HIGH_NEIGHBORS
				&& highNeighborMax - highNeighborMin <= TERRAIN_ANOMALY_RING_MAX_HIGH_SPAN) {
			target = Math.max(target, highNeighborMin);
			linearSeam = true;
		}

		int cap = linearSeam ? max : max - 1;
		target = Math.min(target, cap);
		return Math.max(center, target);
	}

	private static int resolveSolidSectionMaxIndex(
			ChunkAccess chunk,
			int chunkMinY,
			int minSurface,
			int sectionCount
	) {
		if (sectionCount == 0) {
			return -1;
		}
		int sectionIndex = chunk.getSectionIndex(minSurface);
		int sectionBottom = chunkMinY + (sectionIndex << 4);
		int sectionTop = sectionBottom + CHUNK_MASK;
		int solidMaxIndex = minSurface >= sectionTop ? sectionIndex : sectionIndex - 1;
		if (solidMaxIndex < 0) {
			return -1;
		}
		return Math.min(solidMaxIndex, sectionCount - 1);
	}

	private static void fillSolidSections(
			LevelChunkSection[] sections,
			boolean[] solidSections,
			int[] sectionTopYs,
			int solidMaxIndex,
			@NonNull BlockState stone,
			@NonNull BlockState deepslate,
			int deepslateStart
	) {
		int sectionCount = sections.length;
		for (int i = 0; i <= solidMaxIndex && i < sectionCount; i++) {
			int topY = sectionTopYs[i];
			int bottomY = topY - 15;
			if (bottomY < 0 && topY >= 0) {
				continue;
			}
			@NonNull BlockState fill = topY < deepslateStart ? deepslate : stone;
			@NonNull LevelChunkSection section = Objects.requireNonNull(sections[i], "section");
			fillSection(section, fill);
			solidSections[i] = true;
		}
	}

	private static void fillSection(@NonNull LevelChunkSection section, @NonNull BlockState fill) {
		for (int y = 0; y < CHUNK_SIDE; y++) {
			for (int z = 0; z < CHUNK_SIDE; z++) {
				for (int x = 0; x < CHUNK_SIDE; x++) {
					// Use the safe section setter path to avoid palette desync under optimization mods.
					section.setBlockState(x, y, z, fill);
				}
			}
		}
		section.recalcBlockCounts();
	}

	private void filterVillageStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
		Map<Structure, StructureStart> starts = chunk.getAllStarts();
		if (starts.isEmpty()) {
			return;
		}
		Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
		for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
			StructureStart start = entry.getValue();
			if (start == null || !start.isValid()) {
				continue;
			}
			Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
			if (!isVillageStructure(registry, structure)) {
				continue;
			}
			if (isVillageStartTooSteep(start)) {
				chunk.setStartForStructure(structure, StructureStart.INVALID_START);
			}
		}
	}

	private boolean isVillageStartTooSteep(StructureStart start) {
		BoundingBox box = start.getBoundingBox();
		int minX = box.minX() - VILLAGE_FLATNESS_PADDING;
		int maxX = box.maxX() + VILLAGE_FLATNESS_PADDING;
		int minZ = box.minZ() - VILLAGE_FLATNESS_PADDING;
		int maxZ = box.maxZ() + VILLAGE_FLATNESS_PADDING;
		int minHeight = Integer.MAX_VALUE;
		int maxHeight = Integer.MIN_VALUE;
		for (int z = minZ; z <= maxZ; z += VILLAGE_FLATNESS_STEP) {
			for (int x = minX; x <= maxX; x += VILLAGE_FLATNESS_STEP) {
				int surface = sampleSurfaceHeight(x, z);
				if (surface < minHeight) {
					minHeight = surface;
				}
				if (surface > maxHeight) {
					maxHeight = surface;
				}
				if (maxHeight - minHeight > VILLAGE_MAX_HEIGHT_DELTA) {
					return true;
				}
			}
		}
		return maxHeight - minHeight > VILLAGE_MAX_HEIGHT_DELTA;
	}

	private boolean isVillageStructure(Registry<Structure> registry, Structure structure) {
		var key = registry.getKey(structure);
		return key != null && key.getPath().startsWith("village");
	}

	@Override
	public int getGenDepth() {
		return this.height;
	}

	@Override
	public int getSeaLevel() {
		return this.seaLevel;
	}

	@Override
	public int getMinY() {
		return this.minY;
	}

	@Override
	public int getBaseHeight(
			int x,
			int z,
			Heightmap.@NonNull Types heightmapType,
			@NonNull LevelHeightAccessor heightAccessor,
			@NonNull RandomState random
	) {
		if (isFastSpawnMode()) {
			int maxY = heightAccessor.getMaxY() - 1;
			return Mth.clamp(this.seaLevel + 1, heightAccessor.getMinY(), maxY);
		}
		int coverClass = sampleCoverClass(x, z);
		ColumnHeights column = resolveFastColumnHeights(x, z, heightAccessor.getMinY(), heightAccessor.getMaxY(), coverClass);
		int surface = column.terrainSurface();
		if (heightmapType == Heightmap.Types.OCEAN_FLOOR_WG || heightmapType == Heightmap.Types.OCEAN_FLOOR) {
			return surface + 1;
		}
		if (column.hasWater()) {
			return Math.max(surface, column.waterSurface()) + 1;
		}
		return surface + 1;
	}

	@Override
	public @NonNull NoiseColumn getBaseColumn(
			int x,
			int z,
			@NonNull LevelHeightAccessor heightAccessor,
			@NonNull RandomState random
	) {
		int minY = heightAccessor.getMinY();
		int height = heightAccessor.getHeight();
		BlockState[] states = new BlockState[height];
		Arrays.fill(states, AIR_STATE);

		int coverClass = sampleCoverClass(x, z);
		ColumnHeights column = resolveFastColumnHeights(x, z, minY, minY + height, coverClass);
		int surface = column.terrainSurface();
		int surfaceIndex = surface - minY;
		for (int i = 0; i <= surfaceIndex; i++) {
			if (i >= 0 && i < states.length) {
				int y = minY + i;
				states[i] = y < 0 ? DEEPSLATE_STATE : STONE_STATE;
			}
		}

		if (column.hasWater()) {
			int waterTop = column.waterSurface();
			int waterIndex = waterTop - minY;
			for (int i = surfaceIndex + 1; i <= waterIndex; i++) {
				states[i] = WATER_STATE;
			}
		}
		int bedrockIndex = this.minY - minY;
		if (bedrockIndex >= 0 && bedrockIndex < states.length) {
			states[bedrockIndex] = BEDROCK_STATE;
		}

		return Objects.requireNonNull(new NoiseColumn(minY, states), "noiseColumn");
	}

	@Override
	public void addDebugScreenInfo(@NonNull List<String> info, @NonNull RandomState random, @NonNull BlockPos pos) {
		info.add(String.format("Tellus scale: %.1f", this.settings.worldScale()));
	}

	private boolean isFastSpawnMode() {
		return this.fastSpawnMode.get();
	}

	private void disableFastSpawnMode() {
		if (!this.fastSpawnMode.compareAndSet(true, false)) {
			return;
		}
		if (this.biomeSource instanceof EarthBiomeSource earthBiomeSource) {
			earthBiomeSource.setFastSpawnMode(false);
		}
	}

	private void placeTrees(WorldGenLevel level, ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		int chunkMinX = pos.getMinBlockX();
		int chunkMinZ = pos.getMinBlockZ();
		int chunkMaxX = chunkMinX + CHUNK_MASK;
		int chunkMaxZ = chunkMinZ + CHUNK_MASK;
		int shorelineBlendRadius = Math.max(
				this.settings.riverLakeShorelineBlend(),
				this.settings.oceanShorelineBlend()
		);
		int cellMinX = Math.floorDiv(chunkMinX, TREE_CELL_SIZE);
		int cellMaxX = Math.floorDiv(chunkMaxX, TREE_CELL_SIZE);
		int cellMinZ = Math.floorDiv(chunkMinZ, TREE_CELL_SIZE);
		int cellMaxZ = Math.floorDiv(chunkMaxZ, TREE_CELL_SIZE);

		long worldSeed = level.getSeed();
		for (int cellX = cellMinX; cellX <= cellMaxX; cellX++) {
			for (int cellZ = cellMinZ; cellZ <= cellMaxZ; cellZ++) {
				long seed = seedFromCoords(cellX, 0, cellZ) ^ worldSeed;
				RandomSource random = RandomSource.create(seed);
				int worldX = cellX * TREE_CELL_SIZE + random.nextInt(TREE_CELL_SIZE);
				int worldZ = cellZ * TREE_CELL_SIZE + random.nextInt(TREE_CELL_SIZE);
				if (worldX < chunkMinX || worldX > chunkMaxX || worldZ < chunkMinZ || worldZ > chunkMaxZ) {
					continue;
				}
				int coverClass = sampleCoverClass(worldX, worldZ);
				if (coverClass != ESA_TREE_COVER) {
					continue;
				}
				if (shorelineBlendRadius > 0 && isNearWater(worldX, worldZ, shorelineBlendRadius)) {
					continue;
				}
					int expectedSurface = this.sampleSurfaceHeight(worldX, worldZ);
					if (expectedSurface < this.seaLevel) {
						continue;
					}
					int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
					if (topY < level.getMinY() || topY < this.seaLevel) {
						continue;
					}
					if (expectedSurface - topY > TREE_MAX_SURFACE_DROP) {
						// Avoid spawning trees on cave-mouth columns where terrain dropped after carving.
						continue;
					}
					BlockPos ground = new BlockPos(worldX, topY, worldZ);
					BlockState groundState = level.getBlockState(ground);
					if (!isSolidCaveAnchor(groundState)
							|| groundState.is(BlockTags.LOGS)
							|| groundState.is(BlockTags.LEAVES)) {
						continue;
					}
					BlockPos position = ground.above();
					Holder<Biome> biome = level.getBiome(position);
					if (biome.is(Biomes.MANGROVE_SWAMP)) {
						continue;
					}
					List<ConfiguredFeature<?, ?>> features = treeFeaturesForBiome(biome);
					if (features.isEmpty()) {
						continue;
					}
					if (!groundState.is(BlockTags.DIRT)) {
						level.setBlock(ground, GRASS_BLOCK_STATE, Block.UPDATE_NONE);
					}
				ConfiguredFeature<?, ?> feature = features.get(random.nextInt(features.size()));
				feature.place(level, this, random, position);
			}
		}
	}

	private boolean isNearWater(int worldX, int worldZ, int radius) {
		for (int dz = -radius; dz <= radius; dz++) {
			int z = worldZ + dz;
			for (int dx = -radius; dx <= radius; dx++) {
				int x = worldX + dx;
				int coverClass = sampleCoverClass(x, z);
				WaterSurfaceResolver.WaterInfo info = this.waterResolver.resolveWaterInfo(x, z, coverClass);
				if (info.isWater()) {
					return true;
				}
			}
		}
		return false;
	}

	private int sampleSurfaceHeight(int blockX, int blockZ) {
		boolean oceanZoom = useOceanZoom(blockX, blockZ);
		double elevation = ELEVATION_SOURCE.sampleElevationMeters(
				blockX,
				blockZ,
				this.settings.worldScale(),
				oceanZoom,
				this.settings.demProvider()
		);
		double heightScale = elevation >= 0.0 ? this.settings.terrestrialHeightScale() : this.settings.oceanicHeightScale();
		double scaled = elevation * heightScale / this.settings.worldScale();
		int offset = this.settings.heightOffset();
		int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
		return height + offset;
	}

	private boolean useOceanZoom(double blockX, double blockZ) {
		TellusLandMaskSource.LandMaskSample landSample =
				LAND_MASK_SOURCE.sampleLandMask(blockX, blockZ, this.settings.worldScale());
		if (!landSample.known()) {
			return true;
		}
		if (landSample.land()) {
			return false;
		}
		int coverClass = LAND_COVER_SOURCE.sampleCoverClass(blockX, blockZ, this.settings.worldScale());
		return coverClass == ESA_NO_DATA || coverClass == ESA_WATER;
	}

	private int sampleSlopeDiff(int worldX, int worldZ, int surface) {
		int step = SLOPE_SAMPLE_STEP;
		int east = sampleSurfaceHeight(worldX + step, worldZ);
		int west = sampleSurfaceHeight(worldX - step, worldZ);
		int north = sampleSurfaceHeight(worldX, worldZ - step);
		int south = sampleSurfaceHeight(worldX, worldZ + step);

		int maxDiff = Math.max(
				Math.max(Math.abs(east - surface), Math.abs(west - surface)),
				Math.max(Math.abs(north - surface), Math.abs(south - surface))
		);
		return maxDiff;
	}

	private int sampleConvexity(int worldX, int worldZ, int surface) {
		int step = SLOPE_SAMPLE_STEP;
		int east = sampleSurfaceHeight(worldX + step, worldZ);
		int west = sampleSurfaceHeight(worldX - step, worldZ);
		int north = sampleSurfaceHeight(worldX, worldZ - step);
		int south = sampleSurfaceHeight(worldX, worldZ + step);
		int neighborAverage = (east + west + north + south) / 4;
		return neighborAverage - surface;
	}

	private ColumnHeights resolveColumnHeights(
			int worldX,
			int worldZ,
			int localX,
			int localZ,
			int minY,
			int maxYExclusive,
			int coverClass,
			WaterSurfaceResolver.WaterChunkData waterData,
			int cachedSurface,
			boolean suppressWater
	) {
		int maxY = Math.max(minY, maxYExclusive - 1);
		if (coverClass == ESA_MANGROVES) {
			int surface = cachedSurface == Integer.MIN_VALUE
					? this.sampleSurfaceHeight(worldX, worldZ)
					: cachedSurface;
			surface = Mth.clamp(surface, minY, maxY);
			int waterSurface = resolveMangroveWaterSurface(worldX, worldZ, maxY);
			boolean hasWater = waterSurface > surface;
			return new ColumnHeights(surface, waterSurface, hasWater);
		}
		int surface = Mth.clamp(
				cachedSurface == Integer.MIN_VALUE ? waterData.terrainSurface(localX, localZ) : cachedSurface,
				minY,
				maxY
		);
		if (suppressWater) {
			return new ColumnHeights(surface, surface, false);
		}
		surface = Mth.clamp(waterData.terrainSurface(localX, localZ), minY, maxY);
		int waterSurface = Mth.clamp(waterData.waterSurface(localX, localZ), minY, maxY);
		boolean hasWater = waterData.hasWater(localX, localZ);
		if (!hasWater) {
			return new ColumnHeights(surface, surface, false);
		}
		return new ColumnHeights(surface, waterSurface, true);
	}

	private ColumnHeights resolveFastColumnHeights(int worldX, int worldZ, int minY, int maxYExclusive, int coverClass) {
		int maxY = Math.max(minY, maxYExclusive - 1);
		if (coverClass == ESA_MANGROVES) {
			int surface = Mth.clamp(this.sampleSurfaceHeight(worldX, worldZ), minY, maxY);
			int waterSurface = resolveMangroveWaterSurface(worldX, worldZ, maxY);
			boolean hasWater = waterSurface > surface;
			return new ColumnHeights(surface, waterSurface, hasWater);
		}
		int sampledSurface = Mth.clamp(this.sampleSurfaceHeight(worldX, worldZ), minY, maxY);
		int sampledSlope = sampleSlopeDiff(worldX, worldZ, sampledSurface);
		int effectiveCoverClass = resolveEffectiveCoverClassForTerrain(
				worldX,
				worldZ,
				coverClass,
				sampledSurface,
				sampledSlope
		);
		boolean suppressWater = coverClass == ESA_WATER && effectiveCoverClass != coverClass;
		if (suppressWater) {
			int repairedSurface = repairAnomalousSurfaceHeight(worldX, worldZ, sampledSurface, effectiveCoverClass, minY, maxY);
			return new ColumnHeights(repairedSurface, repairedSurface, false);
		}
		WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(worldX, worldZ);
		int surface = Mth.clamp(column.terrainSurface(), minY, maxY);
		surface = repairAnomalousSurfaceHeight(worldX, worldZ, surface, effectiveCoverClass, minY, maxY);
		int waterSurface = Mth.clamp(column.waterSurface(), minY, maxY);
		if (!column.hasWater()) {
			return new ColumnHeights(surface, surface, false);
		}
		if (surface >= waterSurface) {
			surface = Math.max(minY, waterSurface - 1);
		}
		return new ColumnHeights(surface, waterSurface, true);
	}

	private int resolveMangroveWaterSurface(int worldX, int worldZ, int maxY) {
		long seed = seedFromCoords(worldX, 1, worldZ) ^ 0x9E3779B97F4A7C15L;
		Random columnRandom = new Random(seed);
		int offset = 1 + columnRandom.nextInt(3);
		int waterTop = Math.min(this.seaLevel, maxY);
		return Math.min(waterTop, this.seaLevel - offset);
	}

	private static long seedFromCoords(int x, int y, int z) {
		long seed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
		seed = seed * seed * 42317861L + seed * 11L;
		return seed >> 16;
	}

	private void applySurface(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			int worldX,
			int worldZ,
			int surface,
			int minY,
			boolean underwater,
			Holder<Biome> biome,
			int slopeDiff,
			int convexity,
			int coverClass
	) {
		if (surface < minY) {
			return;
		}
		SurfacePalette palette = selectSurfacePalette(biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, coverClass);
		if (palette == null) {
			return;
		}
		if (!underwater && biome.is(BiomeTags.IS_BADLANDS) && slopeDiff >= BADLANDS_BAND_SLOPE_DIFF) {
			applyBadlandsBands(chunk, cursor, worldX, worldZ, surface, minY, palette);
			return;
		}
		@NonNull BlockState top = underwater ? palette.underwaterTop() : palette.top();
		@NonNull BlockState filler = palette.filler();
		int depth = palette.depth();
		int bottom = Math.max(minY, surface - depth + 1);

		for (int y = surface; y >= bottom; y--) {
			cursor.set(worldX, y, worldZ);
			chunk.setBlockState(cursor, y == surface ? top : filler);
		}
	}

	private static void applyBadlandsBands(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			int worldX,
			int worldZ,
			int surface,
			int minY,
			SurfacePalette palette
	) {
		int depth = Math.max(palette.depth(), BADLANDS_BAND_DEPTH);
		int bottom = Math.max(minY, surface - depth + 1);
		int offset = badlandsBandOffset(worldX, worldZ);
		@NonNull BlockState top = palette.top();
		for (int y = surface; y >= bottom; y--) {
			cursor.set(worldX, y, worldZ);
			@NonNull BlockState state = y == surface ? top : badlandsBand(y, offset);
			chunk.setBlockState(cursor, state);
		}
	}

	private static int badlandsBandOffset(int worldX, int worldZ) {
		int cellX = Math.floorDiv(worldX, BADLANDS_BAND_OFFSET_CELL);
		int cellZ = Math.floorDiv(worldZ, BADLANDS_BAND_OFFSET_CELL);
		long seed = seedFromCoords(cellX, 2, cellZ) ^ 0x2E2B9E9B4A7C15L;
		int range = BADLANDS_BAND_HEIGHT * BADLANDS_BANDS.length;
		return Math.floorMod((int) seed, range);
	}

	private static @NonNull BlockState badlandsBand(int y, int offset) {
		int index = Math.floorDiv(y + offset, BADLANDS_BAND_HEIGHT);
		int bandIndex = Math.floorMod(index, BADLANDS_BANDS.length);
		return BADLANDS_BANDS[bandIndex];
	}

	public @NonNull BlockState resolveLodSurfaceBlock(int worldX, int worldZ, int surface, boolean underwater) {
		if (this.biomeSource instanceof EarthBiomeSource earthBiomeSource) {
			Holder<Biome> biome = earthBiomeSource.getBiomeAtBlock(worldX, worldZ);
			return resolveLodSurface(biome, worldX, worldZ, surface, underwater).top();
		}
		return STONE_STATE;
	}

	public @NonNull BlockState resolveLodFillerBlock(int worldX, int worldZ, int surface, boolean underwater) {
		if (this.biomeSource instanceof EarthBiomeSource earthBiomeSource) {
			Holder<Biome> biome = earthBiomeSource.getBiomeAtBlock(worldX, worldZ);
			return resolveLodSurface(biome, worldX, worldZ, surface, underwater).filler();
		}
		return STONE_STATE;
	}

	public @NonNull LodSurface resolveLodSurface(Holder<Biome> biome, int worldX, int worldZ, int surface, boolean underwater) {
		int coverClass = sampleCoverClass(worldX, worldZ);
		return resolveLodSurface(biome, worldX, worldZ, surface, underwater, coverClass);
	}

	public @NonNull LodSurface resolveLodSurface(
			Holder<Biome> biome,
			int worldX,
			int worldZ,
			int surface,
			boolean underwater,
			int coverClass
	) {
		int slopeDiff = sampleSlopeDiff(worldX, worldZ, surface);
		int effectiveCoverClass = resolveEffectiveCoverClassForTerrain(
				worldX,
				worldZ,
				coverClass,
				surface,
				slopeDiff
		);
		SurfacePalette palette = selectSurfacePalette(biome, worldX, worldZ, surface, underwater, effectiveCoverClass);
		if (palette == null) {
			return new LodSurface(STONE_STATE, STONE_STATE);
		}
		BlockState top = underwater ? palette.underwaterTop() : palette.top();
		return new LodSurface(top, palette.filler());
	}

	public void prefetchForChunk(int chunkX, int chunkZ) {
		TellusWorldgenSources.prefetchForChunk(new ChunkPos(chunkX, chunkZ), this.settings);
	}

	private WaterSurfaceResolver.WaterChunkData resolveChunkWaterData(ChunkPos pos) {
		WaterChunkCache cache = this.waterChunkCache.get();
		if (cache.matches(pos)) {
			return cache.data();
		}
		WaterSurfaceResolver.WaterChunkData data = this.waterResolver.resolveChunkWaterData(pos.x, pos.z);
		cache.update(pos, data);
		return data;
	}

	private TellusVanillaCarverRunner getTellusCarverRunner(RegistryAccess registryAccess) {
		TellusVanillaCarverRunner cached = this.tellusCarverRunner;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			cached = this.tellusCarverRunner;
			if (cached == null) {
				Registry<Block> blockRegistry = registryAccess.lookupOrThrow(Registries.BLOCK);
				Registry<NoiseGeneratorSettings> noiseSettings = registryAccess.lookupOrThrow(Registries.NOISE_SETTINGS);
				@NonNull Holder<NoiseGeneratorSettings> adaptedCarverNoiseSettings = Objects.requireNonNull(
						TellusNoiseSettingsAdapter.adaptToTellusHeight(
								noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD),
								this.minY,
								this.height,
								this.seaLevel
						),
						"adaptedCarverNoiseSettings"
				);
				cached = new TellusVanillaCarverRunner(
						this.biomeSource,
						blockRegistry,
						adaptedCarverNoiseSettings,
						this.minY,
						this.height
				);
				this.tellusCarverRunner = cached;
			}
			return cached;
		}
	}

	public int sampleCoverClass(int worldX, int worldZ) {
		return LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
	}

	private int resolveEffectiveCoverClassForTerrain(
			int worldX,
			int worldZ,
			int coverClass,
			int surface,
			int slopeDiff
	) {
		if (!shouldSuppressSteepWater(worldX, worldZ, coverClass, surface, slopeDiff)) {
			return coverClass;
		}
		return findNearestLandCoverClass(worldX, worldZ);
	}

	private boolean shouldSuppressSteepWater(
			int worldX,
			int worldZ,
			int coverClass,
			int surface,
			int slopeDiff
	) {
		if (coverClass != ESA_WATER) {
			return false;
		}
		int heightAboveSea = surface - this.seaLevel;
		if (heightAboveSea < STEEP_WATER_MIN_HEIGHT_ABOVE_SEA) {
			return false;
		}
		if (slopeDiff < STEEP_WATER_SLOPE_THRESHOLD) {
			return false;
		}
		TellusLandMaskSource.LandMaskSample landMask = LAND_MASK_SOURCE.sampleLandMask(
				worldX,
				worldZ,
				this.settings.worldScale()
		);
		if (landMask.known() && !landMask.land()) {
			return false;
		}
		return true;
	}

	private int findNearestLandCoverClass(int worldX, int worldZ) {
		for (int radius = 1; radius <= STEEP_WATER_COVER_SEARCH_RADIUS; radius++) {
			int minX = worldX - radius;
			int maxX = worldX + radius;
			int minZ = worldZ - radius;
			int maxZ = worldZ + radius;
			for (int x = minX; x <= maxX; x++) {
				int north = sampleCoverClass(x, minZ);
				if (isValidReplacementLandCover(north)) {
					return north;
				}
				int south = sampleCoverClass(x, maxZ);
				if (isValidReplacementLandCover(south)) {
					return south;
				}
			}
			for (int z = minZ + 1; z < maxZ; z++) {
				int west = sampleCoverClass(minX, z);
				if (isValidReplacementLandCover(west)) {
					return west;
				}
				int east = sampleCoverClass(maxX, z);
				if (isValidReplacementLandCover(east)) {
					return east;
				}
			}
		}
		return ESA_BARE_SPARSE;
	}

	private static boolean isValidReplacementLandCover(int coverClass) {
		return coverClass != ESA_NO_DATA && !isWaterLikeCoverClass(coverClass);
	}

	private static boolean isWaterLikeCoverClass(int coverClass) {
		return coverClass == ESA_WATER || coverClass == ESA_MANGROVES || coverClass == ESA_NO_DATA;
	}

	public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ) {
		int coverClass = sampleCoverClass(worldX, worldZ);
		return resolveLodWaterColumn(worldX, worldZ, coverClass);
	}

	public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ, int coverClass) {
		// LODs use a lightweight water approximation to avoid the full resolver cost.
		int surface = sampleSurfaceHeight(worldX, worldZ);
		int slopeDiff = sampleSlopeDiff(worldX, worldZ, surface);
		int effectiveCoverClass = resolveEffectiveCoverClassForTerrain(
				worldX,
				worldZ,
				coverClass,
				surface,
				slopeDiff
		);
		boolean suppressWater = coverClass == ESA_WATER && effectiveCoverClass != coverClass;
		surface = repairAnomalousSurfaceHeight(worldX, worldZ, surface, effectiveCoverClass, this.minY, this.minY + this.height - 1);
		if (suppressWater) {
			return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
		}
		boolean noData = effectiveCoverClass == ESA_NO_DATA;
		boolean hasWater = effectiveCoverClass == ESA_WATER
				|| effectiveCoverClass == ESA_MANGROVES
				|| (noData && surface <= this.seaLevel);
		if (!hasWater) {
			return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
		}
		int waterSurface = Math.max(surface + 1, this.seaLevel);
		boolean isOcean = noData && surface <= this.seaLevel;
		if (!isOcean) {
			int targetSurface = waterSurface - Math.max(1, LOD_MIN_WATER_DEPTH);
			if (surface > targetSurface) {
				surface = targetSurface;
			}
			if (surface >= waterSurface) {
				surface = waterSurface - 1;
			}
		}
		return new WaterSurfaceResolver.WaterColumnData(true, isOcean, surface, waterSurface);
	}

	public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(
			int worldX,
			int worldZ,
			int coverClass,
			boolean useDetailedResolver
	) {
		if (!useDetailedResolver) {
			return resolveLodWaterColumn(worldX, worldZ, coverClass);
		}
		int surface = sampleSurfaceHeight(worldX, worldZ);
		int slopeDiff = sampleSlopeDiff(worldX, worldZ, surface);
		int effectiveCoverClass = resolveEffectiveCoverClassForTerrain(
				worldX,
				worldZ,
				coverClass,
				surface,
				slopeDiff
		);
		surface = repairAnomalousSurfaceHeight(worldX, worldZ, surface, effectiveCoverClass, this.minY, this.minY + this.height - 1);
		boolean suppressWater = coverClass == ESA_WATER && effectiveCoverClass != coverClass;
		if (suppressWater) {
			return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
		}
		if (effectiveCoverClass == ESA_MANGROVES) {
			int waterSurface = resolveMangroveWaterSurface(worldX, worldZ, this.seaLevel);
			boolean hasWater = waterSurface > surface;
			return new WaterSurfaceResolver.WaterColumnData(hasWater, false, surface, waterSurface);
		}
		if (effectiveCoverClass != ESA_WATER && effectiveCoverClass != ESA_NO_DATA) {
			return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
		}
		return this.waterResolver.resolveColumnData(worldX, worldZ, coverClass);
	}

	public void prefetchLodWaterRegions(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
		this.waterResolver.prefetchRegionsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
	}

	public @NonNull BlockState resolveBadlandsBandBlock(int worldX, int worldZ, int y) {
		int offset = badlandsBandOffset(worldX, worldZ);
		return badlandsBand(y, offset);
	}

	private SurfacePalette selectSurfacePalette(
			Holder<Biome> biome,
			int worldX,
			int worldZ,
			int surface,
			boolean underwater,
			int coverClass
	) {
		SurfacePalette palette = selectBaseSurfacePalette(biome, worldX, worldZ, surface, coverClass);
		if (palette == null) {
			return null;
		}
		int slopeDiff = sampleSlopeDiff(worldX, worldZ, surface);
		int convexity = sampleConvexity(worldX, worldZ, surface);
		return applySlopeSurfaceOverride(palette, underwater, slopeDiff, convexity, coverClass, biome, worldX, worldZ, surface);
	}

	private SurfacePalette selectSurfacePalette(
			Holder<Biome> biome,
			int worldX,
			int worldZ,
			int surface,
			boolean underwater,
			int slopeDiff,
			int convexity,
			int coverClass
	) {
		SurfacePalette palette = selectBaseSurfacePalette(biome, worldX, worldZ, surface, coverClass);
		if (palette == null) {
			return null;
		}
		return applySlopeSurfaceOverride(palette, underwater, slopeDiff, convexity, coverClass, biome, worldX, worldZ, surface);
	}

	private SurfacePalette applySlopeSurfaceOverride(
			SurfacePalette palette,
			boolean underwater,
			int slopeDiff,
			int convexity,
			int coverClass,
			Holder<Biome> biome,
			int worldX,
			int worldZ,
			int surface
	) {
		if (underwater) {
			return palette;
		}
		int heightAboveSea = surface - this.seaLevel;
		float treeTransitionWeight = sampleMountainTreeTransitionWeight(worldX, worldZ, coverClass, heightAboveSea);
		MountainSurfaceContext mountain = evaluateMountainSurfaceContext(
				biome,
				coverClass,
				heightAboveSea,
				slopeDiff,
				convexity,
				treeTransitionWeight,
				worldX,
				worldZ
		);
		if (coverClass == ESA_SNOW_ICE) {
			if (mountain.retainSnow()) {
				return isSnowPalette(palette) ? palette : SurfacePalette.snowy();
			}
			if (treeTransitionWeight >= MOUNTAIN_TREE_TRANSITION_MIN_WEIGHT
					&& mountain.darkRockScore() >= MOUNTAIN_DARK_BLEND_THRESHOLD) {
				return selectMountainTransitionPalette(worldX, worldZ, treeTransitionWeight);
			}
			if (mountain.darkRockScore() >= MOUNTAIN_DARK_MASS_THRESHOLD) {
				return SurfacePalette.deepslateMass();
			}
			if (mountain.darkRockScore() >= MOUNTAIN_DARK_BLEND_THRESHOLD) {
				return SurfacePalette.deepslateBlend();
			}
			return slopeDiff >= CLIFF_SLOPE_DIFF ? SurfacePalette.scree() : SurfacePalette.stonyPeaks();
		}

		if (isMountainRockyCover(coverClass, heightAboveSea)) {
			if (treeTransitionWeight >= MOUNTAIN_TREE_TRANSITION_MIN_WEIGHT
					&& mountain.darkRockScore() >= MOUNTAIN_DARK_BLEND_THRESHOLD) {
				return selectMountainTransitionPalette(worldX, worldZ, treeTransitionWeight);
			}
			if (mountain.darkRockScore() >= MOUNTAIN_DARK_MASS_THRESHOLD) {
				return SurfacePalette.deepslateMass();
			}
			if (mountain.darkRockScore() >= MOUNTAIN_DARK_BLEND_THRESHOLD) {
				return SurfacePalette.deepslateBlend();
			}
			if (slopeDiff >= TALUS_SLOPE_DIFF) {
				int talusChance = 30 + Math.max(0, slopeDiff - TALUS_SLOPE_DIFF) * 12;
				if (coverClass == ESA_BARE_SPARSE) {
					talusChance += 20;
				}
				talusChance = Mth.clamp(talusChance, 0, 85);
				if (surfaceVariant(worldX, worldZ, 91) < talusChance) {
					return slopeDiff >= CLIFF_SLOPE_DIFF ? SurfacePalette.scree() : SurfacePalette.talus();
				}
			}
		}

		if (!isSoilPalette(palette) || coverClass == ESA_TREE_COVER) {
			return palette;
		}
		if (slopeDiff >= STONY_SLOPE_DIFF) {
			return SurfacePalette.stonyPeaks();
		}
		return palette;
	}

	private SurfacePalette selectMountainTransitionPalette(int worldX, int worldZ, float transitionWeight) {
		int roll = surfaceVariant(worldX, worldZ, 149);
		if (transitionWeight >= 0.6f) {
			if (roll < 45) {
				return SurfacePalette.transitionAndesite();
			}
			if (roll < 80) {
				return SurfacePalette.transitionTuff();
			}
			return SurfacePalette.transitionDiorite();
		}
		if (roll < 50) {
			return SurfacePalette.transitionAndesite();
		}
		if (roll < 75) {
			return SurfacePalette.stonyPeaks();
		}
		if (roll < 90) {
			return SurfacePalette.transitionTuff();
		}
		return SurfacePalette.transitionDiorite();
	}

	private float sampleMountainTreeTransitionWeight(int worldX, int worldZ, int coverClass, int heightAboveSea) {
		if (isTreeCoverClass(coverClass)) {
			return 1.0f;
		}
		if (!isMountainRockyCover(coverClass, heightAboveSea)) {
			return 0.0f;
		}
		float weightedHits = 0.0f;
		float totalWeight = 0.0f;
		weightedHits += sampleTreeTransitionRing(worldX, worldZ, MOUNTAIN_TREE_TRANSITION_NEAR, 0.46f);
		totalWeight += 0.46f * 4.0f;
		weightedHits += sampleTreeTransitionRing(worldX, worldZ, MOUNTAIN_TREE_TRANSITION_MID, 0.34f);
		totalWeight += 0.34f * 4.0f;
		weightedHits += sampleTreeTransitionRing(worldX, worldZ, MOUNTAIN_TREE_TRANSITION_FAR, 0.20f);
		totalWeight += 0.20f * 4.0f;
		if (totalWeight <= 0.0f) {
			return 0.0f;
		}
		return Mth.clamp(weightedHits / totalWeight, 0.0f, 1.0f);
	}

	private float sampleTreeTransitionRing(int worldX, int worldZ, int distance, float weightPerSample) {
		float hits = 0.0f;
		hits += isTreeCoverClass(sampleCoverClass(worldX + distance, worldZ)) ? weightPerSample : 0.0f;
		hits += isTreeCoverClass(sampleCoverClass(worldX - distance, worldZ)) ? weightPerSample : 0.0f;
		hits += isTreeCoverClass(sampleCoverClass(worldX, worldZ + distance)) ? weightPerSample : 0.0f;
		hits += isTreeCoverClass(sampleCoverClass(worldX, worldZ - distance)) ? weightPerSample : 0.0f;
		return hits;
	}

	private static boolean isMountainRockyCover(int coverClass, int heightAboveSea) {
		if (coverClass == ESA_NO_DATA) {
			return heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA;
		}
		return coverClass == ESA_SNOW_ICE
				|| coverClass == ESA_BARE_SPARSE
				|| coverClass == ESA_SHRUBLAND
				|| coverClass == ESA_MOSS_LICHEN;
	}

	private static boolean isTreeCoverClass(int coverClass) {
		return coverClass == ESA_TREE_COVER;
	}

	private static boolean isSnowPalette(SurfacePalette palette) {
		return palette.top().is(Blocks.SNOW_BLOCK);
	}

	private boolean shouldRetainMountainSnow(
			int coverClass,
			int heightAboveSea,
			int slopeDiff,
			int convexity,
			int worldX,
			int worldZ
	) {
		if (coverClass != ESA_SNOW_ICE) {
			return false;
		}
		return computeSnowRetentionScore(heightAboveSea, slopeDiff, convexity, worldX, worldZ) >= MOUNTAIN_SNOW_RETENTION_THRESHOLD;
	}

	private MountainSurfaceContext evaluateMountainSurfaceContext(
			Holder<Biome> biome,
			int coverClass,
			int heightAboveSea,
			int slopeDiff,
			int convexity,
			float treeTransitionWeight,
			int worldX,
			int worldZ
	) {
		boolean mountainCover = isMountainRockyCover(coverClass, heightAboveSea);
		int snowRetentionScore = 0;
		boolean retainSnow = false;
		if (coverClass == ESA_SNOW_ICE) {
			snowRetentionScore = computeSnowRetentionScore(heightAboveSea, slopeDiff, convexity, worldX, worldZ);
			retainSnow = snowRetentionScore >= MOUNTAIN_SNOW_RETENTION_THRESHOLD;
		}
		int darkRockScore = computeDarkRockScore(
				biome,
				coverClass,
				mountainCover,
				heightAboveSea,
				slopeDiff,
				convexity,
				treeTransitionWeight,
				snowRetentionScore,
				worldX,
				worldZ
		);
		if (heightAboveSea < MOUNTAIN_DARK_ROCK_MIN_HEIGHT_ABOVE_SEA) {
			darkRockScore = 0;
		}
		if (darkRockScore >= MOUNTAIN_DARK_MASS_THRESHOLD
				&& heightAboveSea < MOUNTAIN_DARK_MASS_MIN_HEIGHT_ABOVE_SEA) {
			darkRockScore = MOUNTAIN_DARK_BLEND_THRESHOLD;
		}
		return new MountainSurfaceContext(retainSnow, darkRockScore);
	}

	private int computeSnowRetentionScore(int heightAboveSea, int slopeDiff, int convexity, int worldX, int worldZ) {
		double breakupMask = sampleLowFrequencyMask(worldX, worldZ, MOUNTAIN_SNOW_BREAKUP_CELL, MOUNTAIN_SNOW_BREAKUP_SALT);
		int score = 56;
		score += Mth.clamp((heightAboveSea - SNOW_EXPOSE_MIN_HEIGHT_ABOVE_SEA) / 2, -20, 32);
		score -= slopeDiff * 11;
		score += Mth.clamp(convexity * 9, -18, 24);
		score += (int) Math.round((breakupMask - 0.5) * 24.0);
		if (heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA) {
			score += 8;
		}
		return Mth.clamp(score, 0, 100);
	}

	private int computeDarkRockScore(
			Holder<Biome> biome,
			int coverClass,
			boolean mountainCover,
			int heightAboveSea,
			int slopeDiff,
			int convexity,
			float treeTransitionWeight,
			int snowRetentionScore,
			int worldX,
			int worldZ
	) {
		if (!mountainCover) {
			return 0;
		}
		double darkMask = sampleLowFrequencyMask(worldX, worldZ, MOUNTAIN_DARK_ROCK_CELL, MOUNTAIN_DARK_ROCK_SALT);
		int ruggedness = slopeDiff + Math.max(0, -convexity);
		int score = -18;
		score += Math.max(0, (heightAboveSea - MOUNTAIN_DARK_ROCK_MIN_HEIGHT_ABOVE_SEA) / 2);
		score += ruggedness * 9;
		score += (int) Math.round((darkMask - 0.5) * 26.0);
		score += Mth.clamp((100 - snowRetentionScore) / 3, 0, 28);
		if (heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA) {
			score += 10;
		}
		score = Math.max(score, biomeDarkRockFloor(biome, coverClass, heightAboveSea, slopeDiff, treeTransitionWeight));
		score -= Math.round(treeTransitionWeight * 30.0f);
		return Mth.clamp(score, 0, 100);
	}

	private static int biomeDarkRockFloor(
			Holder<Biome> biome,
			int coverClass,
			int heightAboveSea,
			int slopeDiff,
			float treeTransitionWeight
	) {
		if (biome.is(Biomes.FROZEN_PEAKS)) {
			int progress = altitudeProgress(
					heightAboveSea,
					FROZEN_PEAKS_DARK_START_HEIGHT_ABOVE_SEA,
					FROZEN_PEAKS_DARK_FULL_HEIGHT_ABOVE_SEA
			);
			if (progress <= 0) {
				return 0;
			}
			int floor = 20 + (progress * 76) / 100;
			if (slopeDiff < STONY_SLOPE_DIFF) {
				floor -= 8;
			}
				if (heightAboveSea >= FROZEN_PEAKS_FORCE_MASS_HEIGHT_ABOVE_SEA && slopeDiff >= STONY_SLOPE_DIFF) {
					floor = Math.max(floor, MOUNTAIN_DARK_MASS_THRESHOLD + 10);
				}
				floor -= Math.round(treeTransitionWeight * 34.0f);
				return Mth.clamp(floor, 0, 100);
			}
			if (biome.is(Biomes.STONY_PEAKS)) {
			int progress = altitudeProgress(
					heightAboveSea,
					STONY_PEAKS_DARK_START_HEIGHT_ABOVE_SEA,
					STONY_PEAKS_DARK_FULL_HEIGHT_ABOVE_SEA
			);
			if (progress <= 0) {
				return 0;
			}
			int floor = 16 + (progress * 72) / 100;
			if (slopeDiff < STONY_SLOPE_DIFF) {
				floor -= 8;
			}
				if (heightAboveSea >= STONY_PEAKS_FORCE_MASS_HEIGHT_ABOVE_SEA && slopeDiff >= STONY_SLOPE_DIFF) {
					floor = Math.max(floor, MOUNTAIN_DARK_MASS_THRESHOLD + 4);
				}
				floor -= Math.round(treeTransitionWeight * 30.0f);
				return Mth.clamp(floor, 0, 100);
			}
			if (coverClass == ESA_BARE_SPARSE
					|| coverClass == ESA_SNOW_ICE
				|| coverClass == ESA_MOSS_LICHEN
				|| coverClass == ESA_SHRUBLAND) {
			int progress = altitudeProgress(
					heightAboveSea,
					GENERIC_MOUNTAIN_DARK_START_HEIGHT_ABOVE_SEA,
					GENERIC_MOUNTAIN_DARK_FULL_HEIGHT_ABOVE_SEA
			);
			if (progress <= 0) {
				return 0;
			}
			int floor = 12 + (progress * 74) / 100;
			if (slopeDiff < STONY_SLOPE_DIFF) {
				floor -= 6;
			}
				if (heightAboveSea >= GENERIC_MOUNTAIN_FORCE_MASS_HEIGHT_ABOVE_SEA
						&& slopeDiff >= GENERIC_MOUNTAIN_FORCE_MASS_MIN_SLOPE) {
					floor = Math.max(floor, MOUNTAIN_DARK_MASS_THRESHOLD + 2);
				}
				floor -= Math.round(treeTransitionWeight * 26.0f);
				return Mth.clamp(floor, 0, 100);
			}
		return 0;
	}

	private static int altitudeProgress(int heightAboveSea, int startHeight, int fullHeight) {
		if (heightAboveSea <= startHeight) {
			return 0;
		}
		if (heightAboveSea >= fullHeight) {
			return 100;
		}
		int span = Math.max(1, fullHeight - startHeight);
		return ((heightAboveSea - startHeight) * 100) / span;
	}

	private double sampleLowFrequencyMask(int worldX, int worldZ, int cellSize, long salt) {
		int cellX = Math.floorDiv(worldX, cellSize);
		int cellZ = Math.floorDiv(worldZ, cellSize);
		double fracX = (double) Math.floorMod(worldX, cellSize) / (double) cellSize;
		double fracZ = (double) Math.floorMod(worldZ, cellSize) / (double) cellSize;
		double v00 = hashedCellNoise(cellX, cellZ, salt);
		double v10 = hashedCellNoise(cellX + 1, cellZ, salt);
		double v01 = hashedCellNoise(cellX, cellZ + 1, salt);
		double v11 = hashedCellNoise(cellX + 1, cellZ + 1, salt);
		double i0 = Mth.lerp(fracX, v00, v10);
		double i1 = Mth.lerp(fracX, v01, v11);
		return Mth.lerp(fracZ, i0, i1);
	}

	private double hashedCellNoise(int cellX, int cellZ, long salt) {
		long seed = this.worldSeed
				^ salt
				^ (long) cellX * 341873128712L
				^ (long) cellZ * 132897987541L;
		seed ^= (seed >>> 33);
		seed *= 0xff51afd7ed558ccdL;
		seed ^= (seed >>> 33);
		seed *= 0xc4ceb9fe1a85ec53L;
		seed ^= (seed >>> 33);
		long bits = (seed >>> 11) & ((1L << 53) - 1);
		return bits / (double) (1L << 53);
	}

	private static int sampleSlopeDiffCached(int[] heightGrid, int gridSize, int step, int centerIndex, int surface) {
		int east = heightGrid[centerIndex + step];
		int west = heightGrid[centerIndex - step];
		int north = heightGrid[centerIndex - step * gridSize];
		int south = heightGrid[centerIndex + step * gridSize];

		return Math.max(
				Math.max(Math.abs(east - surface), Math.abs(west - surface)),
				Math.max(Math.abs(north - surface), Math.abs(south - surface))
		);
	}

	private static int sampleConvexityCached(int[] heightGrid, int gridSize, int step, int centerIndex, int surface) {
		int east = heightGrid[centerIndex + step];
		int west = heightGrid[centerIndex - step];
		int north = heightGrid[centerIndex - step * gridSize];
		int south = heightGrid[centerIndex + step * gridSize];
		int neighborAverage = (east + west + north + south) / 4;
		return neighborAverage - surface;
	}

	private static boolean isSoilPalette(SurfacePalette palette) {
		BlockState filler = palette.filler();
		return filler.is(BlockTags.DIRT) || filler.is(Blocks.MUD);
	}

	private SurfacePalette selectBaseSurfacePalette(
			Holder<Biome> biome,
			int worldX,
			int worldZ,
			int surface,
			int coverClass
	) {
		if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
			return oceanFloorPalette(worldX, worldZ);
		}
		if (biome.is(BiomeTags.IS_BEACH)) {
			return SurfacePalette.beach();
		}
		if (biome.is(BiomeTags.IS_BADLANDS)) {
			return SurfacePalette.badlands();
		}
		if (biome.is(Biomes.DESERT)) {
			return SurfacePalette.desert();
		}
		if (biome.is(Biomes.MANGROVE_SWAMP)) {
			return SurfacePalette.mangrove();
		}
		if (biome.is(Biomes.SWAMP)) {
			return SurfacePalette.swamp();
		}
		if (biome.is(Biomes.STONY_PEAKS)) {
			return SurfacePalette.stonyPeaks();
		}
		if (biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)) {
			return SurfacePalette.gravelly();
		}
		if (biome.is(Biomes.SNOWY_PLAINS)
				|| biome.is(Biomes.SNOWY_TAIGA)
				|| biome.is(Biomes.SNOWY_SLOPES)
				|| biome.is(Biomes.GROVE)
				|| biome.is(Biomes.ICE_SPIKES)
				|| biome.is(Biomes.FROZEN_PEAKS)) {
			return SurfacePalette.snowy();
		}
		byte climateGroup = climateGroupForBiome(biome);
		SurfacePalette coverPalette = coverDrivenSurfacePalette(coverClass, climateGroup, worldX, worldZ, surface);
		if (isPlainsOrMeadowBiome(biome) && isGravellyPalette(coverPalette) && surfaceVariant(worldX, worldZ, 211) < 50) {
			return SurfacePalette.defaultOverworld();
		}
		return coverPalette;
	}

	private SurfacePalette coverDrivenSurfacePalette(
			int coverClass,
			byte climateGroup,
			int worldX,
			int worldZ,
			int surface
	) {
		int roll = surfaceVariant(worldX, worldZ, 23);
		int heightAboveSea = surface - this.seaLevel;
		return switch (coverClass) {
			case ESA_TREE_COVER -> {
				if (climateGroup == CLIMATE_COLD || climateGroup == CLIMATE_POLAR) {
					yield roll < 65 ? SurfacePalette.podzolic() : SurfacePalette.defaultOverworld();
				}
				if (climateGroup == CLIMATE_ARID) {
					yield roll < 70 ? SurfacePalette.steppe() : SurfacePalette.defaultOverworld();
				}
				if (climateGroup == CLIMATE_TROPICAL) {
					yield roll < 35 ? SurfacePalette.rooted() : SurfacePalette.defaultOverworld();
				}
				yield roll < 20 ? SurfacePalette.rooted() : SurfacePalette.defaultOverworld();
			}
			case ESA_SHRUBLAND -> {
				if (heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 70) {
					yield SurfacePalette.gravelly();
				}
				if (climateGroup == CLIMATE_ARID) {
					yield SurfacePalette.steppe();
				}
				if (climateGroup == CLIMATE_COLD || climateGroup == CLIMATE_POLAR) {
					yield SurfacePalette.podzolic();
				}
				yield roll < 40 ? SurfacePalette.steppe() : SurfacePalette.defaultOverworld();
			}
			case ESA_GRASSLAND -> {
				if (heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 35) {
					yield SurfacePalette.gravelly();
				}
				if (climateGroup == CLIMATE_ARID) {
					yield roll < 65 ? SurfacePalette.steppe() : SurfacePalette.defaultOverworld();
				}
				if (climateGroup == CLIMATE_COLD || climateGroup == CLIMATE_POLAR) {
					yield roll < 40 ? SurfacePalette.podzolic() : SurfacePalette.defaultOverworld();
				}
				yield SurfacePalette.defaultOverworld();
			}
			case ESA_CROPLAND -> {
				if (climateGroup == CLIMATE_ARID) {
					yield SurfacePalette.steppe();
				}
				yield roll < 65 ? SurfacePalette.rooted() : SurfacePalette.defaultOverworld();
			}
			case ESA_BUILT_UP -> roll < 45 ? SurfacePalette.gravelly() : SurfacePalette.stonyPeaks();
			case ESA_BARE_SPARSE -> {
				if (heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA) {
					yield SurfacePalette.stonyPeaks();
				}
				if (climateGroup == CLIMATE_ARID) {
					yield roll < 55 ? SurfacePalette.desert() : SurfacePalette.badlands();
				}
				if (climateGroup == CLIMATE_COLD || climateGroup == CLIMATE_POLAR) {
					yield roll < 70 ? SurfacePalette.gravelly() : SurfacePalette.stonyPeaks();
				}
				if (heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA && roll < 55) {
					yield SurfacePalette.gravelly();
				}
				yield SurfacePalette.beach();
			}
			case ESA_HERBACEOUS_WETLAND -> roll < 40 ? SurfacePalette.swamp() : SurfacePalette.wetland();
			case ESA_MANGROVES -> SurfacePalette.mangrove();
			case ESA_MOSS_LICHEN -> {
				if (heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 60) {
					yield SurfacePalette.stonyPeaks();
				}
				yield roll < 35 ? SurfacePalette.mossy() : SurfacePalette.defaultOverworld();
			}
			case ESA_SNOW_ICE -> SurfacePalette.snowy();
			default -> {
				if (climateGroup == CLIMATE_COLD || climateGroup == CLIMATE_POLAR) {
					yield SurfacePalette.podzolic();
				}
				yield SurfacePalette.defaultOverworld();
			}
		};
	}

	private static byte climateGroupForBiome(Holder<Biome> biome) {
		if (biome.is(Biomes.MANGROVE_SWAMP)
				|| biome.is(BiomeTags.IS_JUNGLE)
				|| biome.is(BiomeTags.IS_SAVANNA)) {
			return CLIMATE_TROPICAL;
		}
		if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BADLANDS)) {
			return CLIMATE_ARID;
		}
		if (biome.is(Biomes.FROZEN_PEAKS)
				|| biome.is(Biomes.SNOWY_PLAINS)
				|| biome.is(Biomes.SNOWY_SLOPES)
				|| biome.is(Biomes.ICE_SPIKES)) {
			return CLIMATE_POLAR;
		}
		if (biome.is(BiomeTags.IS_TAIGA)
				|| biome.is(Biomes.GROVE)
				|| biome.is(Biomes.JAGGED_PEAKS)
				|| biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)) {
			return CLIMATE_COLD;
		}
		if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
			return CLIMATE_UNKNOWN;
		}
		return CLIMATE_TEMPERATE;
	}

	private static boolean isPlainsOrMeadowBiome(Holder<Biome> biome) {
		return biome.is(Biomes.PLAINS) || biome.is(Biomes.MEADOW);
	}

	private static boolean isGravellyPalette(SurfacePalette palette) {
		return palette.top().is(Blocks.GRAVEL);
	}

	private static int surfaceVariant(int worldX, int worldZ, int salt) {
		long seed = seedFromCoords(worldX, salt, worldZ) ^ 0x27D4EB2D1F123BB5L;
		return Math.floorMod((int) (seed ^ (seed >>> 32)), 100);
	}

	private SurfacePalette oceanFloorPalette(int worldX, int worldZ) {
		long seed = seedFromCoords(worldX, 0, worldZ) ^ 0x6F1D5E3A2B9C4D1EL;
		Random random = new Random(seed);
		int roll = random.nextInt(100);
		if (roll < 10) {
			return SurfacePalette.ocean(GRAVEL_STATE);
		}
		if (roll < 15) {
			return SurfacePalette.ocean(CLAY_STATE);
		}
		return SurfacePalette.ocean(SAND_STATE);
	}

	private static BiomeGenerationSettings generationSettingsForBiome(Holder<Biome> biome, EarthGeneratorSettings settings) {
		boolean keepTrees = biome.is(Biomes.MANGROVE_SWAMP);
		int flags = geologyFlags(settings, keepTrees);
		BiomeSettingsKey key = new BiomeSettingsKey(biome, flags);
		return FILTERED_SETTINGS.computeIfAbsent(key, cached -> filterGenerationSettings(biome, settings, keepTrees));
	}

	private static BiomeGenerationSettings filterGenerationSettings(
			Holder<Biome> biome,
			EarthGeneratorSettings settings,
			boolean keepTrees
	) {
		BiomeGenerationSettings original = biome.value().getGenerationSettings();
		BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
		for (Holder<ConfiguredWorldCarver<?>> carver : original.getCarvers()) {
			Holder<ConfiguredWorldCarver<?>> safeCarver = Objects.requireNonNull(carver, "carver");
			if (shouldKeepCarver(safeCarver, settings)) {
				builder.addCarver(safeCarver);
			}
		}
		List<HolderSet<PlacedFeature>> features = original.features();
		for (int step = 0; step < features.size(); step++) {
			for (Holder<PlacedFeature> feature : features.get(step)) {
				Holder<PlacedFeature> safeFeature = Objects.requireNonNull(feature, "feature");
				if (!keepTrees && isTreeFeature(safeFeature.value())) {
					continue;
				}
				if (!shouldKeepFeature(safeFeature, settings)) {
					continue;
				}
				builder.addFeature(step, safeFeature);
			}
		}
		return builder.build();
	}

	private static int geologyFlags(EarthGeneratorSettings settings, boolean keepTrees) {
		int flags = 0;
		if (settings.caveGeneration()) {
			flags |= 1 << 0;
		}
		if (settings.oreDistribution()) {
			flags |= 1 << 1;
		}
		if (settings.lavaPools()) {
			flags |= 1 << 2;
		}
		if (settings.deepDark()) {
			flags |= 1 << 3;
		}
		if (settings.geodes()) {
			flags |= 1 << 4;
		}
		if (keepTrees) {
			flags |= 1 << 5;
		}
		return flags;
	}

	private static boolean shouldKeepCarver(Holder<ConfiguredWorldCarver<?>> carver, EarthGeneratorSettings settings) {
		return carver.unwrapKey()
				.map(ResourceKey::identifier)
				.map(id -> shouldKeepCarverId(id.getPath(), settings))
				.orElse(true);
	}

	private static boolean shouldKeepCarverId(String path, EarthGeneratorSettings settings) {
		if (!settings.caveGeneration()
				&& (path.equals("cave") || path.equals("cave_extra_underground") || path.equals("canyon"))) {
			return false;
		}
		return true;
	}

	private static boolean shouldKeepFeature(Holder<PlacedFeature> feature, EarthGeneratorSettings settings) {
		return feature.unwrapKey()
				.map(ResourceKey::identifier)
				.map(id -> shouldKeepFeatureId(id.getPath(), settings))
				.orElse(true);
	}

	private static boolean shouldKeepFeatureId(String path, EarthGeneratorSettings settings) {
		if (path.equals("freeze_top_layer") || path.equals("snow_and_freeze")) {
			return false;
		}
		if (!settings.oreDistribution() && path.startsWith("ore_")) {
			return false;
		}
		if (!settings.geodes() && path.contains("geode")) {
			return false;
		}
		if (!settings.deepDark() && (path.contains("sculk") || path.contains("deep_dark"))) {
			return false;
		}
		if (!settings.caveGeneration() && (path.contains("dripstone") || path.startsWith("spring_water"))) {
			return false;
		}
		if (!settings.lavaPools() && (path.startsWith("lake_lava") || path.startsWith("spring_lava"))) {
			return false;
		}
		return true;
	}

	private boolean isStructureSetEnabled(Holder<StructureSet> structureSet) {
		for (StructureSet.StructureSelectionEntry entry : structureSet.value().structures()) {
			if (!isStructureEnabled(entry.structure())) {
				return false;
			}
		}
		return true;
	}

	private boolean isStructureEnabled(Holder<Structure> structure) {
		return structure.unwrapKey()
				.map(ResourceKey::identifier)
				.map(id -> isStructureEnabled(id.getPath()))
				.orElse(true);
	}

	private boolean isStructureEnabled(String path) {
		if (path.startsWith("village")) {
			return this.settings.addVillages();
		}
		if (path.equals("stronghold")) {
			return this.settings.addStrongholds();
		}
		if (path.startsWith("mineshaft")) {
			return this.settings.addMineshafts();
		}
		if (path.equals("igloo")) {
			return this.settings.addIgloos();
		}
		if (path.equals("ocean_monument") || path.equals("monument")) {
			return this.settings.addOceanMonuments();
		}
		if (path.equals("woodland_mansion")) {
			return this.settings.addWoodlandMansions();
		}
		if (path.equals("desert_pyramid") || path.equals("desert_temple")) {
			return this.settings.addDesertTemples();
		}
		if (path.equals("jungle_pyramid") || path.equals("jungle_temple")) {
			return this.settings.addJungleTemples();
		}
		if (path.equals("pillager_outpost")) {
			return this.settings.addPillagerOutposts();
		}
		if (path.startsWith("ruined_portal")) {
			return this.settings.addRuinedPortals();
		}
		if (path.startsWith("shipwreck")) {
			return this.settings.addShipwrecks();
		}
		if (path.startsWith("ocean_ruin")) {
			return this.settings.addOceanRuins();
		}
		if (path.equals("buried_treasure")) {
			return this.settings.addBuriedTreasure();
		}
		if (path.equals("swamp_hut") || path.equals("witch_hut")) {
			return this.settings.addWitchHuts();
		}
		if (path.equals("ancient_city")) {
			return this.settings.addAncientCities();
		}
		if (path.equals("trial_chambers")) {
			return this.settings.addTrialChambers();
		}
		if (path.startsWith("trail_ruins")) {
			return this.settings.addTrailRuins();
		}
		return true;
	}

	private boolean isFrozenPeaksChunk(ChunkPos pos, RandomState randomState) {
		int centerX = pos.getMinBlockX() + 8;
		int centerZ = pos.getMinBlockZ() + 8;
		Holder<Biome> biome = this.biomeSource.getNoiseBiome(
				QuartPos.fromBlock(centerX),
				0,
				QuartPos.fromBlock(centerZ),
				randomState.sampler()
		);
		return biome.is(Biomes.FROZEN_PEAKS);
	}

	private void stripIglooStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
		Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
		@NonNull Structure igloo = Objects.requireNonNull(
				registry.getValueOrThrow(BuiltinStructures.IGLOO),
				"iglooStructure"
		);
		StructureStart start = chunk.getStartForStructure(igloo);
		if (start == null || !start.isValid()) {
			return;
		}
		chunk.setStartForStructure(igloo, StructureStart.INVALID_START);
		Map<Structure, LongSet> references = chunk.getAllReferences();
		if (!references.isEmpty()) {
			Map<Structure, LongSet> updated = new HashMap<>(references.size());
			for (Map.Entry<Structure, LongSet> entry : references.entrySet()) {
				if (entry.getKey() == igloo) {
					continue;
				}
				updated.put(entry.getKey(), entry.getValue());
			}
			chunk.setAllReferences(updated);
		}
	}

	private static List<ConfiguredFeature<?, ?>> treeFeaturesForBiome(Holder<Biome> biome) {
		return TREE_FEATURES.computeIfAbsent(biome, holder -> {
			List<ConfiguredFeature<?, ?>> result = new ArrayList<>();
			for (HolderSet<PlacedFeature> set : holder.value().getGenerationSettings().features()) {
				for (Holder<PlacedFeature> feature : set) {
					PlacedFeature placed = feature.value();
					if (!isTreeFeature(placed)) {
						continue;
					}
					result.add(placed.feature().value());
				}
			}
			return List.copyOf(result);
		});
	}

	private void retargetStrongholdStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
		Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
		@NonNull Structure stronghold = Objects.requireNonNull(
				registry.getValueOrThrow(BuiltinStructures.STRONGHOLD),
				"strongholdStructure"
		);
		StructureStart start = chunk.getStartForStructure(stronghold);
		if (start == null || !start.isValid()) {
			return;
		}
		int chunkMinY = chunk.getMinY();
		int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
		BlockPos center = start.getBoundingBox().getCenter();
		int centerX = center.getX();
		int centerY = center.getY();
		int centerZ = center.getZ();
		WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(center.getX(), center.getZ());
		int terrainSurface = column.terrainSurface();
		boolean inOceanColumn = column.hasWater() && column.isOcean() && column.waterSurface() > terrainSurface;
		boolean tooShallow = centerY > terrainSurface - STRONGHOLD_MIN_FLOOR_CLEARANCE;
		if (!inOceanColumn && !tooShallow) {
			return;
		}
		int minTargetY = Math.max(
				this.minY + STRONGHOLD_MIN_FLOOR_CLEARANCE,
				chunkMinY + STRONGHOLD_MIN_FLOOR_CLEARANCE
		);
		int maxTargetY = Math.min(chunkMaxY - STRONGHOLD_MIN_FLOOR_CLEARANCE, terrainSurface - STRONGHOLD_MIN_FLOOR_CLEARANCE);
		if (maxTargetY <= minTargetY) {
			return;
		}
		long seed = seedFromCoords(centerX, 19, centerZ) ^ this.worldSeed ^ STRONGHOLD_HEIGHT_ADJUST_SALT;
		Random random = new Random(seed);
		int targetDepth = STRONGHOLD_TARGET_MIN_DEPTH + random.nextInt(STRONGHOLD_TARGET_DEPTH_VARIATION + 1);
		int targetCenterY = Mth.clamp(terrainSurface - targetDepth, minTargetY, maxTargetY);
		int offsetY = targetCenterY - centerY;
		if (Math.abs(offsetY) < STRONGHOLD_MIN_SHIFT_DELTA) {
			if (centerY > maxTargetY) {
				offsetY = maxTargetY - centerY;
			} else {
				return;
			}
		}
		List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());
		for (StructurePiece piece : start.getPieces()) {
			piece.move(0, offsetY, 0);
			movedPieces.add(piece);
		}
		StructureStart moved = new StructureStart(
				stronghold,
				start.getChunkPos(),
				start.getReferences(),
				new PiecesContainer(movedPieces)
		);
		chunk.setStartForStructure(stronghold, moved);
	}

	private void retargetMineshaftStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
		Map<Structure, StructureStart> starts = chunk.getAllStarts();
		if (starts.isEmpty()) {
			return;
		}
		Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
		Structure normalMineshaft = Objects.requireNonNull(
				registry.getValueOrThrow(BuiltinStructures.MINESHAFT),
				"normalMineshaft"
		);
		int chunkMinY = chunk.getMinY();
		int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
			for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
				@NonNull Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
				if (structure != normalMineshaft) {
					continue;
				}
			StructureStart start = entry.getValue();
			if (start == null || !start.isValid()) {
				continue;
			}
			BoundingBox box = start.getBoundingBox();
			BlockPos center = box.getCenter();
			int centerX = center.getX();
			int centerY = center.getY();
			int centerZ = center.getZ();
			int surface = sampleSurfaceHeight(centerX, centerZ);
			int minTargetY = Math.max(this.minY + MINESHAFT_MIN_FLOOR_CLEARANCE, chunkMinY + MINESHAFT_MIN_FLOOR_CLEARANCE);
			int maxTargetY = Math.min(chunkMaxY - MINESHAFT_MIN_FLOOR_CLEARANCE, surface - MINESHAFT_MIN_FLOOR_CLEARANCE);
			if (maxTargetY <= minTargetY) {
				continue;
			}
			long seed = seedFromCoords(centerX, 14, centerZ) ^ this.worldSeed ^ MINESHAFT_HEIGHT_ADJUST_SALT;
			Random random = new Random(seed);
			int targetDepth = MINESHAFT_TARGET_MIN_DEPTH + random.nextInt(MINESHAFT_TARGET_DEPTH_VARIATION + 1);
			int targetCenterY = Mth.clamp(surface - targetDepth, minTargetY, maxTargetY);
			int minOffset = minTargetY - centerY;
			int maxOffset = maxTargetY - centerY;
			int offsetY = Mth.clamp(targetCenterY - centerY, minOffset, maxOffset);
			if (Math.abs(offsetY) < MINESHAFT_MIN_SHIFT_DELTA) {
				continue;
			}

			List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());
			for (StructurePiece piece : start.getPieces()) {
				piece.move(0, offsetY, 0);
				movedPieces.add(piece);
			}
			StructureStart moved = new StructureStart(
					structure,
					start.getChunkPos(),
					start.getReferences(),
					new PiecesContainer(movedPieces)
			);
				chunk.setStartForStructure(structure, moved);
			}
		}

	private void retargetTrialChamberStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
		Map<Structure, StructureStart> starts = chunk.getAllStarts();
		if (starts.isEmpty()) {
			return;
		}
		Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
		@NonNull Structure trialChambers = Objects.requireNonNull(
				registry.getValueOrThrow(BuiltinStructures.TRIAL_CHAMBERS),
				"trialChambers"
		);
		int chunkMinY = chunk.getMinY();
		int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
		for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
			@NonNull Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
			if (structure != trialChambers) {
				continue;
			}
			StructureStart start = entry.getValue();
			if (start == null || !start.isValid()) {
				continue;
			}
				BoundingBox box = start.getBoundingBox();
				BlockPos center = box.getCenter();
				int centerX = center.getX();
				int centerY = center.getY();
				int centerZ = center.getZ();
				WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(centerX, centerZ);
				int terrainSurface = column.terrainSurface();
				int requiredTopY = terrainSurface - TRIAL_CHAMBER_MIN_FLOOR_CLEARANCE;
				if (box.maxY() <= requiredTopY) {
					continue;
				}
				int minAllowedOffset = (chunkMinY + TRIAL_CHAMBER_MIN_FLOOR_CLEARANCE) - box.minY();
				int maxAllowedOffset = (chunkMaxY - TRIAL_CHAMBER_MIN_FLOOR_CLEARANCE) - box.maxY();
				if (maxAllowedOffset < minAllowedOffset) {
					continue;
				}
				int minTargetY = Math.max(
						this.minY + TRIAL_CHAMBER_MIN_FLOOR_CLEARANCE,
						chunkMinY + TRIAL_CHAMBER_MIN_FLOOR_CLEARANCE
				);
				int maxTargetY = Math.min(chunkMaxY - TRIAL_CHAMBER_MIN_FLOOR_CLEARANCE, terrainSurface - TRIAL_CHAMBER_MIN_FLOOR_CLEARANCE);
				int offsetY;
				if (maxTargetY > minTargetY) {
					long seed = seedFromCoords(centerX, 17, centerZ) ^ this.worldSeed ^ TRIAL_CHAMBER_HEIGHT_ADJUST_SALT;
					Random random = new Random(seed);
					int targetDepth = TRIAL_CHAMBER_TARGET_MIN_DEPTH + random.nextInt(TRIAL_CHAMBER_TARGET_DEPTH_VARIATION + 1);
					int targetCenterY = Mth.clamp(terrainSurface - targetDepth, minTargetY, maxTargetY);
					offsetY = targetCenterY - centerY;
				} else {
					offsetY = requiredTopY - box.maxY();
				}
				int buryOffset = requiredTopY - box.maxY();
				if (offsetY > buryOffset) {
					offsetY = buryOffset;
				}
				offsetY = Mth.clamp(offsetY, minAllowedOffset, maxAllowedOffset);
				if (offsetY == 0) {
					continue;
				}
				if (Math.abs(offsetY) < TRIAL_CHAMBER_MIN_SHIFT_DELTA && box.maxY() + offsetY <= requiredTopY) {
					continue;
				}
				List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());
				for (StructurePiece piece : start.getPieces()) {
					piece.move(0, offsetY, 0);
					movedPieces.add(piece);
				}
			StructureStart moved = new StructureStart(
					structure,
					start.getChunkPos(),
					start.getReferences(),
					new PiecesContainer(movedPieces)
			);
			chunk.setStartForStructure(structure, moved);
		}
	}

	private static boolean isTreeFeature(PlacedFeature feature) {
		return feature.getFeatures().anyMatch(configured -> {
			Feature<?> type = configured.feature();
			return type == Feature.TREE || type == Feature.FALLEN_TREE;
		});
	}

	private record BiomeSettingsKey(Holder<Biome> biome, int flags) {
	}

	private static final class FilteredStructureLookup implements HolderLookup<StructureSet> {
		private final HolderLookup<StructureSet> delegate;
		private final Predicate<Holder<StructureSet>> predicate;

		private FilteredStructureLookup(HolderLookup<StructureSet> delegate, Predicate<Holder<StructureSet>> predicate) {
			this.delegate = delegate;
			this.predicate = predicate;
		}

		@Override
		public @NonNull Stream<Holder.Reference<StructureSet>> listElements() {
			return Objects.requireNonNull(
					this.delegate.listElements().filter(this.predicate),
					"listElements"
			);
		}

		@Override
		public @NonNull Stream<HolderSet.Named<StructureSet>> listTags() {
			return Objects.requireNonNull(this.delegate.listTags(), "listTags");
		}

		@Override
		public @NonNull Optional<Holder.Reference<StructureSet>> get(@NonNull ResourceKey<StructureSet> key) {
			return Objects.requireNonNull(this.delegate.get(key), "getStructureSet");
		}

		@Override
		public @NonNull Optional<HolderSet.Named<StructureSet>> get(@NonNull TagKey<StructureSet> tag) {
			return Objects.requireNonNull(this.delegate.get(tag), "getStructureSetTag");
		}
	}

	private static void applySnowCover(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			int worldX,
			int worldZ,
			int surface,
			int minY,
			boolean reduceIce
	) {
		long seed = seedFromCoords(worldX, 0, worldZ) ^ 0x5DEECE66DL;
		Random random = new Random(seed);
		int roll = random.nextInt(COVER_ROLL_RANGE);
		if (roll < SNOW_ICE_CHANCE && (!reduceIce || random.nextBoolean())) {
			cursor.set(worldX, surface, worldZ);
			chunk.setBlockState(cursor, ICE_STATE);
			return;
		}
		if (roll < SNOW_ICE_CHANCE + POWDER_SNOW_CHANCE) {
			int depth = 1 + random.nextInt(MAX_POWDER_DEPTH);
			for (int i = 0; i < depth; i++) {
				int y = surface - i;
				if (y < minY) {
					break;
				}
				cursor.set(worldX, y, worldZ);
				chunk.setBlockState(cursor, POWDER_SNOW_STATE);
			}
			return;
		}
		cursor.set(worldX, surface, worldZ);
		chunk.setBlockState(cursor, SNOW_BLOCK_STATE);
	}

	public void applyRealtimeSnowCover(WorldGenLevel level, ChunkAccess chunk) {
		if (!TellusRealtimeState.isHistoricalSnowEnabled()
				&& !(TellusRealtimeState.isWeatherEnabled()
				&& TellusRealtimeState.precipitationMode() == TellusRealtimeState.PrecipitationMode.SNOW)) {
			return;
		}
		int updateFlags = level instanceof ServerLevel ? Block.UPDATE_CLIENTS : Block.UPDATE_NONE;
		ChunkPos pos = chunk.getPos();
		int chunkMinX = pos.getMinBlockX();
		int chunkMinZ = pos.getMinBlockZ();
		int minY = chunk.getMinY();
		int maxY = minY + chunk.getHeight();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		BlockState snowLayer = SNOW_LAYER_STATE;

		for (int localX = 0; localX < CHUNK_SIDE; localX++) {
			int worldX = chunkMinX + localX;
			for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
				int worldZ = chunkMinZ + localZ;
				if (!TellusRealtimeState.shouldApplySnow(worldX, worldZ)) {
					continue;
				}
				int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
				if (surface < minY || surface + 1 >= maxY) {
					continue;
				}
				int slopeDiff = sampleSlopeDiff(level, worldX, worldZ, surface);
				if (slopeDiff >= SNOW_SLOPE_DIFF) {
					continue;
				}
				cursor.set(worldX, surface, worldZ);
				BlockState surfaceState = level.getBlockState(cursor);
				if (!surfaceState.getFluidState().isEmpty()) {
					continue;
				}
				BlockPos above = cursor.above();
				if (!level.getBlockState(above).isAir()) {
					continue;
				}
				if (!snowLayer.canSurvive(level, above)) {
					continue;
				}
				level.setBlock(above, snowLayer, updateFlags);
				if (surfaceState.hasProperty(BlockStateProperties.SNOWY)
						&& !surfaceState.getValue(BlockStateProperties.SNOWY)) {
					BlockState snowySurface = Objects.requireNonNull(
							surfaceState.setValue(BlockStateProperties.SNOWY, Boolean.TRUE),
							"snowySurface"
					);
					level.setBlock(cursor, snowySurface, updateFlags);
				}
			}
		}
	}

	private void spawnAxolotlsInLushPonds(WorldGenLevel level, ChunkAccess chunk) {
		ServerLevel serverLevel = level.getLevel();
		// C2ME can run biome decoration on worker threads; entity creation is only safe on the server thread.
		if (!serverLevel.getServer().isSameThread()) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		long seed = seedFromCoords(pos.x, 11, pos.z) ^ this.worldSeed ^ 0x7C1F39E2A56BD408L;
		RandomSource random = RandomSource.create(seed);
		if (random.nextFloat() > AXOLOTL_CHUNK_CHANCE) {
			return;
		}

		int chunkMinX = pos.getMinBlockX();
		int chunkMinZ = pos.getMinBlockZ();
		int minY = Math.max(this.minY + 4, chunk.getMinY() + 4);
		int spawned = 0;
		int attempts = 6 + random.nextInt(6);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int attempt = 0; attempt < attempts; attempt++) {
			int worldX = chunkMinX + random.nextInt(CHUNK_SIDE);
			int worldZ = chunkMinZ + random.nextInt(CHUNK_SIDE);
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
			int maxY = Math.min(surface - 4, this.seaLevel - 1);
			if (maxY <= minY) {
				continue;
			}

			for (int y = maxY; y >= minY; y--) {
				cursor.set(worldX, y, worldZ);
				BlockState water = level.getBlockState(cursor);
				if (!water.getFluidState().is(Fluids.WATER)) {
					continue;
				}
				cursor.set(worldX, y - 1, worldZ);
				if (!level.getBlockState(cursor).is(Blocks.CLAY)) {
					continue;
				}
				cursor.set(worldX, y + 1, worldZ);
				if (!level.getBlockState(cursor).isAir()) {
					continue;
				}
				cursor.set(worldX, y, worldZ);
				if (!level.getBiome(cursor).is(Biomes.LUSH_CAVES)) {
					break;
				}
				if (random.nextFloat() > AXOLOTL_POND_CHANCE) {
					break;
				}

				var axolotl = EntityType.AXOLOTL.create(
						serverLevel,
						entity -> {
						},
						cursor.immutable(),
						EntitySpawnReason.CHUNK_GENERATION,
						false,
						false
				);
				if (axolotl == null) {
					return;
				}
				axolotl.setPersistenceRequired();
				axolotl.snapTo(worldX + 0.5, y + 0.1, worldZ + 0.5, random.nextFloat() * 360.0f, 0.0f);
				if (serverLevel.addFreshEntity(axolotl)) {
					spawned++;
				}
				break;
			}
			if (spawned >= MAX_AXOLOTLS_PER_CHUNK) {
				return;
			}
		}
	}

	private static int sampleSlopeDiff(WorldGenLevel level, int worldX, int worldZ, int surface) {
		int step = SLOPE_SAMPLE_STEP;
		int east = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX + step, worldZ);
		int west = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX - step, worldZ);
		int north = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ - step);
		int south = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ + step);

		return Math.max(
				Math.max(Math.abs(east - surface), Math.abs(west - surface)),
				Math.max(Math.abs(north - surface), Math.abs(south - surface))
		);
	}

	private static final class WaterChunkCache {
		private int chunkX = Integer.MIN_VALUE;
		private int chunkZ = Integer.MIN_VALUE;
		private WaterSurfaceResolver.WaterChunkData data;

		private boolean matches(ChunkPos pos) {
			return this.data != null && this.chunkX == pos.x && this.chunkZ == pos.z;
		}

		private WaterSurfaceResolver.WaterChunkData data() {
			return this.data;
		}

		private void update(ChunkPos pos, WaterSurfaceResolver.WaterChunkData data) {
			this.chunkX = pos.x;
			this.chunkZ = pos.z;
			this.data = data;
		}
	}

	private record ColumnHeights(int terrainSurface, int waterSurface, boolean hasWater) {
	}

	private record MountainSurfaceContext(boolean retainSnow, int darkRockScore) {
	}

	public record LodSurface(@NonNull BlockState top, @NonNull BlockState filler) {
	}

	private record SurfacePalette(@NonNull BlockState top, @NonNull BlockState underwaterTop, @NonNull BlockState filler, int depth) {
		static SurfacePalette defaultOverworld() {
			return new SurfacePalette(GRASS_BLOCK_STATE, DIRT_STATE, DIRT_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette steppe() {
			return new SurfacePalette(COARSE_DIRT_STATE, COARSE_DIRT_STATE, DIRT_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette podzolic() {
			return new SurfacePalette(PODZOL_STATE, PODZOL_STATE, DIRT_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette rooted() {
			return new SurfacePalette(ROOTED_DIRT_STATE, ROOTED_DIRT_STATE, DIRT_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette mossy() {
			return new SurfacePalette(MOSS_BLOCK_STATE, MOSS_BLOCK_STATE, DIRT_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette talus() {
			return new SurfacePalette(GRAVEL_STATE, GRAVEL_STATE, COBBLESTONE_STATE, SURFACE_DEPTH + 1);
		}

		static SurfacePalette scree() {
			return new SurfacePalette(GRAVEL_STATE, GRAVEL_STATE, ANDESITE_STATE, SURFACE_DEPTH + 1);
		}

		static SurfacePalette deepslateBlend() {
			return new SurfacePalette(ANDESITE_STATE, ANDESITE_STATE, DEEPSLATE_STATE, SURFACE_DEPTH + 1);
		}

		static SurfacePalette deepslateMass() {
			return new SurfacePalette(DEEPSLATE_STATE, DEEPSLATE_STATE, DEEPSLATE_STATE, SURFACE_DEPTH + 1);
		}

		static SurfacePalette transitionAndesite() {
			return new SurfacePalette(ANDESITE_STATE, ANDESITE_STATE, STONE_STATE, SURFACE_DEPTH + 1);
		}

		static SurfacePalette transitionDiorite() {
			return new SurfacePalette(DIORITE_STATE, DIORITE_STATE, STONE_STATE, SURFACE_DEPTH + 1);
		}

		static SurfacePalette transitionTuff() {
			return new SurfacePalette(TUFF_STATE, TUFF_STATE, STONE_STATE, SURFACE_DEPTH + 1);
		}

		static SurfacePalette desert() {
			return new SurfacePalette(SAND_STATE, SAND_STATE, SANDSTONE_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette badlands() {
			return new SurfacePalette(RED_SAND_STATE, RED_SAND_STATE, TERRACOTTA_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette beach() {
			return new SurfacePalette(SAND_STATE, SAND_STATE, SAND_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette ocean(@NonNull BlockState top) {
			return new SurfacePalette(top, top, top, SURFACE_DEPTH);
		}

		static SurfacePalette snowy() {
			return new SurfacePalette(SNOW_BLOCK_STATE, SNOW_BLOCK_STATE, DIRT_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette swamp() {
			return new SurfacePalette(GRASS_BLOCK_STATE, DIRT_STATE, DIRT_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette wetland() {
			return new SurfacePalette(MUD_STATE, MUD_STATE, PACKED_MUD_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette mangrove() {
			return new SurfacePalette(MUD_STATE, MUD_STATE, DIRT_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette stonyPeaks() {
			return new SurfacePalette(STONE_STATE, STONE_STATE, STONE_STATE, SURFACE_DEPTH);
		}

		static SurfacePalette gravelly() {
			return new SurfacePalette(GRAVEL_STATE, GRAVEL_STATE, STONE_STATE, SURFACE_DEPTH);
		}
	}
}
