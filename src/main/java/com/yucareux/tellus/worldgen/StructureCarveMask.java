package com.yucareux.tellus.worldgen;

import java.util.Arrays;

public final class StructureCarveMask {
	private final int[] minY;
	private final int[] maxY;

	public StructureCarveMask() {
		this.minY = new int[16 * 16];
		this.maxY = new int[16 * 16];
		Arrays.fill(this.minY, Integer.MAX_VALUE);
		Arrays.fill(this.maxY, Integer.MIN_VALUE);
	}

	public void expandRange(int localX, int localZ, int min, int max) {
		int index = localZ * 16 + localX;
		if (min < this.minY[index]) {
			this.minY[index] = min;
		}
		if (max > this.maxY[index]) {
			this.maxY[index] = max;
		}
	}

	public boolean contains(int localX, int localZ, int y) {
		int index = localZ * 16 + localX;
		int min = this.minY[index];
		if (min == Integer.MAX_VALUE) {
			return false;
		}
		return y >= min && y <= this.maxY[index];
	}
}
