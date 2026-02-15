package com.yucareux.tellus.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.world.data.biome.BiomeClassification;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class EarthBiomeSource extends BiomeSource {
	public static final MapCodec<EarthBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			RegistryOps.<Biome, EarthBiomeSource>retrieveGetter(Registries.BIOME),
			EarthGeneratorSettings.CODEC.fieldOf("settings").forGetter(EarthBiomeSource::settings)
	).apply(instance, EarthBiomeSource::new));

	private static final int ESA_SNOW_ICE = 70;
	private static final int ESA_WATER = 80;
	private static final int ESA_MANGROVES = 95;
	private static final int ESA_NO_DATA = 0;
	private static final int CAVE_MIN_DEPTH = 8;
	private static final int LUSH_MIN_DEPTH = 12;
	private static final int DRIPSTONE_MIN_DEPTH = 16;
	private static final int DEEP_DARK_MIN_DEPTH = 24;
	private static final int CAVE_BIOME_GRID = 48;
	private static final int CAVE_BIOME_Y_GRID = 32;
	private static final int DEEP_DARK_GRID = 96;
	private static final int DEEP_DARK_Y_GRID = 48;
	private static final int DEEP_DARK_Y_OFFSET = 32;
	private static final double MAX_CAVE_BIOME_CHANCE = 0.55;

	private static final TellusLandCoverSource LAND_COVER_SOURCE = TellusWorldgenSources.landCover();
	private static final TellusKoppenSource KOPPEN_SOURCE = TellusWorldgenSources.koppen();

	private final @NonNull HolderGetter<Biome> biomeLookup;
	private final @NonNull EarthGeneratorSettings settings;
	private final @NonNull Set<Holder<Biome>> possibleBiomes;
	private final @NonNull Holder<Biome> plains;
	private final @NonNull Holder<Biome> ocean;
	private final @NonNull Holder<Biome> river;
	private final @NonNull Holder<Biome> frozenPeaks;
	private final @NonNull Holder<Biome> mangrove;
	private final @Nullable Holder<Biome> lushCaves;
	private final @Nullable Holder<Biome> dripstoneCaves;
	private final @Nullable Holder<Biome> deepDark;
	private final @NonNull WaterSurfaceResolver waterResolver;
	private final int deepDarkCeiling;
	private volatile boolean fastSpawnMode = true;

	public EarthBiomeSource(HolderGetter<Biome> biomeLookup, EarthGeneratorSettings settings) {
		this.biomeLookup = Objects.requireNonNull(biomeLookup, "biomeLookup");
		this.settings = Objects.requireNonNull(settings, "settings");
		this.deepDarkCeiling = settings.resolveSeaLevel() - DEEP_DARK_Y_OFFSET;
		this.plains = this.biomeLookup.getOrThrow(Biomes.PLAINS);
		this.ocean = resolveBiome(Biomes.OCEAN, this.plains);
		this.river = resolveBiome(Biomes.RIVER, this.plains);
		this.frozenPeaks = resolveBiome(Biomes.FROZEN_PEAKS, this.plains);
		this.mangrove = resolveBiome(Biomes.MANGROVE_SWAMP, this.plains);
		this.lushCaves = resolveOptionalBiome(Biomes.LUSH_CAVES);
		this.dripstoneCaves = resolveOptionalBiome(Biomes.DRIPSTONE_CAVES);
		this.deepDark = resolveOptionalBiome(Biomes.DEEP_DARK);
		this.waterResolver = TellusWorldgenSources.waterResolver(this.settings);
		this.possibleBiomes = buildPossibleBiomes();
	}

	public EarthGeneratorSettings settings() {
		return this.settings;
	}

	void setFastSpawnMode(boolean enabled) {
		this.fastSpawnMode = enabled;
	}

	@Override
	protected @NonNull Stream<Holder<Biome>> collectPossibleBiomes() {
		return Objects.requireNonNull(this.possibleBiomes.stream(), "possibleBiomes.stream()");
	}

	@Override
	protected @NonNull MapCodec<? extends BiomeSource> codec() {
		return Objects.requireNonNull(CODEC, "CODEC");
	}

	@Override
	public @NonNull Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.@NonNull Sampler sampler) {
		int blockX = QuartPos.toBlock(x);
		int blockY = QuartPos.toBlock(y);
		int blockZ = QuartPos.toBlock(z);
		return resolveBiomeAtBlock(blockX, blockY, blockZ);
	}

	public @NonNull Holder<Biome> getBiomeAtBlock(int blockX, int blockZ) {
		return resolveSurfaceBiomeAtBlock(blockX, blockZ);
	}

	private @NonNull Holder<Biome> resolveSurfaceBiomeAtBlock(int blockX, int blockZ) {
		if (this.fastSpawnMode) {
			return resolveFastSpawnSurfaceBiome(blockX, blockZ);
		}
		int coverClass = LAND_COVER_SOURCE.sampleCoverClass(blockX, blockZ, this.settings.worldScale());
		return resolveSurfaceBiomeAtBlock(blockX, blockZ, coverClass, null);
	}

	private @NonNull Holder<Biome> resolveBiomeAtBlock(int blockX, int blockY, int blockZ) {
		if (this.fastSpawnMode) {
			return resolveFastSpawnSurfaceBiome(blockX, blockZ);
		}
		int coverClass = LAND_COVER_SOURCE.sampleCoverClass(blockX, blockZ, this.settings.worldScale());
		WaterSurfaceResolver.WaterColumnData column =
				this.waterResolver.resolveColumnData(blockX, blockZ, coverClass);
		Holder<Biome> surfaceBiome = resolveSurfaceBiomeAtBlock(blockX, blockZ, coverClass, column);
		if (!this.settings.caveGeneration()) {
			return surfaceBiome;
		}
		int depth = column.terrainSurface() - blockY;
		if (depth < CAVE_MIN_DEPTH) {
			return surfaceBiome;
		}
		return resolveCaveBiome(surfaceBiome, blockX, blockY, blockZ, depth);
	}

	private @NonNull Holder<Biome> resolveFastSpawnSurfaceBiome(int blockX, int blockZ) {
		int coverClass = LAND_COVER_SOURCE.sampleCoverClass(blockX, blockZ, this.settings.worldScale());
		if (coverClass == ESA_SNOW_ICE) {
			return this.frozenPeaks;
		}
		if (coverClass == ESA_MANGROVES) {
			return this.mangrove;
		}
		if (coverClass == ESA_WATER) {
			return this.ocean;
		}
		if (coverClass == ESA_NO_DATA) {
			return this.ocean;
		}
		return this.plains;
	}

	private @NonNull Holder<Biome> resolveSurfaceBiomeAtBlock(
			int blockX,
			int blockZ,
			int coverClass,
			WaterSurfaceResolver.@Nullable WaterColumnData column
	) {

		if (coverClass == ESA_SNOW_ICE) {
			return this.frozenPeaks;
		}
		if (coverClass == ESA_MANGROVES) {
			return this.mangrove;
		}
		if (coverClass == ESA_NO_DATA || coverClass == ESA_WATER) {
			WaterSurfaceResolver.WaterColumnData waterColumn =
					column != null ? column : this.waterResolver.resolveColumnData(blockX, blockZ, coverClass);
			if (waterColumn.hasWater()) {
				if (waterColumn.isOcean()) {
					return this.ocean;
				}
				return this.river;
			}
		}

		String koppen = KOPPEN_SOURCE.sampleDitheredCode(blockX, blockZ, this.settings.worldScale());
		if (koppen == null) {
			koppen = KOPPEN_SOURCE.findNearestCode(blockX, blockZ, this.settings.worldScale());
		}

		ResourceKey<Biome> biomeKey = BiomeClassification.findBiomeKey(coverClass, koppen);
		if (biomeKey == null) {
			biomeKey = BiomeClassification.findFallbackKey(coverClass);
		}
		if (biomeKey == null) {
			return this.plains;
		}
		return resolveBiome(biomeKey, this.plains);
	}

	private @NonNull Set<Holder<Biome>> buildPossibleBiomes() {
		Set<Holder<Biome>> holders = new HashSet<>();
		for (ResourceKey<Biome> key : BiomeClassification.allBiomeKeys()) {
			holders.add(resolveBiome(key, this.plains));
		}
		holders.add(this.plains);
		holders.add(this.ocean);
		holders.add(this.river);
		holders.add(this.frozenPeaks);
		holders.add(this.mangrove);
		if (this.settings.caveGeneration()) {
			addIfPresent(holders, this.lushCaves);
			addIfPresent(holders, this.dripstoneCaves);
			if (this.settings.deepDark()) {
				addIfPresent(holders, this.deepDark);
			}
		}
		return holders;
	}

	private @NonNull Holder<Biome> resolveCaveBiome(
			@NonNull Holder<Biome> surfaceBiome,
			int blockX,
			int blockY,
			int blockZ,
			int depth
	) {
		double depthFactor = Mth.clamp((depth - CAVE_MIN_DEPTH) / 80.0, 0.0, 1.0);
		double noise = sampleCaveNoise(blockX, blockY, blockZ, CAVE_BIOME_GRID, CAVE_BIOME_Y_GRID);
		Holder<Biome> deepDarkBiome = this.deepDark;
		Holder<Biome> lushCavesBiome = this.lushCaves;
		Holder<Biome> dripstoneCavesBiome = this.dripstoneCaves;

		if (this.settings.deepDark()
				&& deepDarkBiome != null
				&& blockY <= this.deepDarkCeiling
				&& depth >= DEEP_DARK_MIN_DEPTH) {
			double deepNoise = sampleCaveNoise(blockX, blockY, blockZ, DEEP_DARK_GRID, DEEP_DARK_Y_GRID);
			double deepChance = 0.28 + depthFactor * 0.22;
			if (deepNoise < deepChance) {
				return deepDarkBiome;
			}
		}

		double lushChance = (isLushSurface(surfaceBiome) ? 0.45 : 0.25) * (1.0 - depthFactor * 0.35);
		double dripChance = (isDrySurface(surfaceBiome) ? 0.45 : 0.25) * (0.7 + depthFactor * 0.5);

		if (depth < LUSH_MIN_DEPTH) {
			lushChance = 0.0;
		}
		if (depth < DRIPSTONE_MIN_DEPTH) {
			dripChance = 0.0;
		}

		double total = lushChance + dripChance;
		if (total <= 0.0 || noise > MAX_CAVE_BIOME_CHANCE) {
			return surfaceBiome;
		}

		double pick = noise * total / MAX_CAVE_BIOME_CHANCE;
		if (lushCavesBiome != null && pick < lushChance) {
			return lushCavesBiome;
		}
		if (dripstoneCavesBiome != null && pick < lushChance + dripChance) {
			return dripstoneCavesBiome;
		}
		return surfaceBiome;
	}

	private static boolean isLushSurface(Holder<Biome> surfaceBiome) {
		return surfaceBiome.is(Biomes.JUNGLE)
				|| surfaceBiome.is(Biomes.SPARSE_JUNGLE)
				|| surfaceBiome.is(Biomes.BAMBOO_JUNGLE)
				|| surfaceBiome.is(Biomes.SWAMP)
				|| surfaceBiome.is(Biomes.MANGROVE_SWAMP)
				|| surfaceBiome.is(Biomes.DARK_FOREST)
				|| surfaceBiome.is(Biomes.FOREST)
				|| surfaceBiome.is(Biomes.BIRCH_FOREST)
				|| surfaceBiome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)
				|| surfaceBiome.is(Biomes.OLD_GROWTH_PINE_TAIGA)
				|| surfaceBiome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)
				|| surfaceBiome.is(Biomes.TAIGA);
	}

	private static boolean isDrySurface(Holder<Biome> surfaceBiome) {
		return surfaceBiome.is(Biomes.DESERT)
				|| surfaceBiome.is(Biomes.BADLANDS)
				|| surfaceBiome.is(Biomes.WOODED_BADLANDS)
				|| surfaceBiome.is(Biomes.ERODED_BADLANDS)
				|| surfaceBiome.is(Biomes.SAVANNA)
				|| surfaceBiome.is(Biomes.SAVANNA_PLATEAU)
				|| surfaceBiome.is(Biomes.WINDSWEPT_SAVANNA);
	}

	private static double sampleCaveNoise(int blockX, int blockY, int blockZ, int gridXZ, int gridY) {
		int x = Math.floorDiv(blockX, gridXZ);
		int y = Math.floorDiv(blockY, gridY);
		int z = Math.floorDiv(blockZ, gridXZ);
		long seed = (long) x * 341873128712L + (long) z * 132897987541L + (long) y * 42317861L;
		return hashToUnit(seed);
	}

	private static double hashToUnit(long seed) {
		seed ^= (seed >>> 33);
		seed *= 0xff51afd7ed558ccdL;
		seed ^= (seed >>> 33);
		seed *= 0xc4ceb9fe1a85ec53L;
		seed ^= (seed >>> 33);
		return (double) (seed >>> 11) * 0x1.0p-53;
	}

	private static void addIfPresent(Set<Holder<Biome>> holders, @Nullable Holder<Biome> biome) {
		if (biome != null) {
			holders.add(biome);
		}
	}

	private @NonNull Holder<Biome> resolveBiome(@Nullable ResourceKey<Biome> key, @NonNull Holder<Biome> fallback) {
		if (key == null) {
			return fallback;
		}
		Holder<Biome> resolved = this.biomeLookup.get(key).map(holder -> (Holder<Biome>) holder).orElse(fallback);
		return Objects.requireNonNull(resolved, "resolvedBiome");
	}

	private @Nullable Holder<Biome> resolveOptionalBiome(@Nullable ResourceKey<Biome> key) {
		if (key == null) {
			return null;
		}
		return this.biomeLookup.get(key).map(holder -> (Holder<Biome>) holder).orElse(null);
	}
}
