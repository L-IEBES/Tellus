package com.yucareux.tellus.mixin.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {
	private static final int TEXT_COLOR = 0xFFE5E5E5;
	private static final int TEXT_PADDING = 20;
	private static final int LINE_SPACING = 2;
	private static final int MAX_TEXT_WIDTH = 420;
	private static final int BASELINE_OFFSET = 56;

	private static final List<@NonNull Component> CONTRIBUTIONS = List.of(
			Component.literal("Land cover: \u00a9 ESA WorldCover project / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium."),
			Component.literal("Climate zones: K\u00f6ppen\u2013Geiger climate classification (Beck et al., 2018) \u2014 CC BY 4.0."),
			Component.literal("Elevation: Terrain Tiles (Mapzen J\u00f6r\u00f0 / AWS Open Data) \u2014 see Data Sources for required DEM attributions."),
			Component.literal("Weather: Weather data by Open-Meteo.com (https://open-meteo.com/)")
	);

	@Shadow
	private LevelLoadingScreen.Reason reason;

	@Inject(method = "render", at = @At("TAIL"))
	private void tellus$renderContributions(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (this.reason != LevelLoadingScreen.Reason.OTHER) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
		int availableWidth = Math.max(40, width - TEXT_PADDING * 2);
		int wrapWidth = Math.min(MAX_TEXT_WIDTH, availableWidth);
		List<@NonNull FormattedCharSequence> lines = new ArrayList<>();
		for (Component line : CONTRIBUTIONS) {
			for (FormattedCharSequence wrapped : font.split(line, wrapWidth)) {
				lines.add(Objects.requireNonNull(wrapped, "wrappedLine"));
			}
		}
		if (lines.isEmpty()) {
			return;
		}
		int totalHeight = lines.size() * font.lineHeight + (lines.size() - 1) * LINE_SPACING;
		int centerX = width / 2;
		int baseY = height / 2 + BASELINE_OFFSET;
		int maxY = height - totalHeight - TEXT_PADDING;
		int startY = Math.min(baseY, maxY);

		int y = Math.max(TEXT_PADDING, startY);
		for (@NonNull FormattedCharSequence line : lines) {
			int lineWidth = font.width(line);
			int x = centerX - lineWidth / 2;
			graphics.drawString(font, line, x, y, TEXT_COLOR, true);
			y += font.lineHeight + LINE_SPACING;
		}
	}
}
