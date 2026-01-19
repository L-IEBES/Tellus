package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.preview.TerrainPreview;
import com.yucareux.tellus.client.preview.TerrainPreviewWidget;
import com.yucareux.tellus.client.preview.TerrainPreviewWidget.ViewState;
import java.util.Objects;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

public class TerrainPreviewScreen extends Screen {
	private final @NonNull EarthCustomizeScreen parent;
	private final @NonNull TerrainPreview preview;
	private final @NonNull ViewState initialView;

	private TerrainPreviewWidget previewWidget;

	public TerrainPreviewScreen(
			@NonNull EarthCustomizeScreen parent,
			@NonNull TerrainPreview preview,
			@NonNull ViewState initialView
	) {
		super(Component.empty());
		this.parent = Objects.requireNonNull(parent, "parent");
		this.preview = Objects.requireNonNull(preview, "preview");
		this.initialView = Objects.requireNonNull(initialView, "initialView");
	}

	@Override
	protected void init() {
		if (this.previewWidget != null) {
			this.previewWidget.close();
		}

		this.previewWidget = new TerrainPreviewWidget(0, 0, this.width, this.height, this.preview);
		this.previewWidget.setViewState(this.initialView);
		this.previewWidget.setFullscreenAction(this::onClose);
		this.addRenderableOnly(this.previewWidget);

		int buttonY = this.height - 28;
		this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> this.onClose())
				.bounds(this.width / 2 - 75, buttonY, 150, 20)
				.build());

		this.addWidget(this.previewWidget);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.previewWidget != null) {
			this.previewWidget.tick();
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			if (this.previewWidget != null) {
				ViewState viewState = Objects.requireNonNull(
						this.previewWidget.getViewState(),
						"viewState"
				);
				this.parent.applyPreviewViewState(viewState);
			}
			this.minecraft.setScreen(this.parent);
		}
	}

	@Override
	public void removed() {
		if (this.previewWidget != null) {
			this.previewWidget.close();
		}
	}
}
