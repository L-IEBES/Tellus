package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

public final class ArcticDemElevationSource {
	private static final double EQUATOR_CIRCUMFERENCE = 40075017.0;
	private static final double METERS_PER_DEGREE = EQUATOR_CIRCUMFERENCE / 360.0;
	private static final double MIN_LAT = 50.593818479;
	private static final double MAX_LAT = 83.988230361;
	private static final double MIN_LON = -180.0;
	private static final double MAX_LON = 180.0;
	private static final String BASE_URL =
			"https://pgc-opendata-dems.s3.us-west-2.amazonaws.com/arcticdem/mosaics/v4.1";
	private static final double BUCKET_SIZE_METERS = 100000.0;
	private static final int HTTP_CONNECT_TIMEOUT = 8000;
	private static final int HTTP_READ_TIMEOUT = 8000;
	private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
	private static final double NO_DATA = -9999.0;
	private static final int MAX_FILE_CACHE = intProperty("tellus.arcticdem.cacheFiles", 8);
	private static final int MAX_TILE_CACHE = intProperty("tellus.arcticdem.cacheTiles", 16);
	private static final long FAILURE_COOLDOWN_MS = longProperty("tellus.arcticdem.retryMs", 60000L);
	private static final boolean DEBUG_DEM = Boolean.getBoolean("tellus.debug.dem");
	private static final double DEBUG_SAMPLE_LAT = doubleProperty("tellus.debug.dem.sampleLat", Double.NaN);
	private static final double DEBUG_SAMPLE_LON = doubleProperty("tellus.debug.dem.sampleLon", Double.NaN);
	private static final double DEBUG_SAMPLE_EPS = doubleProperty("tellus.debug.dem.sampleEps", 1.0e-4);
	private static final double PROJ_LAT_TS = Math.toRadians(70.0);
	private static final double PROJ_LON_0 = Math.toRadians(-45.0);
	private static final double PROJ_A = 6378137.0;
	private static final double PROJ_E = 0.081819190843;
	private static final double PROJ_SIN_TS = Math.sin(PROJ_LAT_TS);
	private static final double PROJ_T_C = Math.tan(Math.PI / 4.0 - PROJ_LAT_TS / 2.0)
			/ Math.pow((1.0 - PROJ_E * PROJ_SIN_TS) / (1.0 + PROJ_E * PROJ_SIN_TS), PROJ_E / 2.0);
	private static final double PROJ_M_C = Math.cos(PROJ_LAT_TS)
			/ Math.sqrt(1.0 - PROJ_E * PROJ_E * PROJ_SIN_TS * PROJ_SIN_TS);

	private final Path cacheRoot;
	private final LoadingCache<Tier, TileIndex> indexCache;
	private final LoadingCache<String, TileFile> fileCache;
	private final ThreadLocal<TileLookup> lookupCache = ThreadLocal.withInitial(TileLookup::new);
	private final boolean enabled = Boolean.parseBoolean(System.getProperty("tellus.arcticdem.enabled", "true"));
	private volatile long suspendedUntilMs;
	private final AtomicInteger debugMask = new AtomicInteger();
	private final AtomicInteger debugSampleMask = new AtomicInteger();
	private volatile Tier lastLoggedTier;

	public ArcticDemElevationSource() {
		this.cacheRoot = FabricLoader.getInstance().getGameDir().resolve("tellus/cache/elevation-arcticdem/v4.1");
		this.indexCache = CacheBuilder.newBuilder()
				.maximumSize(Tier.values().length)
				.build(new CacheLoader<>() {
					@Override
					public TileIndex load(Tier tier) throws Exception {
						return TileIndex.load(tier, cacheRoot);
					}
				});
		this.fileCache = CacheBuilder.newBuilder()
				.maximumSize(MAX_FILE_CACHE)
				.build(new CacheLoader<>() {
					@Override
					public TileFile load(String url) throws Exception {
						return TileFile.open(url);
					}
				});
	}

	public double sampleElevationMeters(double blockX, double blockZ, double worldScale) {
		if (!this.enabled || isSuspended()) {
			if (!this.enabled) {
				debugOnce(DebugReason.DISABLED, "ArcticDEM disabled (tellus.arcticdem.enabled=false).");
			} else {
				debugOnce(DebugReason.SUSPENDED, "ArcticDEM suspended; falling back to Terrarium.");
			}
			return Double.NaN;
		}
		if (worldScale <= 0.0) {
			return Double.NaN;
		}

		LatLon latLon = toLatLon(blockX, blockZ, worldScale);
		double lat = latLon.lat();
		double lon = latLon.lon();
		if (!isInCoverage(lat, lon)) {
			debugOnce(DebugReason.OUT_OF_BOUNDS, "ArcticDEM out of coverage at lat={}, lon={}.", lat, lon);
			return Double.NaN;
		}

		Tier tier = Tier.forScale(worldScale);
		debugTier(tier, worldScale);
		TileIndex index = getIndex(tier);
		if (index == null) {
			debugOnce(DebugReason.INDEX_FAILED, "ArcticDEM index unavailable; falling back to Terrarium.");
			return Double.NaN;
		}

		Projection projection = project(lat, lon);
		if (projection == null) {
			debugOnce(DebugReason.PROJECTION_FAILED, "ArcticDEM projection failed for lat={}, lon={}.", lat, lon);
			return Double.NaN;
		}

		TileLookup lookup = this.lookupCache.get();
		TileEntry tile = lookup.get(tier, projection.x, projection.y);
		if (tile == null) {
			tile = index.findTile(projection.x, projection.y);
			lookup.update(tier, tile);
		}
		if (tile == null) {
			debugOnce(DebugReason.TILE_NOT_FOUND, "ArcticDEM tile not found for x={}, y={}, tier={}.",
					projection.x, projection.y, tier.id);
			return Double.NaN;
		}

		TileFile tileFile = getTileFile(tile.url);
		if (tileFile == null) {
			debugOnce(DebugReason.TILEFILE_FAILED, "ArcticDEM tile load failed for {}.", tile.url);
			return Double.NaN;
		}

		double value;
		boolean probeSample = shouldDebugSample(lat, lon);
		if (probeSample && this.debugSampleMask.compareAndSet(0, 1)) {
			TileFile.SampleResult result = tileFile.sampleWithDebug(tile, projection.x, projection.y, index.pixelSize);
			value = result.value();
			if (DEBUG_DEM && result.debug() != null) {
				TileFile.SampleDebug debug = result.debug();
				Tellus.LOGGER.info(
					"ArcticDEM probe lat={}, lon={}, tier={}, tile={}, local=({},{}) px=({},{})->({},{}), v00={}, v10={}, v01={}, v11={}, value={}",
					String.format("%.5f", lat),
					String.format("%.5f", lon),
					tier.id,
					tile.url,
					String.format("%.2f", debug.localX()),
					String.format("%.2f", debug.localY()),
					debug.x0(),
					debug.y0(),
					debug.x1(),
					debug.y1(),
					Float.isFinite(debug.v00()) ? String.format("%.2f", debug.v00()) : "NaN",
					Float.isFinite(debug.v10()) ? String.format("%.2f", debug.v10()) : "NaN",
					Float.isFinite(debug.v01()) ? String.format("%.2f", debug.v01()) : "NaN",
					Float.isFinite(debug.v11()) ? String.format("%.2f", debug.v11()) : "NaN",
					Double.isFinite(result.value()) ? String.format("%.2f", result.value()) : "NaN"
				);
			}
		} else {
			value = tileFile.sample(tile, projection.x, projection.y, index.pixelSize);
		}
		if (tileFile.hasFailure()) {
			suspend();
		}
		if (Double.isNaN(value) || value <= NO_DATA + 1.0) {
			debugOnce(DebugReason.NO_DATA, "ArcticDEM returned no-data for {}.", tile.url);
			return Double.NaN;
		}
		debugOnce(DebugReason.SUCCESS, "ArcticDEM active (tier {}, tile {}).", tier.id, tile.url);
		return value;
	}

	public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
		if (!this.enabled || isSuspended()) {
			return;
		}
		if (worldScale <= 0.0 || radius <= 0) {
			return;
		}
		LatLon latLon = toLatLon(blockX, blockZ, worldScale);
		double lat = latLon.lat();
		double lon = latLon.lon();
		if (!isInCoverage(lat, lon)) {
			return;
		}
		Tier tier = Tier.forScale(worldScale);
		TileIndex index = getIndex(tier);
		if (index == null) {
			return;
		}
		Projection projection = project(lat, lon);
		if (projection == null) {
			return;
		}
		double span = index.tileSpanMeters;
		int clampedRadius = Math.max(1, radius);
		for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
			for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
				double x = projection.x + dx * span;
				double y = projection.y + dz * span;
				TileEntry tile = index.findTile(x, y);
				if (tile == null) {
					continue;
				}
				try {
					this.fileCache.get(tile.url);
				} catch (ExecutionException e) {
					Tellus.LOGGER.debug("Failed to prefetch ArcticDEM tile {}", tile.url, e);
				}
			}
		}
	}

	private static LatLon toLatLon(double blockX, double blockZ, double worldScale) {
		double blocksPerDegree = METERS_PER_DEGREE / worldScale;
		double lon = blockX / blocksPerDegree;
		double lat = -blockZ / blocksPerDegree;
		return new LatLon(lat, lon);
	}

	private static boolean isInCoverage(double lat, double lon) {
		return lat >= MIN_LAT && lat <= MAX_LAT && lon >= MIN_LON && lon <= MAX_LON;
	}

	private TileIndex getIndex(Tier tier) {
		try {
			return this.indexCache.get(tier);
		} catch (ExecutionException e) {
			Tellus.LOGGER.warn("Failed to load ArcticDEM index for {}", tier.id, e);
			suspend();
			return null;
		}
	}

	private TileFile getTileFile(String url) {
		try {
			return this.fileCache.get(url);
		} catch (ExecutionException e) {
			Tellus.LOGGER.debug("Failed to load ArcticDEM tile {}", url, e);
			suspend();
			return null;
		}
	}

	private boolean isSuspended() {
		return System.currentTimeMillis() < this.suspendedUntilMs;
	}

	private void suspend() {
		this.suspendedUntilMs = Math.max(this.suspendedUntilMs, System.currentTimeMillis() + FAILURE_COOLDOWN_MS);
	}

	private void debugOnce(DebugReason reason, String message, Object... args) {
		if (!DEBUG_DEM) {
			return;
		}
		int bit = 1 << reason.ordinal();
		while (true) {
			int previous = this.debugMask.get();
			if ((previous & bit) != 0) {
				return;
			}
			if (this.debugMask.compareAndSet(previous, previous | bit)) {
				Tellus.LOGGER.info(message, args);
				return;
			}
		}
	}

	private void debugTier(Tier tier, double worldScale) {
		if (!DEBUG_DEM) {
			return;
		}
		if (tier != this.lastLoggedTier) {
			this.lastLoggedTier = tier;
			Tellus.LOGGER.info("ArcticDEM tier set to {} (worldScale {}).", tier.id, String.format("%.2f", worldScale));
		}
	}

	private boolean shouldDebugSample(double lat, double lon) {
		if (!DEBUG_DEM) {
			return false;
		}
		if (!Double.isFinite(DEBUG_SAMPLE_LAT) || !Double.isFinite(DEBUG_SAMPLE_LON)) {
			return false;
		}
		return Math.abs(lat - DEBUG_SAMPLE_LAT) <= DEBUG_SAMPLE_EPS
				&& Math.abs(lon - DEBUG_SAMPLE_LON) <= DEBUG_SAMPLE_EPS;
	}

	private enum DebugReason {
		DISABLED,
		SUSPENDED,
		OUT_OF_BOUNDS,
		INDEX_FAILED,
		PROJECTION_FAILED,
		TILE_NOT_FOUND,
		TILEFILE_FAILED,
		NO_DATA,
		SUCCESS
	}

	private static Projection project(double latDeg, double lonDeg) {
		if (latDeg <= 0.0) {
			return null;
		}
		double lat = Math.toRadians(latDeg);
		double lon = Math.toRadians(lonDeg);
		double sinLat = Math.sin(lat);
		double t = Math.tan(Math.PI / 4.0 - lat / 2.0)
				/ Math.pow((1.0 - PROJ_E * sinLat) / (1.0 + PROJ_E * sinLat), PROJ_E / 2.0);
		double rho = PROJ_A * PROJ_M_C * t / PROJ_T_C;
		double x = rho * Math.sin(lon - PROJ_LON_0);
		double y = -rho * Math.cos(lon - PROJ_LON_0);
		return new Projection(x, y);
	}

	private static int intProperty(String key, int defaultValue) {
		String value = System.getProperty(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Math.max(1, Integer.parseInt(value));
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private static long longProperty(String key, long defaultValue) {
		String value = System.getProperty(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Math.max(1L, Long.parseLong(value));
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private static double doubleProperty(String key, double defaultValue) {
		String value = System.getProperty(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private static HttpURLConnection openConnection(URI uri) throws IOException {
		return openConnection(uri, null);
	}

	private static HttpURLConnection openConnection(URI uri, String rangeHeader) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
		connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
		connection.setReadTimeout(HTTP_READ_TIMEOUT);
		connection.setRequestProperty("User-Agent", HTTP_USER_AGENT);
		if (rangeHeader != null) {
			connection.setRequestProperty("Range", rangeHeader);
		}
		return connection;
	}

	private enum Tier {
		TWO_M("2m", 2.0),
		TEN_M("10m", 10.0),
		THIRTY_TWO_M("32m", 32.0);

		private final String id;
		private final double meters;

		Tier(String id, double meters) {
			this.id = id;
			this.meters = meters;
		}

		private static Tier forScale(double worldScale) {
			if (worldScale >= THIRTY_TWO_M.meters) {
				return THIRTY_TWO_M;
			}
			if (worldScale >= TEN_M.meters) {
				return TEN_M;
			}
			return TWO_M;
		}
	}

	private record Projection(double x, double y) {
	}

	private record LatLon(double lat, double lon) {
	}

	private static final class TileLookup {
		private Tier tier;
		private TileEntry tile;

		private TileEntry get(Tier tier, double x, double y) {
			if (this.tile == null || this.tier != tier) {
				return null;
			}
			return this.tile.contains(x, y) ? this.tile : null;
		}

		private void update(Tier tier, TileEntry tile) {
			this.tier = tier;
			this.tile = tile;
		}
	}

	private record TileEntry(String url, double xMin, double yMin, double xMax, double yMax) {
		private boolean contains(double x, double y) {
			return x >= xMin && x <= xMax && y >= yMin && y <= yMax;
		}

		private double widthMeters() {
			return xMax - xMin;
		}
	}

	private static final class TileIndex {
		private static final Pattern GEO_TRANSFORM_PATTERN =
				Pattern.compile("<GeoTransform>([^<]+)</GeoTransform>");
		private static final Pattern TILE_PATTERN = Pattern.compile(
				"<ComplexSource>\\s*<SourceFilename[^>]*>([^<]+)</SourceFilename>.*?"
						+ "<SourceProperties[^>]*RasterXSize=\\\"(\\d+)\\\" RasterYSize=\\\"(\\d+)\\\".*?>.*?"
						+ "<DstRect xOff=\\\"(\\d+)\\\" yOff=\\\"(\\d+)\\\" xSize=\\\"(\\d+)\\\" ySize=\\\"(\\d+)\\\"",
				Pattern.DOTALL
		);

		private final double pixelSize;
		private final double minX;
		private final double minY;
		private final double maxX;
		private final double maxY;
		private final double tileSpanMeters;
		private final Map<Long, List<TileEntry>> buckets;

		private TileIndex(
				double pixelSize,
				double minX,
				double minY,
				double maxX,
				double maxY,
				double tileSpanMeters,
				Map<Long, List<TileEntry>> buckets
		) {
			this.pixelSize = pixelSize;
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
			this.tileSpanMeters = tileSpanMeters;
			this.buckets = buckets;
		}

		private TileEntry findTile(double x, double y) {
			if (x < this.minX || x > this.maxX || y < this.minY || y > this.maxY) {
				return null;
			}
			int bx = (int) Math.floor((x - this.minX) / BUCKET_SIZE_METERS);
			int by = (int) Math.floor((y - this.minY) / BUCKET_SIZE_METERS);
			long key = bucketKey(bx, by);
			List<TileEntry> bucket = this.buckets.get(key);
			if (bucket == null) {
				return null;
			}
			for (TileEntry tile : bucket) {
				if (tile.contains(x, y)) {
					return tile;
				}
			}
			return null;
		}

		private static TileIndex load(Tier tier, Path cacheRoot) throws IOException {
			Path cachePath = cacheRoot.resolve(tier.id + "_dem_tiles.vrt");
			String vrtText;
			if (Files.exists(cachePath)) {
				vrtText = new String(Files.readAllBytes(cachePath), StandardCharsets.UTF_8);
			} else {
				vrtText = downloadVrt(tier, cachePath);
			}

			Matcher geoMatcher = GEO_TRANSFORM_PATTERN.matcher(vrtText);
			if (!geoMatcher.find()) {
				throw new IOException("Missing GeoTransform in ArcticDEM VRT");
			}
			double[] transform = parseGeoTransform(geoMatcher.group(1));
			double originX = transform[0];
			double pixelSizeX = transform[1];
			double originY = transform[3];
			double pixelSizeY = transform[5];

			List<TileEntry> tiles = new ArrayList<>();
			double minX = Double.POSITIVE_INFINITY;
			double minY = Double.POSITIVE_INFINITY;
			double maxX = Double.NEGATIVE_INFINITY;
			double maxY = Double.NEGATIVE_INFINITY;

			Matcher tileMatcher = TILE_PATTERN.matcher(vrtText);
			while (tileMatcher.find()) {
				String src = tileMatcher.group(1);
				long xOff = Long.parseLong(tileMatcher.group(4));
				long yOff = Long.parseLong(tileMatcher.group(5));
				int xSize = Integer.parseInt(tileMatcher.group(6));
				int ySize = Integer.parseInt(tileMatcher.group(7));
				double xMin = originX + xOff * pixelSizeX;
				double yMax = originY + yOff * pixelSizeY;
				double xMax = xMin + xSize * pixelSizeX;
				double yMin = yMax + ySize * pixelSizeY;
				String url = toHttpUrl(src);
				TileEntry tile = new TileEntry(url, Math.min(xMin, xMax), Math.min(yMin, yMax),
						Math.max(xMin, xMax), Math.max(yMin, yMax));
				tiles.add(tile);
				minX = Math.min(minX, tile.xMin());
				minY = Math.min(minY, tile.yMin());
				maxX = Math.max(maxX, tile.xMax());
				maxY = Math.max(maxY, tile.yMax());
			}

			if (tiles.isEmpty()) {
				throw new IOException("No tiles found in ArcticDEM VRT");
			}

			double tileSpanMeters = medianTileSpan(tiles);
			Map<Long, List<TileEntry>> buckets = buildBuckets(tiles, minX, minY);

			return new TileIndex(Math.abs(pixelSizeX), minX, minY, maxX, maxY, tileSpanMeters, buckets);
		}

		private static Map<Long, List<TileEntry>> buildBuckets(List<TileEntry> tiles, double minX, double minY) {
			Map<Long, List<TileEntry>> buckets = new LinkedHashMap<>();
			for (TileEntry tile : tiles) {
				int bx0 = (int) Math.floor((tile.xMin() - minX) / BUCKET_SIZE_METERS);
				int bx1 = (int) Math.floor((tile.xMax() - minX) / BUCKET_SIZE_METERS);
				int by0 = (int) Math.floor((tile.yMin() - minY) / BUCKET_SIZE_METERS);
				int by1 = (int) Math.floor((tile.yMax() - minY) / BUCKET_SIZE_METERS);
				for (int by = by0; by <= by1; by++) {
					for (int bx = bx0; bx <= bx1; bx++) {
						long key = bucketKey(bx, by);
						buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tile);
					}
				}
			}
			return buckets;
		}

		private static double medianTileSpan(List<TileEntry> tiles) {
			double[] spans = new double[tiles.size()];
			for (int i = 0; i < tiles.size(); i++) {
				spans[i] = tiles.get(i).widthMeters();
			}
			Arrays.sort(spans);
			return spans[spans.length / 2];
		}

		private static double[] parseGeoTransform(String text) {
			String[] parts = text.trim().split(",");
			if (parts.length < 6) {
				throw new IllegalArgumentException("Invalid GeoTransform");
			}
			double[] values = new double[6];
			for (int i = 0; i < 6; i++) {
				values[i] = Double.parseDouble(parts[i].trim());
			}
			return values;
		}

		private static String downloadVrt(Tier tier, Path cachePath) throws IOException {
			URI uri = URI.create(String.format("%s/%s_dem_tiles.vrt", BASE_URL, tier.id));
			HttpURLConnection connection = openConnection(uri);
			byte[] data;
			try (InputStream input = connection.getInputStream()) {
				data = input.readAllBytes();
			}
			Files.createDirectories(cachePath.getParent());
			Files.write(cachePath, data);
			return new String(data, StandardCharsets.UTF_8);
		}

		private static String toHttpUrl(String sourceFilename) {
			String trimmed = sourceFilename.trim();
			String prefix = "/vsis3/";
			if (trimmed.startsWith(prefix)) {
				String path = trimmed.substring(prefix.length());
				int slash = path.indexOf('/');
				if (slash > 0) {
					String bucket = path.substring(0, slash);
					String key = path.substring(slash + 1);
					return "https://" + bucket + ".s3.us-west-2.amazonaws.com/" + key;
				}
			}
			return trimmed;
		}
	}

	private static long bucketKey(int bx, int by) {
		return ((long) bx << 32) ^ (by & 0xffffffffL);
	}

	private static final class TileFile {
		private static final int TAG_IMAGE_WIDTH = 256;
		private static final int TAG_IMAGE_HEIGHT = 257;
		private static final int TAG_TILE_WIDTH = 322;
		private static final int TAG_TILE_HEIGHT = 323;
		private static final int TAG_TILE_OFFSETS = 324;
		private static final int TAG_TILE_BYTE_COUNTS = 325;
		private static final int TAG_COMPRESSION = 259;
		private static final int TAG_BITS_PER_SAMPLE = 258;
		private static final int TAG_SAMPLE_FORMAT = 339;
		private static final int TAG_PREDICTOR = 317;
		private static final int TAG_SAMPLES_PER_PIXEL = 277;

		private static final int TYPE_SHORT = 3;
		private static final int TYPE_LONG = 4;
		private static final int TYPE_DOUBLE = 12;
		private static final int TYPE_LONG8 = 16;

		private static final int COMPRESSION_LZW = 5;
		private static final int COMPRESSION_DEFLATE = 8;

		private final String url;
		private final RangeReader reader;
		private final ByteOrder order;
		private final int width;
		private final int height;
		private final int tileWidth;
		private final int tileHeight;
		private final int tilesPerRow;
		private final int compression;
		private final int predictor;
		private final int bytesPerSample;
		private final int samplesPerPixel;
		private final long[] tileOffsets;
		private final int[] tileByteCounts;
		private final Map<Integer, float[]> tileCache;
		private volatile boolean failed;

		private record SampleDebug(
			double localX,
			double localY,
			int x0,
			int y0,
			int x1,
			int y1,
			float v00,
			float v10,
			float v01,
			float v11
		) {
		}

		private record SampleResult(double value, SampleDebug debug) {
		}

		private TileFile(String url,
				RangeReader reader,
				ByteOrder order,
				int width,
				int height,
				int tileWidth,
				int tileHeight,
				int compression,
				int predictor,
				int bytesPerSample,
				int samplesPerPixel,
				long[] tileOffsets,
				int[] tileByteCounts
		) {
			this.url = url;
			this.reader = reader;
			this.order = order;
			this.width = width;
			this.height = height;
			this.tileWidth = tileWidth;
			this.tileHeight = tileHeight;
			this.tilesPerRow = (int) Math.ceil(width / (double) tileWidth);
			this.compression = compression;
			this.predictor = predictor;
			this.bytesPerSample = bytesPerSample;
			this.samplesPerPixel = samplesPerPixel;
			this.tileOffsets = tileOffsets;
			this.tileByteCounts = tileByteCounts;
			this.tileCache = new LinkedHashMap<>(MAX_TILE_CACHE, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Integer, float[]> eldest) {
					return size() > MAX_TILE_CACHE;
				}
			};
		}

		private double sample(TileEntry tile, double x, double y, double pixelSize) {
			if (this.failed) {
				return Double.NaN;
			}
			double localX = (x - tile.xMin()) / pixelSize;
			double localY = (tile.yMax() - y) / pixelSize;
			if (!isWithinImage(localX, localY)) {
				return Double.NaN;
			}
			return sampleBilinear(localX, localY);
		}

		private SampleResult sampleWithDebug(TileEntry tile, double x, double y, double pixelSize) {
			if (this.failed) {
				return new SampleResult(Double.NaN, null);
			}
			double localX = (x - tile.xMin()) / pixelSize;
			double localY = (tile.yMax() - y) / pixelSize;
			if (!isWithinImage(localX, localY)) {
				return new SampleResult(Double.NaN, null);
			}
			int x0 = Mth.clamp(Mth.floor(localX), 0, this.width - 1);
			int y0 = Mth.clamp(Mth.floor(localY), 0, this.height - 1);
			int x1 = Math.min(x0 + 1, this.width - 1);
			int y1 = Math.min(y0 + 1, this.height - 1);
			float v00 = sampleValue(x0, y0);
			float v10 = sampleValue(x1, y0);
			float v01 = sampleValue(x0, y1);
			float v11 = sampleValue(x1, y1);
			double dx = localX - x0;
			double dy = localY - y0;
			double value = blendFiniteSamples(v00, v10, v01, v11, dx, dy);
			return new SampleResult(value, new SampleDebug(localX, localY, x0, y0, x1, y1, v00, v10, v01, v11));
		}

		private double sampleBilinear(double localX, double localY) {
			int x0 = Mth.clamp(Mth.floor(localX), 0, this.width - 1);
			int y0 = Mth.clamp(Mth.floor(localY), 0, this.height - 1);
			int x1 = Math.min(x0 + 1, this.width - 1);
			int y1 = Math.min(y0 + 1, this.height - 1);
			float v00 = sampleValue(x0, y0);
			float v10 = sampleValue(x1, y0);
			float v01 = sampleValue(x0, y1);
			float v11 = sampleValue(x1, y1);
			double dx = localX - x0;
			double dy = localY - y0;
			return blendFiniteSamples(v00, v10, v01, v11, dx, dy);
		}

		private boolean isWithinImage(double localX, double localY) {
			return localX >= 0.0 && localY >= 0.0 && localX < this.width && localY < this.height;
		}

		private static double blendFiniteSamples(
				float v00,
				float v10,
				float v01,
				float v11,
				double dx,
				double dy
		) {
			double w00 = (1.0 - dx) * (1.0 - dy);
			double w10 = dx * (1.0 - dy);
			double w01 = (1.0 - dx) * dy;
			double w11 = dx * dy;
			double sum = 0.0;
			double weight = 0.0;
			if (Float.isFinite(v00)) {
				sum += v00 * w00;
				weight += w00;
			}
			if (Float.isFinite(v10)) {
				sum += v10 * w10;
				weight += w10;
			}
			if (Float.isFinite(v01)) {
				sum += v01 * w01;
				weight += w01;
			}
			if (Float.isFinite(v11)) {
				sum += v11 * w11;
				weight += w11;
			}
			return weight <= 0.0 ? Double.NaN : sum / weight;
		}

		private float sampleValue(int pixelX, int pixelY) {
			if (this.failed) {
				return Float.NaN;
			}
			int tileX = pixelX / this.tileWidth;
			int tileY = pixelY / this.tileHeight;
			int tileIndex = tileY * this.tilesPerRow + tileX;
			float[] tile;
			try {
				tile = getTile(tileIndex);
			} catch (IOException e) {
				this.failed = true;
				if (Thread.currentThread().isInterrupted()) {
					Thread.currentThread().interrupt();
				}
				Tellus.LOGGER.debug("Failed to read ArcticDEM tile {} from {}", tileIndex, this.url, e);
				return Float.NaN;
			}
			int localX = pixelX - tileX * this.tileWidth;
			int localY = pixelY - tileY * this.tileHeight;
			float value = tile[localX + localY * this.tileWidth];
			if (!Float.isFinite(value) || value <= (float) (NO_DATA + 1.0)) {
				return Float.NaN;
			}
			return value;
		}

		private boolean hasFailure() {
			return this.failed;
		}

		private float[] getTile(int tileIndex) throws IOException {
			synchronized (this.tileCache) {
				float[] cached = this.tileCache.get(tileIndex);
				if (cached != null) {
					return cached;
				}
			}
			float[] tile = readTile(tileIndex);
			synchronized (this.tileCache) {
				this.tileCache.put(tileIndex, tile);
			}
			return tile;
		}

		private float[] readTile(int tileIndex) throws IOException {
			long offset = this.tileOffsets[tileIndex];
			int length = this.tileByteCounts[tileIndex];
			byte[] compressed = this.reader.read(offset, length);
			int expectedSize = this.tileWidth * this.tileHeight * this.bytesPerSample * this.samplesPerPixel;
			if (this.samplesPerPixel != 1) {
				throw new IOException("Unsupported TIFF samples per pixel " + this.samplesPerPixel);
			}
			byte[] raw = switch (this.compression) {
				case COMPRESSION_DEFLATE -> inflate(compressed, expectedSize);
				case COMPRESSION_LZW -> decompressLzw(compressed, expectedSize);
				default -> throw new IOException("Unsupported TIFF compression " + this.compression);
			};
			if (this.predictor == 3) {
				applyFloatingPointPredictor(raw, this.tileWidth, this.tileHeight, this.bytesPerSample,
						this.samplesPerPixel, this.order);
			} else if (this.predictor != 1) {
				throw new IOException("Unsupported TIFF predictor " + this.predictor);
			}
			float[] values = new float[this.tileWidth * this.tileHeight];
			ByteBuffer buffer = ByteBuffer.wrap(raw).order(this.order);
			for (int i = 0; i < values.length; i++) {
				values[i] = buffer.getFloat();
			}
			return values;
		}

		private static TileFile open(String url) throws IOException {
			RangeReader reader = new RangeReader(url);
			byte[] header = reader.read(0, 32);
			ByteOrder order = switch (header[0]) {
				case 0x49 -> ByteOrder.LITTLE_ENDIAN;
				case 0x4D -> ByteOrder.BIG_ENDIAN;
				default -> throw new IOException("Invalid TIFF byte order");
			};
			ByteBuffer headerBuf = ByteBuffer.wrap(header).order(order);
			headerBuf.getShort();
			short magic = headerBuf.getShort();
			if (magic != 43) {
				throw new IOException("Expected BigTIFF magic");
			}
			headerBuf.getShort();
			headerBuf.getShort();
			long ifdOffset = headerBuf.getLong();

			byte[] countBytes = reader.read(ifdOffset, 8);
			long entryCount = ByteBuffer.wrap(countBytes).order(order).getLong();
			if (entryCount <= 0 || entryCount > 1_000_000) {
				throw new IOException("Invalid BigTIFF entry count " + entryCount);
			}

			byte[] entryBytes = reader.read(ifdOffset + 8, (int) entryCount * 20);
			ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);

			int width = -1;
			int height = -1;
			int tileWidth = -1;
			int tileHeight = -1;
			int compression = -1;
			int predictor = 1;
			int bitsPerSample = -1;
			int sampleFormat = -1;
			int samplesPerPixel = 1;
			long[] tileOffsets = null;
			int[] tileByteCounts = null;

			for (int i = 0; i < entryCount; i++) {
				int tag = Short.toUnsignedInt(entries.getShort());
				int type = Short.toUnsignedInt(entries.getShort());
				long count = entries.getLong();
				long value = entries.getLong();
				switch (tag) {
					case TAG_IMAGE_WIDTH -> width = readIntValue(type, count, value);
					case TAG_IMAGE_HEIGHT -> height = readIntValue(type, count, value);
					case TAG_TILE_WIDTH -> tileWidth = readIntValue(type, count, value);
					case TAG_TILE_HEIGHT -> tileHeight = readIntValue(type, count, value);
					case TAG_COMPRESSION -> compression = readIntValue(type, count, value);
					case TAG_PREDICTOR -> predictor = readIntValue(type, count, value);
					case TAG_BITS_PER_SAMPLE -> bitsPerSample = readIntValue(type, count, value);
					case TAG_SAMPLE_FORMAT -> sampleFormat = readIntValue(type, count, value);
					case TAG_SAMPLES_PER_PIXEL -> samplesPerPixel = readIntValue(type, count, value);
					case TAG_TILE_OFFSETS -> tileOffsets = readLongArray(reader, value, count, type, order);
					case TAG_TILE_BYTE_COUNTS -> tileByteCounts = readIntArray(reader, value, count, type, order);
					default -> {
					}
				}
			}

			if (width <= 0 || height <= 0 || tileWidth <= 0 || tileHeight <= 0) {
				throw new IOException("Missing TIFF size tags");
			}
			if (tileOffsets == null || tileByteCounts == null) {
				throw new IOException("Missing TIFF tile offsets");
			}
			if (bitsPerSample != 32 || sampleFormat != 3) {
				throw new IOException("Unsupported TIFF sample format " + sampleFormat + " bits " + bitsPerSample);
			}

			return new TileFile(url, reader, order, width, height, tileWidth, tileHeight, compression, predictor, 4,
					samplesPerPixel, tileOffsets, tileByteCounts);
		}

		private static int readIntValue(int type, long count, long value) throws IOException {
			if (count != 1) {
				throw new IOException("Expected single TIFF value");
			}
			if (type == TYPE_SHORT) {
				return (int) (value & 0xffff);
			}
			if (type == TYPE_LONG) {
				return (int) (value & 0xffffffffL);
			}
			if (type == TYPE_LONG8) {
				if (value > Integer.MAX_VALUE) {
					throw new IOException("TIFF value out of range " + value);
				}
				return (int) value;
			}
			throw new IOException("Unsupported TIFF value type " + type);
		}

		private static long[] readLongArray(RangeReader reader, long offset, long count, int type, ByteOrder order)
				throws IOException {
			if (count <= 0) {
				return new long[0];
			}
			int size = typeSize(type);
			if (size <= 0) {
				throw new IOException("Unsupported TIFF array type " + type);
			}
			long byteSize = count * size;
			if (byteSize > Integer.MAX_VALUE) {
				throw new IOException("TIFF array too large");
			}
			byte[] data = reader.read(offset, (int) byteSize);
			ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
			long[] values = new long[(int) count];
			for (int i = 0; i < count; i++) {
				values[i] = switch (type) {
					case TYPE_SHORT -> Short.toUnsignedInt(buffer.getShort());
					case TYPE_LONG -> Integer.toUnsignedLong(buffer.getInt());
					case TYPE_LONG8 -> buffer.getLong();
					default -> throw new IOException("Unsupported TIFF array type " + type);
				};
			}
			return values;
		}

		private static int[] readIntArray(RangeReader reader, long offset, long count, int type, ByteOrder order)
				throws IOException {
			long[] values = readLongArray(reader, offset, count, type, order);
			int[] output = new int[values.length];
			for (int i = 0; i < values.length; i++) {
				if (values[i] > Integer.MAX_VALUE) {
					throw new IOException("TIFF byte count too large " + values[i]);
				}
				output[i] = (int) values[i];
			}
			return output;
		}

		private static int typeSize(int type) {
			return switch (type) {
				case TYPE_SHORT -> 2;
				case TYPE_LONG -> 4;
				case TYPE_DOUBLE, TYPE_LONG8 -> 8;
				default -> 0;
			};
		}

		private static byte[] inflate(byte[] compressed, int expectedSize) throws IOException {
			byte[] output = new byte[expectedSize];
			try (java.util.zip.InflaterInputStream inflater =
					new java.util.zip.InflaterInputStream(new ByteArrayInputStream(compressed))) {
				int offset = 0;
				while (offset < expectedSize) {
					int read = inflater.read(output, offset, expectedSize - offset);
					if (read < 0) {
						break;
					}
					offset += read;
				}
				if (offset != expectedSize) {
					throw new IOException("Unexpected inflated data length");
				}
				return output;
			}
		}

		private static byte[] decompressLzw(byte[] compressed, int expectedSize) throws IOException {
			byte[][] table = new byte[4096][];
			for (int i = 0; i < 256; i++) {
				table[i] = new byte[]{(byte) i};
			}

			int clearCode = 256;
			int endCode = 257;
			int codeSize = 9;
			int nextCode = 258;
			byte[] output = new byte[expectedSize];
			int outPos = 0;

			LzwBitReader reader = new LzwBitReader(compressed);
			byte[] previous = null;

			while (true) {
				int code = reader.read(codeSize);
				if (code < 0) {
					break;
				}
				if (code == clearCode) {
					for (int i = 0; i < 256; i++) {
						table[i] = new byte[]{(byte) i};
					}
					for (int i = 256; i < table.length; i++) {
						table[i] = null;
					}
					codeSize = 9;
					nextCode = 258;
					previous = null;
					continue;
				}
				if (code == endCode) {
					break;
				}

				byte[] entry;
				if (code < nextCode && table[code] != null) {
					entry = table[code];
				} else if (code == nextCode && previous != null) {
					entry = concat(previous, previous[0]);
				} else {
					throw new IOException("Invalid LZW code " + code);
				}

				if (outPos + entry.length > output.length) {
					throw new IOException("Unexpected LZW output size");
				}
				System.arraycopy(entry, 0, output, outPos, entry.length);
				outPos += entry.length;

				if (previous != null && nextCode < table.length) {
					table[nextCode++] = concat(previous, entry[0]);
					int threshold = (1 << codeSize) - 1;
					if (nextCode == threshold && codeSize < 12) {
						codeSize++;
					}
				}

				previous = entry;
				if (outPos == output.length) {
					break;
				}
			}

			if (outPos != output.length) {
				throw new IOException("Unexpected LZW output length " + outPos);
			}
			return output;
		}

		private static void applyFloatingPointPredictor(byte[] data,
				int width,
				int height,
				int bytesPerSample,
				int samplesPerPixel,
				ByteOrder order
		) throws IOException {
			int rowBytes = width * bytesPerSample * samplesPerPixel;
			if (rowBytes <= 0) {
				return;
			}
			if (samplesPerPixel <= 0 || rowBytes % (bytesPerSample * samplesPerPixel) != 0) {
				throw new IOException("Invalid floating point predictor stride");
			}
			int wordCount = rowBytes / bytesPerSample;
			byte[] tmp = new byte[rowBytes];

			for (int row = 0; row < height; row++) {
				int rowStart = row * rowBytes;
				int count = rowBytes;
				int offset = rowStart;
				while (count > samplesPerPixel) {
					for (int i = 0; i < samplesPerPixel; i++) {
						int target = offset + samplesPerPixel;
						int value = (data[target] & 0xff) + (data[offset] & 0xff);
						data[target] = (byte) value;
						offset++;
					}
					count -= samplesPerPixel;
				}

				System.arraycopy(data, rowStart, tmp, 0, rowBytes);
				for (int word = 0; word < wordCount; word++) {
					int base = rowStart + word * bytesPerSample;
					for (int byteIndex = 0; byteIndex < bytesPerSample; byteIndex++) {
						int sourceIndex = (order == ByteOrder.BIG_ENDIAN)
								? byteIndex * wordCount + word
								: (bytesPerSample - byteIndex - 1) * wordCount + word;
						data[base + byteIndex] = tmp[sourceIndex];
					}
				}
			}
		}

		private static byte[] concat(byte[] prefix, byte suffix) {
			byte[] combined = new byte[prefix.length + 1];
			System.arraycopy(prefix, 0, combined, 0, prefix.length);
			combined[prefix.length] = suffix;
			return combined;
		}

		private static final class LzwBitReader {
			private final byte[] data;
			private int bitPos;

			private LzwBitReader(byte[] data) {
				this.data = data;
			}

			private int read(int bits) {
				int totalBits = this.data.length * 8;
				if (this.bitPos + bits > totalBits) {
					return -1;
				}
				int result = 0;
				for (int i = 0; i < bits; i++) {
					int byteIndex = (this.bitPos + i) / 8;
					int bitIndex = 7 - ((this.bitPos + i) % 8);
					int bit = (this.data[byteIndex] >> bitIndex) & 1;
					result = (result << 1) | bit;
				}
				this.bitPos += bits;
				return result;
			}
		}
	}

	private static final class RangeReader {
		private final String url;

		private RangeReader(String url) {
			this.url = url;
		}

		private byte[] read(long offset, int length) throws IOException {
			if (length <= 0) {
				return new byte[0];
			}
			String rangeHeader = "bytes=" + offset + "-" + (offset + length - 1);
			HttpURLConnection connection = openConnection(URI.create(this.url), rangeHeader);
			int status = connection.getResponseCode();
			if (status == 416) {
				throw new EOFException("Range not satisfiable for " + this.url);
			}
			if (status != 206 && status != 200) {
				throw new IOException("Unexpected HTTP status " + status + " for " + this.url);
			}
			try (InputStream input = connection.getInputStream()) {
				byte[] data = input.readAllBytes();
				if (data.length != length) {
					throw new EOFException("Unexpected range length " + data.length + " for " + this.url);
				}
				return data;
			}
		}
	}
}
