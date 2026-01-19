package com.yucareux.tellus.client.preview;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class TerrainPreviewWidget extends AbstractWidget implements AutoCloseable {
	private static final float DEFAULT_ROTATION_X = (float) Math.toRadians(18.0);
	private static final float DEFAULT_ROTATION_Y = (float) Math.toRadians(18.0);
	private static final float DEFAULT_ZOOM = 1.4f;
	private static final float MIN_ROTATION_X = (float) Math.toRadians(0.0);
	private static final float MAX_ROTATION_X = (float) Math.toRadians(80.0);
	private static final float MIN_ZOOM = 0.5f;
	private static final float MAX_ZOOM = 4.0f;
	private static final float ROTATION_SPEED = 0.01f;
	private static final float ZOOM_SPEED = 0.1f;
	private static final float AUTO_ROTATION_SPEED = -0.0022f;
	private static final long AUTO_RESUME_DELAY_MS = 1200L;
	private static final int FULLSCREEN_BUTTON_SIZE = 20;
	private static final int FULLSCREEN_BUTTON_PADDING = 6;
	private static final @NonNull Component FULLSCREEN_LABEL = Component.literal("[ ]");
	private static final int LOADING_PANEL_BG = 0xCC101010;
	private static final int LOADING_PANEL_BORDER = 0xFF3A3A3A;
	private static final int LOADING_BAR_BG = 0xFF1A1A1A;
	private static final int LOADING_BAR_BORDER = 0xFF2D2D2D;
	private static final int LOADING_BAR_FILL = 0xFF3FBF4F;
	private static final int LOADING_TEXT = 0xFFFFFFFF;

	private final TerrainPreview preview;
	private final boolean ownsPreview;
	private final Button fullscreenButton;
	private @Nullable Runnable fullscreenAction;
	private boolean dragging;
	private float rotationX = DEFAULT_ROTATION_X;
	private float rotationY = DEFAULT_ROTATION_Y;
	private float zoom = DEFAULT_ZOOM;
	private long lastInteractionTime;

	public TerrainPreviewWidget(int x, int y, int width, int height) {
		this(x, y, width, height, new TerrainPreview(), true);
	}

	public TerrainPreviewWidget(int x, int y, int width, int height, TerrainPreview preview) {
		this(x, y, width, height, preview, false);
	}

	private TerrainPreviewWidget(int x, int y, int width, int height, TerrainPreview preview, boolean ownsPreview) {
		super(x, y, width, height, Component.empty());
		this.preview = preview;
		this.ownsPreview = ownsPreview;
		this.fullscreenButton = Button.builder(FULLSCREEN_LABEL, button -> {
			if (this.fullscreenAction != null) {
				this.fullscreenAction.run();
			}
		}).bounds(0, 0, FULLSCREEN_BUTTON_SIZE, FULLSCREEN_BUTTON_SIZE).build();
		this.fullscreenButton.active = false;
	}

	public void requestRebuild(EarthGeneratorSettings settings) {
		this.preview.requestRebuild(settings);
	}

	public void setFullscreenAction(@Nullable Runnable action) {
		this.fullscreenAction = action;
		this.fullscreenButton.active = action != null;
	}

	public TerrainPreview getPreview() {
		return this.preview;
	}

	public ViewState getViewState() {
		return new ViewState(this.rotationX, this.rotationY, this.zoom);
	}

	public void setViewState(@NonNull ViewState state) {
		this.rotationX = Mth.clamp(state.rotationX(), MIN_ROTATION_X, MAX_ROTATION_X);
		this.rotationY = state.rotationY();
		this.zoom = Mth.clamp(state.zoom(), MIN_ZOOM, MAX_ZOOM);
	}

	public void tick() {
		this.preview.tick();
		long now = System.currentTimeMillis();
		if (!this.dragging && now - this.lastInteractionTime > AUTO_RESUME_DELAY_MS) {
			this.rotationY += AUTO_ROTATION_SPEED;
		}
	}

	@Override
	protected void renderWidget(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		this.renderBlurredBackground(graphics);

		int inset = 0;
		int contentX = this.getX() + inset;
		int contentY = this.getY() + inset;
		int contentWidth = Math.max(1, this.width - inset * 2);
		int contentHeight = Math.max(1, this.height - inset * 2);

		this.preview.render(graphics, contentX, contentY, contentWidth, contentHeight, this.rotationX, this.rotationY, this.zoom);
		renderLoadingOverlay(graphics, contentX, contentY, contentWidth, contentHeight);
		renderFullscreenButton(graphics, mouseX, mouseY, delta);
	}

	@Override
	public void onClick(@NonNull MouseButtonEvent event, boolean doubleClick) {
		if (this.fullscreenAction != null && this.fullscreenButton.isMouseOver(event.x(), event.y())) {
			this.fullscreenButton.mouseClicked(event, doubleClick);
			return;
		}
		if (event.button() == 0) {
			this.dragging = true;
			this.lastInteractionTime = System.currentTimeMillis();
		}
	}

	@Override
	protected void onDrag(@NonNull MouseButtonEvent event, double deltaX, double deltaY) {
		if (this.dragging) {
			this.rotationY += (float) deltaX * ROTATION_SPEED;
			this.rotationX = Mth.clamp(this.rotationX + (float) deltaY * ROTATION_SPEED, MIN_ROTATION_X, MAX_ROTATION_X);
			this.lastInteractionTime = System.currentTimeMillis();
		}
	}

	@Override
	public void onRelease(@NonNull MouseButtonEvent event) {
		if (this.fullscreenAction != null) {
			this.fullscreenButton.mouseReleased(event);
		}
		this.dragging = false;
		this.lastInteractionTime = System.currentTimeMillis();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (this.fullscreenAction != null && this.fullscreenButton.isMouseOver(mouseX, mouseY)) {
			return false;
		}
		if (this.isMouseOver(mouseX, mouseY)) {
			this.zoom = Mth.clamp(this.zoom + (float) verticalAmount * ZOOM_SPEED, MIN_ZOOM, MAX_ZOOM);
			this.lastInteractionTime = System.currentTimeMillis();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	protected void updateWidgetNarration(@NonNull NarrationElementOutput narration) {
	}

	private void renderBlurredBackground(GuiGraphics graphics) {
		int left = this.getX();
		int top = this.getY();
		int right = this.getX() + this.width;
		int bottom = this.getY() + this.height;

		graphics.fill(left, top, right, bottom, 0x88000000);
		graphics.fill(left + 1, top + 1, right - 1, bottom - 1, 0x33000000);
		graphics.fillGradient(left, top, right, top + 8, 0x44000000, 0x14000000);
		graphics.fillGradient(left, bottom - 8, right, bottom, 0x14000000, 0x44000000);
		graphics.renderOutline(left, top, this.width, this.height, 0x18000000);
	}

	private void renderLoadingOverlay(GuiGraphics graphics, int x, int y, int width, int height) {
		if (!this.preview.isLoading()) {
			return;
		}
		TerrainPreview.PreviewStatus status = this.preview.getStatus();
		if (status.stage() == TerrainPreview.PreviewStage.COMPLETE) {
			return;
		}

		String label = status.stage() == TerrainPreview.PreviewStage.DOWNLOADING
				? "Downloading Data"
				: "Loading terrain";
		int percent = Mth.clamp(Math.round(status.progress() * 100.0f), 0, 100);
		String percentText = percent + "%";
		var font = Minecraft.getInstance().font;

		int padding = 6;
		int maxInnerWidth = Math.max(20, width - padding * 2 - 4);
		int barWidth = Math.min(180, maxInnerWidth);
		int textLineWidth = font.width(label) + font.width(percentText) + 12;
		int innerWidth = Math.min(maxInnerWidth, Math.max(barWidth, textLineWidth));
		int panelWidth = innerWidth + padding * 2;
		int barHeight = 8;
		int panelHeight = padding * 3 + font.lineHeight + barHeight;

		int panelX = x + (width - panelWidth) / 2;
		int panelY = y + height - panelHeight - 10;
		panelY = Mth.clamp(panelY, y + 6, y + height - panelHeight - 6);

		graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, LOADING_PANEL_BG);
		graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, LOADING_PANEL_BORDER);

		int textY = panelY + padding;
		graphics.drawString(font, label, panelX + padding, textY, LOADING_TEXT, true);
		graphics.drawString(font, percentText, panelX + panelWidth - padding - font.width(percentText), textY, LOADING_TEXT, true);

		int barX = panelX + padding;
		int barY = textY + font.lineHeight + padding;
		int barInnerWidth = innerWidth - 2;
		graphics.fill(barX, barY, barX + innerWidth, barY + barHeight, LOADING_BAR_BG);
		graphics.renderOutline(barX, barY, innerWidth, barHeight, LOADING_BAR_BORDER);
		float clamped = Mth.clamp(status.progress(), 0.0f, 1.0f);
		int fillWidth = Math.round(barInnerWidth * clamped);
		if (fillWidth == 0 && clamped > 0.0f) {
			fillWidth = 1;
		}
		if (fillWidth > 0) {
			graphics.fill(barX + 1, barY + 1, barX + 1 + fillWidth, barY + barHeight - 1, LOADING_BAR_FILL);
		}
	}

	private void renderFullscreenButton(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		if (this.fullscreenAction == null) {
			return;
		}
		int buttonX = this.getX() + this.width - FULLSCREEN_BUTTON_SIZE - FULLSCREEN_BUTTON_PADDING;
		int buttonY = this.getY() + FULLSCREEN_BUTTON_PADDING;
		this.fullscreenButton.setX(buttonX);
		this.fullscreenButton.setY(buttonY);
		this.fullscreenButton.setWidth(FULLSCREEN_BUTTON_SIZE);
		this.fullscreenButton.setHeight(FULLSCREEN_BUTTON_SIZE);
		this.fullscreenButton.render(graphics, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		if (this.ownsPreview) {
			this.preview.close();
		}
	}

	public record ViewState(float rotationX, float rotationY, float zoom) {}
}
