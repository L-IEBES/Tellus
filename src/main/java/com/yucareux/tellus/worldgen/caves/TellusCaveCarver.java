package com.yucareux.tellus.worldgen.caves;

import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

public final class TellusCaveCarver {
	private final ConfiguredWorldCarver<CaveCarverConfiguration> configured;

	public TellusCaveCarver(CaveCarverConfiguration configuration) {
		this.configured = WorldCarver.CAVE.configured(configuration);
	}

	ConfiguredWorldCarver<CaveCarverConfiguration> configured() {
		return this.configured;
	}
}
