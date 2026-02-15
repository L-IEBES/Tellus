package com.yucareux.tellus.worldgen.caves;

import net.minecraft.util.Mth;

final class TellusHeightRemapper {
	private static final int VANILLA_MIN_Y = -64;
	private static final int VANILLA_HEIGHT = 384;

	private TellusHeightRemapper() {
	}

	static int remapVanillaAbsolute(int vanillaAbsoluteY, int tellusMinY, int tellusHeight) {
		int tellusMaxY = tellusMinY + tellusHeight - 1;
		if (tellusHeight <= 0) {
			return Mth.clamp(vanillaAbsoluteY, tellusMinY, tellusMaxY);
		}
		double normalized = (vanillaAbsoluteY - VANILLA_MIN_Y) / (double) VANILLA_HEIGHT;
		int remapped = tellusMinY + Mth.floor(normalized * tellusHeight);
		return Mth.clamp(remapped, tellusMinY, tellusMaxY);
	}
}
