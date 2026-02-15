package com.yucareux.tellus.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapDecoder;
import com.mojang.serialization.MapEncoder;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;

public record EarthGeneratorSettings(
		double worldScale,
		double terrestrialHeightScale,
		double oceanicHeightScale,
		int heightOffset,
		int seaLevel,
		double spawnLatitude,
		double spawnLongitude,
		int minAltitude,
		int maxAltitude,
		int riverLakeShorelineBlend,
		int oceanShorelineBlend,
		boolean shorelineBlendCliffLimit,
		boolean caveGeneration,
		boolean oreDistribution,
		boolean lavaPools,
		boolean addStrongholds,
		boolean addVillages,
		boolean addMineshafts,
		boolean addOceanMonuments,
		boolean addWoodlandMansions,
		boolean addDesertTemples,
		boolean addJungleTemples,
		boolean addPillagerOutposts,
		boolean addRuinedPortals,
		boolean addShipwrecks,
		boolean addOceanRuins,
		boolean addBuriedTreasure,
		boolean addIgloos,
		boolean addWitchHuts,
		boolean addAncientCities,
		boolean addTrialChambers,
		boolean addTrailRuins,
		boolean deepDark,
		boolean geodes,
		boolean distantHorizonsWaterResolver,
		boolean realtimeTime,
		boolean realtimeWeather,
		boolean historicalSnow,
		boolean voxyChunkPregenEnabled,
		int voxyChunkPregenMaxRadius,
		int voxyChunkPregenChunksPerTick,
		DistantHorizonsRenderMode distantHorizonsRenderMode,
		DemProvider demProvider
) {
	public static final double DEFAULT_SPAWN_LATITUDE = 27.9881;
	public static final double DEFAULT_SPAWN_LONGITUDE = 86.9250;
	public static final int AUTO_ALTITUDE = Integer.MIN_VALUE;
	public static final int AUTO_SEA_LEVEL = Integer.MIN_VALUE + 1;

	public static final int MIN_WORLD_Y = -2032;
	public static final int MAX_WORLD_HEIGHT = 4064;
	public static final int MAX_WORLD_Y = MIN_WORLD_Y + MAX_WORLD_HEIGHT - 1;

	private static final int ALTITUDE_TOLERANCE = 50;
	private static final int HEIGHT_ALIGNMENT = 16;
	private static final double EVEREST_ELEVATION_METERS = 8848.0;
	private static final double MARIANA_TRENCH_METERS = -11034.0;
	private static final double MAX_WORLD_SCALE = 1000.0;
	private static final int MAX_VOXY_PREGEN_RADIUS = 1024;
	private static final int MAX_VOXY_PREGEN_CHUNKS_PER_TICK = 200;

	public EarthGeneratorSettings {
		worldScale = clampWorldScale(worldScale);
		voxyChunkPregenMaxRadius = Mth.clamp(voxyChunkPregenMaxRadius, 0, MAX_VOXY_PREGEN_RADIUS);
		voxyChunkPregenChunksPerTick = Mth.clamp(voxyChunkPregenChunksPerTick, 1, MAX_VOXY_PREGEN_CHUNKS_PER_TICK);
	}

	public static final EarthGeneratorSettings DEFAULT = new EarthGeneratorSettings(
			35.0,
			1.0,
			1.0,
			64,
			AUTO_SEA_LEVEL,
			DEFAULT_SPAWN_LATITUDE,
			DEFAULT_SPAWN_LONGITUDE,
			-64,
			AUTO_ALTITUDE,
			5,
			5,
			true,
			false,
			false,
			false,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			false,
			false,
			false,
			false,
			false,
			96,
			4,
			DistantHorizonsRenderMode.FAST,
			DemProvider.TERRARIUM
	);

	private static final MapCodec<BaseToggles> BASE_TOGGLES_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("cave_generation").forGetter(BaseToggles::caveGeneration),
			Codec.BOOL.optionalFieldOf("cave_carvers").forGetter(BaseToggles::caveCarvers),
			Codec.BOOL.optionalFieldOf("large_caves").forGetter(BaseToggles::largeCaves),
			Codec.BOOL.optionalFieldOf("canyon_carvers").forGetter(BaseToggles::canyonCarvers),
			Codec.BOOL.fieldOf("ore_distribution").orElse(DEFAULT.oreDistribution()).forGetter(BaseToggles::oreDistribution),
			Codec.BOOL.fieldOf("lava_pools").orElse(DEFAULT.lavaPools()).forGetter(BaseToggles::lavaPools)
	).apply(instance, EarthGeneratorSettings::createBaseToggles));

	private static final MapCodec<SettingsBase> BASE_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.DOUBLE.fieldOf("world_scale").orElse(DEFAULT.worldScale()).forGetter(SettingsBase::worldScale),
			Codec.DOUBLE.fieldOf("terrestrial_height_scale").orElse(DEFAULT.terrestrialHeightScale())
					.forGetter(SettingsBase::terrestrialHeightScale),
			Codec.DOUBLE.fieldOf("oceanic_height_scale").orElse(DEFAULT.oceanicHeightScale())
					.forGetter(SettingsBase::oceanicHeightScale),
			Codec.INT.fieldOf("height_offset").orElse(DEFAULT.heightOffset()).forGetter(SettingsBase::heightOffset),
			Codec.DOUBLE.fieldOf("spawn_latitude").orElse(DEFAULT.spawnLatitude()).forGetter(SettingsBase::spawnLatitude),
			Codec.DOUBLE.fieldOf("spawn_longitude").orElse(DEFAULT.spawnLongitude()).forGetter(SettingsBase::spawnLongitude),
			Codec.INT.fieldOf("min_altitude").orElse(DEFAULT.minAltitude()).forGetter(SettingsBase::minAltitude),
			Codec.INT.fieldOf("max_altitude").orElse(DEFAULT.maxAltitude()).forGetter(SettingsBase::maxAltitude),
			Codec.INT.fieldOf("river_lake_shoreline_blend").orElse(DEFAULT.riverLakeShorelineBlend())
					.forGetter(SettingsBase::riverLakeShorelineBlend),
			Codec.INT.fieldOf("ocean_shoreline_blend").orElse(DEFAULT.oceanShorelineBlend())
					.forGetter(SettingsBase::oceanShorelineBlend),
			Codec.BOOL.fieldOf("shoreline_blend_cliff_limit").orElse(DEFAULT.shorelineBlendCliffLimit())
					.forGetter(SettingsBase::shorelineBlendCliffLimit),
			BASE_TOGGLES_CODEC.forGetter(settings -> new BaseToggles(
					Optional.of(settings.caveGeneration()),
					Optional.empty(),
					Optional.empty(),
					Optional.empty(),
					settings.oreDistribution(),
					settings.lavaPools()
			))
	).apply(instance, (worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, spawnLatitude, spawnLongitude,
			minAltitude, maxAltitude, riverLakeShorelineBlend, oceanShorelineBlend, shorelineBlendCliffLimit, toggles) -> createSettingsBase(
			worldScale,
			terrestrialHeightScale,
			oceanicHeightScale,
			heightOffset,
			spawnLatitude,
			spawnLongitude,
			minAltitude,
			maxAltitude,
			riverLakeShorelineBlend,
			oceanShorelineBlend,
			shorelineBlendCliffLimit,
			toggles.resolveCaveGeneration(),
			toggles.oreDistribution(),
			toggles.lavaPools()
	)));

	private static final MapCodec<Optional<Integer>> SEA_LEVEL_CODEC =
			Codec.INT.optionalFieldOf("sea_level");

	private static final MapCodec<DistantHorizonsRenderMode> DISTANT_HORIZONS_RENDER_MODE_CODEC =
			DistantHorizonsRenderMode.CODEC.fieldOf("distant_horizons_render_mode")
					.orElse(DEFAULT.distantHorizonsRenderMode());

	private static final MapCodec<DemProvider> DEM_PROVIDER_CODEC =
			DemProvider.CODEC.fieldOf("dem_provider")
					.orElse(DEFAULT.demProvider());

	private static final MapCodec<Boolean> DISTANT_HORIZONS_WATER_RESOLVER_CODEC =
			Codec.BOOL.fieldOf("distant_horizons_water_resolver").orElse(DEFAULT.distantHorizonsWaterResolver());

	private static final MapCodec<Boolean> REALTIME_TIME_CODEC =
			Codec.BOOL.fieldOf("realtime_time").orElse(DEFAULT.realtimeTime());

	private static final MapCodec<Boolean> REALTIME_WEATHER_CODEC =
			Codec.BOOL.fieldOf("realtime_weather").orElse(DEFAULT.realtimeWeather());

	private static final MapCodec<Boolean> HISTORICAL_SNOW_CODEC =
			Codec.BOOL.fieldOf("historical_snow").orElse(DEFAULT.historicalSnow());

	private static final MapCodec<Boolean> VOXY_CHUNK_PREGEN_ENABLED_CODEC =
			Codec.BOOL.fieldOf("voxy_chunk_pregen_enabled").orElse(DEFAULT.voxyChunkPregenEnabled());

	private static final MapCodec<Integer> VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC =
			Codec.intRange(0, MAX_VOXY_PREGEN_RADIUS)
					.fieldOf("voxy_chunk_pregen_max_radius")
					.orElse(DEFAULT.voxyChunkPregenMaxRadius());

	private static final MapCodec<Integer> VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC =
			Codec.intRange(1, MAX_VOXY_PREGEN_CHUNKS_PER_TICK)
					.fieldOf("voxy_chunk_pregen_chunks_per_tick")
					.orElse(DEFAULT.voxyChunkPregenChunksPerTick());

	private static final MapCodec<Boolean> DEEP_DARK_CODEC =
			Codec.BOOL.fieldOf("deep_dark").orElse(DEFAULT.deepDark());

	private static final MapCodec<Boolean> GEODES_CODEC =
			Codec.BOOL.fieldOf("geodes").orElse(DEFAULT.geodes());

	private static final MapCodec<StructureSettings> STRUCTURE_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.BOOL.fieldOf("add_strongholds").orElse(DEFAULT.addStrongholds()).forGetter(StructureSettings::addStrongholds),
			Codec.BOOL.fieldOf("add_villages").orElse(DEFAULT.addVillages()).forGetter(StructureSettings::addVillages),
			Codec.BOOL.fieldOf("add_mineshafts").orElse(DEFAULT.addMineshafts()).forGetter(StructureSettings::addMineshafts),
			Codec.BOOL.fieldOf("add_ocean_monuments").orElse(DEFAULT.addOceanMonuments()).forGetter(StructureSettings::addOceanMonuments),
			Codec.BOOL.fieldOf("add_woodland_mansions").orElse(DEFAULT.addWoodlandMansions()).forGetter(StructureSettings::addWoodlandMansions),
			Codec.BOOL.fieldOf("add_desert_temples").orElse(DEFAULT.addDesertTemples()).forGetter(StructureSettings::addDesertTemples),
			Codec.BOOL.fieldOf("add_jungle_temples").orElse(DEFAULT.addJungleTemples()).forGetter(StructureSettings::addJungleTemples),
			Codec.BOOL.fieldOf("add_pillager_outposts").orElse(DEFAULT.addPillagerOutposts()).forGetter(StructureSettings::addPillagerOutposts),
			Codec.BOOL.fieldOf("add_ruined_portals").orElse(DEFAULT.addRuinedPortals()).forGetter(StructureSettings::addRuinedPortals),
			Codec.BOOL.fieldOf("add_shipwrecks").orElse(DEFAULT.addShipwrecks()).forGetter(StructureSettings::addShipwrecks),
			Codec.BOOL.fieldOf("add_ocean_ruins").orElse(DEFAULT.addOceanRuins()).forGetter(StructureSettings::addOceanRuins),
			Codec.BOOL.fieldOf("add_buried_treasure").orElse(DEFAULT.addBuriedTreasure()).forGetter(StructureSettings::addBuriedTreasure),
			Codec.BOOL.fieldOf("add_igloos").orElse(DEFAULT.addIgloos()).forGetter(StructureSettings::addIgloos),
			Codec.BOOL.fieldOf("add_witch_huts").orElse(DEFAULT.addWitchHuts()).forGetter(StructureSettings::addWitchHuts),
			Codec.BOOL.fieldOf("add_ancient_cities").orElse(DEFAULT.addAncientCities()).forGetter(StructureSettings::addAncientCities),
			Codec.BOOL.fieldOf("add_trial_chambers").orElse(DEFAULT.addTrialChambers()).forGetter(StructureSettings::addTrialChambers)
	).apply(instance, EarthGeneratorSettings::createStructureSettings));

	private static final MapCodec<Boolean> TRAIL_RUINS_CODEC =
			Codec.BOOL.fieldOf("add_trail_ruins").orElse(DEFAULT.addTrailRuins());

	private static final MapCodec<EarthGeneratorSettings> MAP_CODEC = MapCodec.of(
			new MapEncoder.Implementation<>() {
				@Override
				public <T> RecordBuilder<T> encode(EarthGeneratorSettings input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
					RecordBuilder<T> builder = BASE_CODEC.encode(SettingsBase.fromSettings(input), ops, prefix);
					Optional<Integer> seaLevel = input.seaLevel() == AUTO_SEA_LEVEL
							? Optional.empty()
							: Optional.of(input.seaLevel());
					builder = SEA_LEVEL_CODEC.encode(seaLevel, ops, builder);
					builder = DISTANT_HORIZONS_RENDER_MODE_CODEC.encode(input.distantHorizonsRenderMode(), ops, builder);
					builder = DEM_PROVIDER_CODEC.encode(input.demProvider(), ops, builder);
					builder = DISTANT_HORIZONS_WATER_RESOLVER_CODEC.encode(input.distantHorizonsWaterResolver(), ops, builder);
					builder = REALTIME_TIME_CODEC.encode(input.realtimeTime(), ops, builder);
					builder = REALTIME_WEATHER_CODEC.encode(input.realtimeWeather(), ops, builder);
					builder = HISTORICAL_SNOW_CODEC.encode(input.historicalSnow(), ops, builder);
					builder = VOXY_CHUNK_PREGEN_ENABLED_CODEC.encode(input.voxyChunkPregenEnabled(), ops, builder);
					builder = VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.encode(input.voxyChunkPregenMaxRadius(), ops, builder);
					builder = VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.encode(input.voxyChunkPregenChunksPerTick(), ops, builder);
					builder = DEEP_DARK_CODEC.encode(input.deepDark(), ops, builder);
					builder = GEODES_CODEC.encode(input.geodes(), ops, builder);
					builder = STRUCTURE_CODEC.encode(StructureSettings.fromSettings(input), ops, builder);
					return TRAIL_RUINS_CODEC.encode(input.addTrailRuins(), ops, builder);
				}

				@Override
				public <T> Stream<T> keys(DynamicOps<T> ops) {
					Stream<T> baseKeys = Stream.concat(BASE_CODEC.keys(ops), SEA_LEVEL_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, DISTANT_HORIZONS_RENDER_MODE_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, DEM_PROVIDER_CODEC.keys(ops));
						baseKeys = Stream.concat(baseKeys, DISTANT_HORIZONS_WATER_RESOLVER_CODEC.keys(ops));
						baseKeys = Stream.concat(baseKeys, REALTIME_TIME_CODEC.keys(ops));
						baseKeys = Stream.concat(baseKeys, REALTIME_WEATHER_CODEC.keys(ops));
						baseKeys = Stream.concat(baseKeys, HISTORICAL_SNOW_CODEC.keys(ops));
						baseKeys = Stream.concat(baseKeys, VOXY_CHUNK_PREGEN_ENABLED_CODEC.keys(ops));
						baseKeys = Stream.concat(baseKeys, VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.keys(ops));
						baseKeys = Stream.concat(baseKeys, VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.keys(ops));
						baseKeys = Stream.concat(baseKeys, DEEP_DARK_CODEC.keys(ops));
						baseKeys = Stream.concat(baseKeys, GEODES_CODEC.keys(ops));
						Stream<T> structureKeys = Stream.concat(baseKeys, STRUCTURE_CODEC.keys(ops));
					return Stream.concat(structureKeys, TRAIL_RUINS_CODEC.keys(ops));
				}
			},
			new MapDecoder.Implementation<>() {
				@Override
				public <T> DataResult<EarthGeneratorSettings> decode(DynamicOps<T> ops, MapLike<T> input) {
					DataResult<SettingsBase> base = BASE_CODEC.decode(ops, input);
					DataResult<Optional<Integer>> seaLevel = SEA_LEVEL_CODEC.decode(ops, input);
					DataResult<DistantHorizonsRenderMode> distantHorizonsRenderMode =
							DISTANT_HORIZONS_RENDER_MODE_CODEC.decode(ops, input);
					DataResult<DemProvider> demProvider = DEM_PROVIDER_CODEC.decode(ops, input);
					DataResult<Boolean> distantHorizonsWaterResolver =
							DISTANT_HORIZONS_WATER_RESOLVER_CODEC.decode(ops, input);
					DataResult<Boolean> realtimeTime = REALTIME_TIME_CODEC.decode(ops, input);
					DataResult<Boolean> realtimeWeather = REALTIME_WEATHER_CODEC.decode(ops, input);
					DataResult<Boolean> historicalSnow = HISTORICAL_SNOW_CODEC.decode(ops, input);
					DataResult<Boolean> voxyChunkPregenEnabled = VOXY_CHUNK_PREGEN_ENABLED_CODEC.decode(ops, input);
					DataResult<Integer> voxyChunkPregenMaxRadius = VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.decode(ops, input);
					DataResult<Integer> voxyChunkPregenChunksPerTick = VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.decode(ops, input);
					DataResult<Boolean> deepDark = DEEP_DARK_CODEC.decode(ops, input);
					DataResult<Boolean> geodes = GEODES_CODEC.decode(ops, input);
					DataResult<StructureSettings> structures = STRUCTURE_CODEC.decode(ops, input);
					DataResult<Boolean> trailRuins = TRAIL_RUINS_CODEC.decode(ops, input);
					DataResult<SettingsBase> withSeaLevel = base.apply2(EarthGeneratorSettings::applySeaLevel, seaLevel);
					DataResult<SettingsBase> withRenderMode = withSeaLevel.apply2(
							EarthGeneratorSettings::applyDistantHorizonsRenderMode,
							distantHorizonsRenderMode
					);
					DataResult<SettingsBase> withDemProvider = withRenderMode.apply2(
							EarthGeneratorSettings::applyDemProvider,
							demProvider
					);
					DataResult<SettingsBase> withWaterResolver = withDemProvider.apply2(
							EarthGeneratorSettings::applyDistantHorizonsWaterResolver,
							distantHorizonsWaterResolver
					);
					DataResult<SettingsBase> withRealtimeTime = withWaterResolver.apply2(
							EarthGeneratorSettings::applyRealtimeTime,
							realtimeTime
					);
					DataResult<SettingsBase> withRealtimeWeather = withRealtimeTime.apply2(
							EarthGeneratorSettings::applyRealtimeWeather,
							realtimeWeather
					);
					DataResult<SettingsBase> withHistoricalSnow = withRealtimeWeather.apply2(
							EarthGeneratorSettings::applyHistoricalSnow,
							historicalSnow
					);
					DataResult<SettingsBase> withVoxyEnabled = withHistoricalSnow.apply2(
							EarthGeneratorSettings::applyVoxyChunkPregenEnabled,
							voxyChunkPregenEnabled
					);
					DataResult<SettingsBase> withVoxyMaxRadius = withVoxyEnabled.apply2(
							EarthGeneratorSettings::applyVoxyChunkPregenMaxRadius,
							voxyChunkPregenMaxRadius
					);
					DataResult<SettingsBase> withVoxyChunksPerTick = withVoxyMaxRadius.apply2(
							EarthGeneratorSettings::applyVoxyChunkPregenChunksPerTick,
							voxyChunkPregenChunksPerTick
					);
					DataResult<EarthGeneratorSettings> settings = withVoxyChunksPerTick.map(SettingsBase::toSettings);
					settings = settings.apply2(EarthGeneratorSettings::applyDeepDark, deepDark);
					settings = settings.apply2(EarthGeneratorSettings::applyGeodes, geodes);
					settings = settings.apply2(EarthGeneratorSettings::withStructureSettings, structures);
					return settings.apply2(EarthGeneratorSettings::applyTrailRuins, trailRuins);
				}

				@Override
				public <T> Stream<T> keys(DynamicOps<T> ops) {
					Stream<T> baseKeys = Stream.concat(BASE_CODEC.keys(ops), SEA_LEVEL_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, DISTANT_HORIZONS_RENDER_MODE_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, DEM_PROVIDER_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, DISTANT_HORIZONS_WATER_RESOLVER_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, REALTIME_TIME_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, REALTIME_WEATHER_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, HISTORICAL_SNOW_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, VOXY_CHUNK_PREGEN_ENABLED_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, DEEP_DARK_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, GEODES_CODEC.keys(ops));
					Stream<T> structureKeys = Stream.concat(baseKeys, STRUCTURE_CODEC.keys(ops));
					return Stream.concat(structureKeys, TRAIL_RUINS_CODEC.keys(ops));
				}
			}
	);

	public static final Codec<EarthGeneratorSettings> CODEC = MAP_CODEC.codec();

	public boolean isSeaLevelAutomatic() {
		return this.seaLevel == AUTO_SEA_LEVEL;
	}

	public int resolveSeaLevel() {
		if (this.seaLevel == AUTO_SEA_LEVEL) {
			return this.heightOffset;
		}
		return this.seaLevel;
	}

	private static StructureSettings createStructureSettings(
			Boolean addStrongholds,
			Boolean addVillages,
			Boolean addMineshafts,
			Boolean addOceanMonuments,
			Boolean addWoodlandMansions,
			Boolean addDesertTemples,
			Boolean addJungleTemples,
			Boolean addPillagerOutposts,
			Boolean addRuinedPortals,
			Boolean addShipwrecks,
			Boolean addOceanRuins,
			Boolean addBuriedTreasure,
			Boolean addIgloos,
			Boolean addWitchHuts,
			Boolean addAncientCities,
			Boolean addTrialChambers
	) {
		return new StructureSettings(
				Objects.requireNonNull(addStrongholds, "addStrongholds").booleanValue(),
				Objects.requireNonNull(addVillages, "addVillages").booleanValue(),
				Objects.requireNonNull(addMineshafts, "addMineshafts").booleanValue(),
				Objects.requireNonNull(addOceanMonuments, "addOceanMonuments").booleanValue(),
				Objects.requireNonNull(addWoodlandMansions, "addWoodlandMansions").booleanValue(),
				Objects.requireNonNull(addDesertTemples, "addDesertTemples").booleanValue(),
				Objects.requireNonNull(addJungleTemples, "addJungleTemples").booleanValue(),
				Objects.requireNonNull(addPillagerOutposts, "addPillagerOutposts").booleanValue(),
				Objects.requireNonNull(addRuinedPortals, "addRuinedPortals").booleanValue(),
				Objects.requireNonNull(addShipwrecks, "addShipwrecks").booleanValue(),
				Objects.requireNonNull(addOceanRuins, "addOceanRuins").booleanValue(),
				Objects.requireNonNull(addBuriedTreasure, "addBuriedTreasure").booleanValue(),
				Objects.requireNonNull(addIgloos, "addIgloos").booleanValue(),
				Objects.requireNonNull(addWitchHuts, "addWitchHuts").booleanValue(),
				Objects.requireNonNull(addAncientCities, "addAncientCities").booleanValue(),
				Objects.requireNonNull(addTrialChambers, "addTrialChambers").booleanValue()
		);
	}

	private static SettingsBase createSettingsBase(
			Double worldScale,
			Double terrestrialHeightScale,
			Double oceanicHeightScale,
			Integer heightOffset,
			Double spawnLatitude,
			Double spawnLongitude,
			Integer minAltitude,
			Integer maxAltitude,
			Integer riverLakeShorelineBlend,
			Integer oceanShorelineBlend,
			Boolean shorelineBlendCliffLimit,
			Boolean caveGeneration,
			Boolean oreDistribution,
			Boolean lavaPools
	) {
		int resolvedHeightOffset = Objects.requireNonNull(heightOffset, "heightOffset").intValue();
		int resolvedSeaLevel = AUTO_SEA_LEVEL;
		double resolvedWorldScale = clampWorldScale(Objects.requireNonNull(worldScale, "worldScale").doubleValue());
		return new SettingsBase(
				resolvedWorldScale,
				Objects.requireNonNull(terrestrialHeightScale, "terrestrialHeightScale").doubleValue(),
				Objects.requireNonNull(oceanicHeightScale, "oceanicHeightScale").doubleValue(),
				resolvedHeightOffset,
				resolvedSeaLevel,
				Objects.requireNonNull(spawnLatitude, "spawnLatitude").doubleValue(),
				Objects.requireNonNull(spawnLongitude, "spawnLongitude").doubleValue(),
				Objects.requireNonNull(minAltitude, "minAltitude").intValue(),
				Objects.requireNonNull(maxAltitude, "maxAltitude").intValue(),
				Objects.requireNonNull(riverLakeShorelineBlend, "riverLakeShorelineBlend").intValue(),
				Objects.requireNonNull(oceanShorelineBlend, "oceanShorelineBlend").intValue(),
				Objects.requireNonNull(shorelineBlendCliffLimit, "shorelineBlendCliffLimit").booleanValue(),
				Objects.requireNonNull(caveGeneration, "caveGeneration").booleanValue(),
				Objects.requireNonNull(oreDistribution, "oreDistribution").booleanValue(),
				Objects.requireNonNull(lavaPools, "lavaPools").booleanValue(),
				DEFAULT.distantHorizonsWaterResolver(),
				DEFAULT.realtimeTime(),
				DEFAULT.realtimeWeather(),
				DEFAULT.historicalSnow(),
				DEFAULT.voxyChunkPregenEnabled(),
				DEFAULT.voxyChunkPregenMaxRadius(),
				DEFAULT.voxyChunkPregenChunksPerTick(),
				DEFAULT.distantHorizonsRenderMode(),
				DEFAULT.demProvider()
			);
	}

	private static BaseToggles createBaseToggles(
			Optional<Boolean> caveGeneration,
			Optional<Boolean> caveCarvers,
			Optional<Boolean> largeCaves,
			Optional<Boolean> canyonCarvers,
			Boolean oreDistribution,
			Boolean lavaPools
	) {
		return new BaseToggles(
				caveGeneration,
				caveCarvers,
				largeCaves,
				canyonCarvers,
				Objects.requireNonNull(oreDistribution, "oreDistribution").booleanValue(),
				Objects.requireNonNull(lavaPools, "lavaPools").booleanValue()
		);
	}

	private static double clampWorldScale(double worldScale) {
		if (worldScale <= 0.0) {
			return worldScale;
		}
		return Math.min(worldScale, MAX_WORLD_SCALE);
	}

	private record BaseToggles(
			Optional<Boolean> caveGeneration,
			Optional<Boolean> caveCarvers,
			Optional<Boolean> largeCaves,
			Optional<Boolean> canyonCarvers,
			boolean oreDistribution,
			boolean lavaPools
	) {
		private boolean resolveCaveGeneration() {
			if (this.caveGeneration.isPresent()) {
				return this.caveGeneration.get().booleanValue();
			}
			boolean hasLegacy = this.caveCarvers.isPresent()
					|| this.largeCaves.isPresent()
					|| this.canyonCarvers.isPresent();
			if (hasLegacy) {
				return this.caveCarvers.orElse(false)
						|| this.largeCaves.orElse(false)
						|| this.canyonCarvers.orElse(false);
			}
			return DEFAULT.caveGeneration();
		}
	}

	private record SettingsBase(
			double worldScale,
			double terrestrialHeightScale,
			double oceanicHeightScale,
			int heightOffset,
			int seaLevel,
			double spawnLatitude,
			double spawnLongitude,
			int minAltitude,
			int maxAltitude,
			int riverLakeShorelineBlend,
			int oceanShorelineBlend,
			boolean shorelineBlendCliffLimit,
			boolean caveGeneration,
			boolean oreDistribution,
			boolean lavaPools,
			boolean distantHorizonsWaterResolver,
			boolean realtimeTime,
			boolean realtimeWeather,
			boolean historicalSnow,
			boolean voxyChunkPregenEnabled,
			int voxyChunkPregenMaxRadius,
			int voxyChunkPregenChunksPerTick,
			DistantHorizonsRenderMode distantHorizonsRenderMode,
			DemProvider demProvider
	) {
		private static SettingsBase fromSettings(EarthGeneratorSettings settings) {
			return new SettingsBase(
					settings.worldScale(),
					settings.terrestrialHeightScale(),
					settings.oceanicHeightScale(),
					settings.heightOffset(),
					settings.seaLevel(),
					settings.spawnLatitude(),
					settings.spawnLongitude(),
					settings.minAltitude(),
					settings.maxAltitude(),
					settings.riverLakeShorelineBlend(),
					settings.oceanShorelineBlend(),
					settings.shorelineBlendCliffLimit(),
					settings.caveGeneration(),
					settings.oreDistribution(),
					settings.lavaPools(),
					settings.distantHorizonsWaterResolver(),
					settings.realtimeTime(),
					settings.realtimeWeather(),
					settings.historicalSnow(),
					settings.voxyChunkPregenEnabled(),
					settings.voxyChunkPregenMaxRadius(),
					settings.voxyChunkPregenChunksPerTick(),
					settings.distantHorizonsRenderMode(),
					settings.demProvider()
			);
		}

		private SettingsBase withSeaLevel(int seaLevel) {
			return new SettingsBase(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.riverLakeShorelineBlend,
					this.oceanShorelineBlend,
					this.shorelineBlendCliffLimit,
					this.caveGeneration,
					this.oreDistribution,
					this.lavaPools,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

		private SettingsBase withDistantHorizonsWaterResolver(boolean enabled) {
			return new SettingsBase(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					this.seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.riverLakeShorelineBlend,
					this.oceanShorelineBlend,
					this.shorelineBlendCliffLimit,
					this.caveGeneration,
					this.oreDistribution,
					this.lavaPools,
					enabled,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

		private SettingsBase withRealtimeTime(boolean enabled) {
			return new SettingsBase(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					this.seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.riverLakeShorelineBlend,
					this.oceanShorelineBlend,
					this.shorelineBlendCliffLimit,
					this.caveGeneration,
					this.oreDistribution,
					this.lavaPools,
					this.distantHorizonsWaterResolver,
					enabled,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

		private SettingsBase withRealtimeWeather(boolean enabled) {
			return new SettingsBase(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					this.seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.riverLakeShorelineBlend,
					this.oceanShorelineBlend,
					this.shorelineBlendCliffLimit,
					this.caveGeneration,
					this.oreDistribution,
					this.lavaPools,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					enabled,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

		private SettingsBase withHistoricalSnow(boolean enabled) {
			return new SettingsBase(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					this.seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.riverLakeShorelineBlend,
					this.oceanShorelineBlend,
					this.shorelineBlendCliffLimit,
					this.caveGeneration,
					this.oreDistribution,
					this.lavaPools,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					enabled,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

		private SettingsBase withVoxyChunkPregenEnabled(boolean enabled) {
			return new SettingsBase(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					this.seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.riverLakeShorelineBlend,
					this.oceanShorelineBlend,
					this.shorelineBlendCliffLimit,
					this.caveGeneration,
					this.oreDistribution,
					this.lavaPools,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					enabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

		private SettingsBase withVoxyChunkPregenMaxRadius(int maxRadius) {
			return new SettingsBase(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					this.seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.riverLakeShorelineBlend,
					this.oceanShorelineBlend,
					this.shorelineBlendCliffLimit,
					this.caveGeneration,
					this.oreDistribution,
					this.lavaPools,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					maxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

		private SettingsBase withVoxyChunkPregenChunksPerTick(int chunksPerTick) {
			return new SettingsBase(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					this.seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.riverLakeShorelineBlend,
					this.oceanShorelineBlend,
					this.shorelineBlendCliffLimit,
					this.caveGeneration,
					this.oreDistribution,
					this.lavaPools,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					chunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

		private SettingsBase withDistantHorizonsRenderMode(DistantHorizonsRenderMode renderMode) {
			return new SettingsBase(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					this.seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.riverLakeShorelineBlend,
					this.oceanShorelineBlend,
					this.shorelineBlendCliffLimit,
					this.caveGeneration,
					this.oreDistribution,
					this.lavaPools,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					renderMode,
					this.demProvider
			);
		}

		private SettingsBase withDemProvider(DemProvider demProvider) {
			return new SettingsBase(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					this.seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.riverLakeShorelineBlend,
					this.oceanShorelineBlend,
					this.shorelineBlendCliffLimit,
					this.caveGeneration,
					this.oreDistribution,
					this.lavaPools,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					Objects.requireNonNull(demProvider, "demProvider")
			);
		}

		private EarthGeneratorSettings toSettings() {
			return new EarthGeneratorSettings(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					this.seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.riverLakeShorelineBlend,
					this.oceanShorelineBlend,
					this.shorelineBlendCliffLimit,
					this.caveGeneration,
					this.oreDistribution,
					this.lavaPools,
					DEFAULT.addStrongholds(),
					DEFAULT.addVillages(),
					DEFAULT.addMineshafts(),
					DEFAULT.addOceanMonuments(),
					DEFAULT.addWoodlandMansions(),
					DEFAULT.addDesertTemples(),
					DEFAULT.addJungleTemples(),
					DEFAULT.addPillagerOutposts(),
					DEFAULT.addRuinedPortals(),
					DEFAULT.addShipwrecks(),
					DEFAULT.addOceanRuins(),
					DEFAULT.addBuriedTreasure(),
					DEFAULT.addIgloos(),
					DEFAULT.addWitchHuts(),
					DEFAULT.addAncientCities(),
					DEFAULT.addTrialChambers(),
					DEFAULT.addTrailRuins(),
					DEFAULT.deepDark(),
					DEFAULT.geodes(),
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}
	}

	private static SettingsBase applySeaLevel(SettingsBase settings, Optional<Integer> seaLevel) {
		Optional<Integer> value = Objects.requireNonNull(seaLevel, "seaLevel");
		if (value.isEmpty()) {
			return settings;
		}
		int resolved = value.get();
		if (resolved == AUTO_SEA_LEVEL) {
			return settings.withSeaLevel(AUTO_SEA_LEVEL);
		}
		return settings.withSeaLevel(resolved);
	}

	private static SettingsBase applyDistantHorizonsRenderMode(
			SettingsBase settings,
			DistantHorizonsRenderMode renderMode
	) {
		return settings.withDistantHorizonsRenderMode(Objects.requireNonNull(renderMode, "renderMode"));
	}

	private static SettingsBase applyDemProvider(SettingsBase settings, DemProvider demProvider) {
		return settings.withDemProvider(Objects.requireNonNull(demProvider, "demProvider"));
	}

	private static SettingsBase applyDistantHorizonsWaterResolver(SettingsBase settings, Boolean enabled) {
		return settings.withDistantHorizonsWaterResolver(Objects.requireNonNull(enabled, "distantHorizonsWaterResolver").booleanValue());
	}

	private static SettingsBase applyRealtimeTime(SettingsBase settings, Boolean enabled) {
		return settings.withRealtimeTime(Objects.requireNonNull(enabled, "realtimeTime").booleanValue());
	}

	private static SettingsBase applyRealtimeWeather(SettingsBase settings, Boolean enabled) {
		return settings.withRealtimeWeather(Objects.requireNonNull(enabled, "realtimeWeather").booleanValue());
	}

	private static SettingsBase applyHistoricalSnow(SettingsBase settings, Boolean enabled) {
		return settings.withHistoricalSnow(Objects.requireNonNull(enabled, "historicalSnow").booleanValue());
	}

	private static SettingsBase applyVoxyChunkPregenEnabled(SettingsBase settings, Boolean enabled) {
		return settings.withVoxyChunkPregenEnabled(Objects.requireNonNull(enabled, "voxyChunkPregenEnabled").booleanValue());
	}

	private static SettingsBase applyVoxyChunkPregenMaxRadius(SettingsBase settings, Integer maxRadius) {
		return settings.withVoxyChunkPregenMaxRadius(Objects.requireNonNull(maxRadius, "voxyChunkPregenMaxRadius").intValue());
	}

	private static SettingsBase applyVoxyChunkPregenChunksPerTick(SettingsBase settings, Integer chunksPerTick) {
		return settings.withVoxyChunkPregenChunksPerTick(Objects.requireNonNull(chunksPerTick, "voxyChunkPregenChunksPerTick").intValue());
	}

	private record StructureSettings(
			boolean addStrongholds,
			boolean addVillages,
			boolean addMineshafts,
			boolean addOceanMonuments,
			boolean addWoodlandMansions,
			boolean addDesertTemples,
			boolean addJungleTemples,
			boolean addPillagerOutposts,
			boolean addRuinedPortals,
			boolean addShipwrecks,
			boolean addOceanRuins,
			boolean addBuriedTreasure,
			boolean addIgloos,
			boolean addWitchHuts,
			boolean addAncientCities,
			boolean addTrialChambers
	) {
		private static StructureSettings fromSettings(EarthGeneratorSettings settings) {
			return new StructureSettings(
					settings.addStrongholds(),
					settings.addVillages(),
					settings.addMineshafts(),
					settings.addOceanMonuments(),
					settings.addWoodlandMansions(),
					settings.addDesertTemples(),
					settings.addJungleTemples(),
					settings.addPillagerOutposts(),
					settings.addRuinedPortals(),
					settings.addShipwrecks(),
					settings.addOceanRuins(),
					settings.addBuriedTreasure(),
					settings.addIgloos(),
					settings.addWitchHuts(),
					settings.addAncientCities(),
					settings.addTrialChambers()
			);
		}
	}

	private EarthGeneratorSettings withStructureSettings(StructureSettings structures) {
		return new EarthGeneratorSettings(
				this.worldScale,
				this.terrestrialHeightScale,
				this.oceanicHeightScale,
				this.heightOffset,
				this.seaLevel,
				this.spawnLatitude,
				this.spawnLongitude,
				this.minAltitude,
				this.maxAltitude,
				this.riverLakeShorelineBlend,
				this.oceanShorelineBlend,
				this.shorelineBlendCliffLimit,
				this.caveGeneration,
				this.oreDistribution,
				this.lavaPools,
				structures.addStrongholds(),
				structures.addVillages(),
				structures.addMineshafts(),
				structures.addOceanMonuments(),
				structures.addWoodlandMansions(),
				structures.addDesertTemples(),
				structures.addJungleTemples(),
				structures.addPillagerOutposts(),
				structures.addRuinedPortals(),
				structures.addShipwrecks(),
				structures.addOceanRuins(),
				structures.addBuriedTreasure(),
				structures.addIgloos(),
				structures.addWitchHuts(),
				structures.addAncientCities(),
				structures.addTrialChambers(),
				this.addTrailRuins,
				this.deepDark,
				this.geodes,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

	private static EarthGeneratorSettings applyTrailRuins(EarthGeneratorSettings settings, Boolean addTrailRuins) {
		return settings.withTrailRuins(Objects.requireNonNull(addTrailRuins, "addTrailRuins").booleanValue());
	}

	private static EarthGeneratorSettings applyDeepDark(EarthGeneratorSettings settings, Boolean deepDark) {
		return settings.withDeepDark(Objects.requireNonNull(deepDark, "deepDark").booleanValue());
	}

	private static EarthGeneratorSettings applyGeodes(EarthGeneratorSettings settings, Boolean geodes) {
		return settings.withGeodes(Objects.requireNonNull(geodes, "geodes").booleanValue());
	}

	private EarthGeneratorSettings withTrailRuins(boolean addTrailRuins) {
		return new EarthGeneratorSettings(
				this.worldScale,
				this.terrestrialHeightScale,
				this.oceanicHeightScale,
				this.heightOffset,
				this.seaLevel,
				this.spawnLatitude,
				this.spawnLongitude,
				this.minAltitude,
				this.maxAltitude,
				this.riverLakeShorelineBlend,
				this.oceanShorelineBlend,
				this.shorelineBlendCliffLimit,
				this.caveGeneration,
				this.oreDistribution,
				this.lavaPools,
				this.addStrongholds,
				this.addVillages,
				this.addMineshafts,
				this.addOceanMonuments,
				this.addWoodlandMansions,
				this.addDesertTemples,
				this.addJungleTemples,
				this.addPillagerOutposts,
				this.addRuinedPortals,
				this.addShipwrecks,
				this.addOceanRuins,
				this.addBuriedTreasure,
				this.addIgloos,
				this.addWitchHuts,
				this.addAncientCities,
				this.addTrialChambers,
				addTrailRuins,
				this.deepDark,
				this.geodes,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

	private EarthGeneratorSettings withDeepDark(boolean deepDark) {
		return new EarthGeneratorSettings(
				this.worldScale,
				this.terrestrialHeightScale,
				this.oceanicHeightScale,
				this.heightOffset,
				this.seaLevel,
				this.spawnLatitude,
				this.spawnLongitude,
				this.minAltitude,
				this.maxAltitude,
				this.riverLakeShorelineBlend,
				this.oceanShorelineBlend,
				this.shorelineBlendCliffLimit,
				this.caveGeneration,
				this.oreDistribution,
				this.lavaPools,
				this.addStrongholds,
				this.addVillages,
				this.addMineshafts,
				this.addOceanMonuments,
				this.addWoodlandMansions,
				this.addDesertTemples,
				this.addJungleTemples,
				this.addPillagerOutposts,
				this.addRuinedPortals,
				this.addShipwrecks,
				this.addOceanRuins,
				this.addBuriedTreasure,
				this.addIgloos,
				this.addWitchHuts,
				this.addAncientCities,
				this.addTrialChambers,
				this.addTrailRuins,
				deepDark,
				this.geodes,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

	private EarthGeneratorSettings withGeodes(boolean geodes) {
		return new EarthGeneratorSettings(
				this.worldScale,
				this.terrestrialHeightScale,
				this.oceanicHeightScale,
				this.heightOffset,
				this.seaLevel,
				this.spawnLatitude,
				this.spawnLongitude,
				this.minAltitude,
				this.maxAltitude,
				this.riverLakeShorelineBlend,
				this.oceanShorelineBlend,
				this.shorelineBlendCliffLimit,
				this.caveGeneration,
				this.oreDistribution,
				this.lavaPools,
				this.addStrongholds,
				this.addVillages,
				this.addMineshafts,
				this.addOceanMonuments,
				this.addWoodlandMansions,
				this.addDesertTemples,
				this.addJungleTemples,
				this.addPillagerOutposts,
				this.addRuinedPortals,
				this.addShipwrecks,
				this.addOceanRuins,
				this.addBuriedTreasure,
				this.addIgloos,
				this.addWitchHuts,
				this.addAncientCities,
				this.addTrialChambers,
				this.addTrailRuins,
				this.deepDark,
				geodes,
					this.distantHorizonsWaterResolver,
					this.realtimeTime,
					this.realtimeWeather,
					this.historicalSnow,
					this.voxyChunkPregenEnabled,
					this.voxyChunkPregenMaxRadius,
					this.voxyChunkPregenChunksPerTick,
					this.distantHorizonsRenderMode,
					this.demProvider
			);
		}

	public enum DistantHorizonsRenderMode {
		FAST("fast"),
		ULTRA_FAST("ultra_fast"),
		DETAILED("detailed");

		public static final Codec<DistantHorizonsRenderMode> CODEC = Codec.STRING.xmap(
				DistantHorizonsRenderMode::fromId,
				DistantHorizonsRenderMode::id
		);

		private final String id;

		DistantHorizonsRenderMode(String id) {
			this.id = Objects.requireNonNull(id, "id");
		}

		public String id() {
			return this.id;
		}

		public static DistantHorizonsRenderMode fromId(String id) {
			if (id == null) {
				return FAST;
			}
			for (DistantHorizonsRenderMode mode : values()) {
				if (mode.id.equalsIgnoreCase(id)) {
					return mode;
				}
			}
			return FAST;
		}
	}

	public enum DemProvider {
		TERRARIUM("terrarium"),
		ARCTICDEM("arcticdem");

		public static final Codec<DemProvider> CODEC = Codec.STRING.xmap(
				DemProvider::fromId,
				DemProvider::id
		);

		private final String id;

		DemProvider(String id) {
			this.id = Objects.requireNonNull(id, "id");
		}

		public String id() {
			return this.id;
		}

		public static DemProvider fromId(String id) {
			if (id == null) {
				return TERRARIUM;
			}
			for (DemProvider provider : values()) {
				if (provider.id.equalsIgnoreCase(id)) {
					return provider;
				}
			}
			return TERRARIUM;
		}
	}

	public static HeightLimits resolveHeightLimits(EarthGeneratorSettings settings) {
		int autoMin = computeAutoMinAltitude(settings);
		int autoMax = computeAutoMaxAltitude(settings);
		boolean autoMinEnabled = settings.minAltitude() == AUTO_ALTITUDE;
		boolean autoMaxEnabled = settings.maxAltitude() == AUTO_ALTITUDE;

		if ((autoMinEnabled && autoMin < MIN_WORLD_Y) || (autoMaxEnabled && autoMax > MAX_WORLD_Y)) {
			return HeightLimits.maxRange();
		}

		int resolvedMin = autoMinEnabled ? autoMin : settings.minAltitude();
		int resolvedMax = autoMaxEnabled ? autoMax : settings.maxAltitude();

		if (resolvedMin > resolvedMax) {
			int swap = resolvedMin;
			resolvedMin = resolvedMax;
			resolvedMax = swap;
		}

		resolvedMin = Mth.clamp(resolvedMin, MIN_WORLD_Y, MAX_WORLD_Y);
		resolvedMax = Mth.clamp(resolvedMax, MIN_WORLD_Y, MAX_WORLD_Y);

		int alignedMin = alignDown(resolvedMin, HEIGHT_ALIGNMENT);
		int alignedTop = alignUp(resolvedMax + 1, HEIGHT_ALIGNMENT);
		int height = alignedTop - alignedMin;

		if (alignedMin < MIN_WORLD_Y || alignedTop - 1 > MAX_WORLD_Y || height > MAX_WORLD_HEIGHT) {
			return HeightLimits.maxRange();
		}
		if (height <= 0) {
			height = HEIGHT_ALIGNMENT;
			alignedTop = alignedMin + height;
			if (alignedTop - 1 > MAX_WORLD_Y) {
				return HeightLimits.maxRange();
			}
		}

		return new HeightLimits(alignedMin, height, height);
	}


	private static int computeAutoMaxAltitude(EarthGeneratorSettings settings) {
		if (settings.worldScale() <= 0.0) {
			return settings.heightOffset();
		}
		double scaled = EVEREST_ELEVATION_METERS * settings.terrestrialHeightScale() / settings.worldScale();
		int maxSurface = Mth.ceil(scaled) + settings.heightOffset();
		return maxSurface + ALTITUDE_TOLERANCE;
	}

	private static int computeAutoMinAltitude(EarthGeneratorSettings settings) {
		if (settings.worldScale() <= 0.0) {
			return settings.heightOffset();
		}
		double scaled = MARIANA_TRENCH_METERS * settings.oceanicHeightScale() / settings.worldScale();
		int minSurface = Mth.floor(scaled) + settings.heightOffset();
		return minSurface - ALTITUDE_TOLERANCE;
	}

	private static int alignDown(int value, int alignment) {
		int remainder = Math.floorMod(value, alignment);
		return value - remainder;
	}

	private static int alignUp(int value, int alignment) {
		int remainder = Math.floorMod(value, alignment);
		return remainder == 0 ? value : value + (alignment - remainder);
	}

	public record HeightLimits(int minY, int height, int logicalHeight) {
		public static HeightLimits maxRange() {
			return new HeightLimits(MIN_WORLD_Y, MAX_WORLD_HEIGHT, MAX_WORLD_HEIGHT);
		}
	}

	public static DimensionType applyHeightLimits(DimensionType base, HeightLimits limits) {
		return new DimensionType(
				base.hasFixedTime(),
				base.hasSkyLight(),
				base.hasCeiling(),
				base.coordinateScale(),
				limits.minY(),
				limits.height(),
				limits.logicalHeight(),
				base.infiniburn(),
				base.ambientLight(),
				base.monsterSettings(),
				base.skybox(),
				base.cardinalLightType(),
				base.attributes(),
				base.timelines()
		);
	}

}
