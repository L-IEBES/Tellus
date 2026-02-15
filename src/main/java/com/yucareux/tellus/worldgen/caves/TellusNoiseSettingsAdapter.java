package com.yucareux.tellus.worldgen.caves;

import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;

public final class TellusNoiseSettingsAdapter {
	private TellusNoiseSettingsAdapter() {
	}

	public static Holder<NoiseGeneratorSettings> adaptToTellusHeight(
			Holder<NoiseGeneratorSettings> baseHolder,
			int tellusMinY,
			int tellusHeight,
			int seaLevel
	) {
		NoiseGeneratorSettings base = baseHolder.value();
		NoiseSettings baseNoise = base.noiseSettings();
		NoiseSettings adaptedNoise = NoiseSettings.create(
				tellusMinY,
				tellusHeight,
				baseNoise.noiseSizeHorizontal(),
				baseNoise.noiseSizeVertical()
		);
			int maxY = tellusMinY + tellusHeight - 1;
			int adaptedSeaLevel = Mth.clamp(seaLevel, tellusMinY, maxY);
			NoiseGeneratorSettings adapted = new NoiseGeneratorSettings(
					adaptedNoise,
					base.defaultBlock(),
					base.defaultFluid(),
					base.noiseRouter(),
					base.surfaceRule(),
					base.spawnTarget(),
					adaptedSeaLevel,
					readDisableMobGeneration(base),
					base.aquifersEnabled(),
					base.oreVeinsEnabled(),
					base.useLegacyRandomSource()
			);
			return Holder.direct(adapted);
	}

	@SuppressWarnings("deprecation")
	private static boolean readDisableMobGeneration(NoiseGeneratorSettings settings) {
		// 1.21.11 still exposes this flag via a deprecated accessor with no replacement.
		return settings.disableMobGeneration();
	}
}
