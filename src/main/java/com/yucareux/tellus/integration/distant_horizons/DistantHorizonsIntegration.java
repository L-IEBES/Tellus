package com.yucareux.tellus.integration.distant_horizons;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

public final class DistantHorizonsIntegration {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String VOXY_MOD_ID = "voxy";

	private DistantHorizonsIntegration() {
	}

	private static boolean checkApiVersion() {
		final int apiMajor = DhApi.getApiMajorVersion();
		final int apiMinor = DhApi.getApiMinorVersion();
		final int apiPatch = DhApi.getApiPatchVersion();
		if (apiMajor < 4) {
			LOGGER.warn(
					"Detected Distant Horizons {}, but API {}.{}.{} is too old - won't enable integration with Tellus",
					DhApi.getModVersion(),
					apiMajor,
					apiMinor,
					apiPatch
			);
			return false;
		}
		LOGGER.info(
				"Detected Distant Horizons {} (API {}.{}.{}), enabling integration with Tellus",
				DhApi.getModVersion(),
				apiMajor,
				apiMinor,
				apiPatch
		);
		return true;
	}

	public static void bootstrap() {
		if (!checkApiVersion()) {
			return;
		}
		DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
			@Override
			public void onLevelLoad(final DhApiEventParam<EventParam> param) {
				DistantHorizonsIntegration.onLevelLoad(param.value.levelWrapper);
			}
		});
	}

	private static void onLevelLoad(final IDhApiLevelWrapper levelWrapper) {
		if (levelWrapper.getWrappedMcObject() instanceof final ServerLevel level
				&& level.getChunkSource().getGenerator() instanceof final EarthChunkGenerator generator
		) {
			EarthGeneratorSettings settings = generator.settings();
			if (settings.voxyChunkPregenEnabled() && FabricLoader.getInstance().isModLoaded(VOXY_MOD_ID)) {
				LOGGER.info("Voxy pregen enabled; skipping Tellus Distant Horizons integration override");
				return;
			}
			if (settings.distantHorizonsRenderMode() == EarthGeneratorSettings.DistantHorizonsRenderMode.DETAILED) {
				LOGGER.info("Distant Horizons render mode set to detailed; using chunk-based generator");
				final TellusChunkLodGenerator chunkGenerator = new TellusChunkLodGenerator(level);
				final DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, chunkGenerator);
				if (!result.success) {
					LOGGER.warn("Failed to register Tellus chunk LOD generator: {}", result.message);
				}
				return;
			}
			final TellusLodGenerator lodGenerator = new TellusLodGenerator(levelWrapper, generator);
			final DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, lodGenerator);
			if (!result.success) {
				LOGGER.warn("Failed to register Tellus LOD generator: {}", result.message);
			}
		}
	}
}
