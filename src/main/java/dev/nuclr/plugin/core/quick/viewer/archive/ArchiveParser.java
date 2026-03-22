package dev.nuclr.plugin.core.quick.viewer.archive;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;

import dev.nuclr.plugin.PluginPathResource;

public final class ArchiveParser {

	private static final int SIGNATURE_SIZE = 12;
	private static final int MARK_LIMIT = 64 * 1024;
	private static final int MAX_DISPLAY_ENTRIES = 1_000;
	private static final int MAX_ROOT_ENTRIES = 64;

	private ArchiveParser() {
	}

	public static ArchiveMetadata parse(PluginPathResource item, AtomicBoolean cancelled) throws Exception {
		Objects.requireNonNull(item, "item");
		Objects.requireNonNull(cancelled, "cancelled");

		String containerName = resolveContainerName(item);
		SummaryBuilder builder = new SummaryBuilder(containerName, item.getSizeBytes(), resolveContainerModified(item));
		String lowerName = containerName.toLowerCase(Locale.ROOT);
		byte[] signature = readSignature(item);

		if (isRar(lowerName, signature)) {
			parseRar(item, builder, cancelled);
		} else if (isSevenZip(lowerName, signature)) {
			parseSevenZip(item, builder, cancelled);
		} else if (looksLikeCompressedArchive(lowerName)) {
			parseCompressed(item, builder, lowerName, cancelled);
		} else if (looksLikeZipFamily(lowerName) && item.getPath() != null) {
			parseZip(item.getPath(), builder, cancelled, extensionLabel(lowerName, "ZIP"));
		} else {
			parseByDetection(item, builder, lowerName, cancelled);
		}

		if (builder.entryCount == 0) {
			throw new IOException("No archive entries were found.");
		}
		return builder.build();
	}

	private static void parseByDetection(PluginPathResource item, SummaryBuilder builder, String lowerName, AtomicBoolean cancelled)
			throws Exception {
		try (BufferedInputStream raw = openBuffered(item)) {
			raw.mark(MARK_LIMIT);
			try {
				String archiveType = ArchiveStreamFactory.detect(raw);
				raw.reset();
				parseArchiveStream(raw, archiveType, builder, cancelled, extensionLabel(lowerName, prettyArchiveName(archiveType)));
				return;
			} catch (ArchiveException ignored) {
				raw.reset();
			}

			raw.mark(MARK_LIMIT);
			try {
				String compressor = CompressorStreamFactory.detect(raw);
				raw.reset();
				parseCompressed(raw, compressor, builder, lowerName, cancelled);
				return;
			} catch (CompressorException ignored) {
				raw.reset();
			}
		}

		if (item.getPath() != null) {
			parseZip(item.getPath(), builder, cancelled, extensionLabel(lowerName, "ZIP"));
			return;
		}
		throw new IOException("Unsupported archive format.");
	}

	private static void parseCompressed(PluginPathResource item, SummaryBuilder builder, String lowerName, AtomicBoolean cancelled)
			throws Exception {
		try (BufferedInputStream raw = openBuffered(item)) {
			String compressor = compressorNameFromExtension(lowerName);
			if (compressor == null) {
				try {
					compressor = CompressorStreamFactory.detect(raw);
					raw.reset();
				} catch (CompressorException e) {
					throw new IOException("Unable to detect compressed archive format.", e);
				}
			}
			parseCompressed(raw, compressor, builder, lowerName, cancelled);
		}
	}

	private static void parseCompressed(InputStream raw, String compressor, SummaryBuilder builder, String lowerName,
			AtomicBoolean cancelled) throws Exception {
		if (CompressorStreamFactory.GZIP.equals(compressor)) {
			try (GzipCompressorInputStream gzip = new GzipCompressorInputStream(new BufferedInputStream(raw));
					BufferedInputStream decompressed = new BufferedInputStream(gzip)) {
				GzipParameters meta = gzip.getMetaData();
				String nestedLabel = compressedArchiveLabel(lowerName, compressor);
				if (tryNestedArchive(decompressed, nestedLabel, builder, cancelled)) {
					return;
				}
				builder.formatLabel = prettyCompressorName(compressor);
				builder.comment = normalize(meta.getComment());
				String entryName = normalize(meta.getFileName());
				if (entryName == null) {
					entryName = stripCompressedSuffix(builder.containerName, lowerName);
				}
				builder.addEntry(entryName, false, -1L, builder.containerSize, meta.getModificationInstant());
				builder.addWarning("Uncompressed size is not available from this single-file stream.");
			}
			return;
		}

		try (CompressorInputStream compressed = new CompressorStreamFactory().createCompressorInputStream(compressor, new BufferedInputStream(raw));
				BufferedInputStream decompressed = new BufferedInputStream(compressed)) {
			String nestedLabel = compressedArchiveLabel(lowerName, compressor);
			if (tryNestedArchive(decompressed, nestedLabel, builder, cancelled)) {
				return;
			}
			builder.formatLabel = prettyCompressorName(compressor);
			builder.addEntry(stripCompressedSuffix(builder.containerName, lowerName), false, -1L, builder.containerSize, null);
			builder.addWarning("Uncompressed size is not available from this single-file stream.");
		}
	}

	private static boolean tryNestedArchive(BufferedInputStream decompressed, String nestedLabel, SummaryBuilder builder,
			AtomicBoolean cancelled) throws Exception {
		decompressed.mark(MARK_LIMIT);
		try {
			String nestedType = ArchiveStreamFactory.detect(decompressed);
			decompressed.reset();
			parseArchiveStream(decompressed, nestedType, builder, cancelled, nestedLabel != null ? nestedLabel : prettyArchiveName(nestedType));
			return true;
		} catch (ArchiveException ignored) {
			decompressed.reset();
			return false;
		}
	}

	private static void parseZip(Path path, SummaryBuilder builder, AtomicBoolean cancelled, String label) throws Exception {
		builder.formatLabel = label;
		try (ZipFile zip = new ZipFile(path)) {
			Enumeration<ZipArchiveEntry> entries = zip.getEntries();
			while (entries.hasMoreElements()) {
				checkCancelled(cancelled);
				ZipArchiveEntry entry = entries.nextElement();
				builder.addEntry(
						entry.getName(),
						entry.isDirectory(),
						entry.getSize(),
						null,
						toInstant(entry.getLastModifiedTime()));
			}
		}
	}

	private static void parseArchiveStream(InputStream input, String archiveType, SummaryBuilder builder, AtomicBoolean cancelled,
			String label) throws Exception {
		builder.formatLabel = label != null ? label : prettyArchiveName(archiveType);
		try (ArchiveInputStream<? extends ArchiveEntry> archive = new ArchiveStreamFactory().createArchiveInputStream(archiveType, input)) {
			ArchiveEntry entry;
			while ((entry = archive.getNextEntry()) != null) {
				checkCancelled(cancelled);
				builder.addEntry(
						entry.getName(),
						entry.isDirectory(),
						entry.getSize(),
						extractCompressedSize(entry),
						toInstant(entry.getLastModifiedDate()));
			}
		}
	}

	private static void parseSevenZip(PluginPathResource item, SummaryBuilder builder, AtomicBoolean cancelled) throws Exception {
		builder.formatLabel = "7z";
		try (SeekableByteChannel channel = openSeekable(item); SevenZFile sevenZ = new SevenZFile(channel)) {
			for (SevenZArchiveEntry entry : sevenZ.getEntries()) {
				checkCancelled(cancelled);
				builder.addEntry(
						entry.getName(),
						entry.isDirectory(),
						entry.getSize(),
						null,
						toInstant(entry.getLastModifiedTime()));
			}
		}
	}

	private static void parseRar(PluginPathResource item, SummaryBuilder builder, AtomicBoolean cancelled) throws Exception {
		builder.formatLabel = "RAR";
		try (InputStream input = item.openStream(); Archive archive = new Archive(input)) {
			try {
				builder.encrypted = archive.isEncrypted() || archive.isPasswordProtected();
			} catch (RarException ignored) {
				// Best effort only.
			}
			for (FileHeader header : archive.getFileHeaders()) {
				checkCancelled(cancelled);
				builder.addEntry(
						header.getFileName(),
						header.isDirectory(),
						header.getFullUnpackSize(),
						header.getFullPackSize() >= 0 ? header.getFullPackSize() : null,
						toInstant(header.getLastModifiedTime()));
				if (header.isSplitAfter() || header.isSplitBefore()) {
					builder.addWarning("Multi-volume RAR member detected.");
				}
			}
		}
	}

	private static BufferedInputStream openBuffered(PluginPathResource item) throws Exception {
		BufferedInputStream buffered = new BufferedInputStream(item.openStream());
		buffered.mark(MARK_LIMIT);
		return buffered;
	}

	private static SeekableByteChannel openSeekable(PluginPathResource item) throws Exception {
		if (item.getPath() != null) {
			return Files.newByteChannel(item.getPath());
		}
		try (InputStream input = item.openStream()) {
			return new SeekableInMemoryByteChannel(input.readAllBytes());
		}
	}

	private static byte[] readSignature(PluginPathResource item) throws Exception {
		try (InputStream input = item.openStream()) {
			return input.readNBytes(SIGNATURE_SIZE);
		}
	}

	private static Instant resolveContainerModified(PluginPathResource item) {
		try {
			Path path = item.getPath();
			if (path != null) {
				return Files.getLastModifiedTime(path).toInstant();
			}
		} catch (Exception ignored) {
			// Best effort only.
		}
		return null;
	}

	private static boolean looksLikeZipFamily(String lowerName) {
		return lowerName.endsWith(".zip")
				|| lowerName.endsWith(".jar")
				|| lowerName.endsWith(".war")
				|| lowerName.endsWith(".ear")
				|| lowerName.endsWith(".apk")
				|| lowerName.endsWith(".xapk")
				|| lowerName.endsWith(".apks")
				|| lowerName.endsWith(".apkm");
	}

	private static boolean looksLikeCompressedArchive(String lowerName) {
		return lowerName.endsWith(".tar.gz")
				|| lowerName.endsWith(".tgz")
				|| lowerName.endsWith(".tar.bz2")
				|| lowerName.endsWith(".tbz2")
				|| lowerName.endsWith(".tbz")
				|| lowerName.endsWith(".tar.xz")
				|| lowerName.endsWith(".txz")
				|| lowerName.endsWith(".gz")
				|| lowerName.endsWith(".bz2")
				|| lowerName.endsWith(".xz");
	}

	private static boolean isSevenZip(String lowerName, byte[] signature) {
		return lowerName.endsWith(".7z") || SevenZFile.matches(signature, signature.length);
	}

	private static boolean isRar(String lowerName, byte[] signature) {
		return lowerName.endsWith(".rar")
				|| startsWith(signature, new byte[] { 0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00 })
				|| startsWith(signature, new byte[] { 0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00 });
	}

	private static boolean startsWith(byte[] actual, byte[] expected) {
		if (actual.length < expected.length) {
			return false;
		}
		for (int i = 0; i < expected.length; i++) {
			if (actual[i] != expected[i]) {
				return false;
			}
		}
		return true;
	}

	private static String compressorNameFromExtension(String lowerName) {
		if (lowerName.endsWith(".gz") || lowerName.endsWith(".tgz")) {
			return CompressorStreamFactory.GZIP;
		}
		if (lowerName.endsWith(".bz2") || lowerName.endsWith(".tbz2") || lowerName.endsWith(".tbz")) {
			return CompressorStreamFactory.BZIP2;
		}
		if (lowerName.endsWith(".xz") || lowerName.endsWith(".txz")) {
			return CompressorStreamFactory.XZ;
		}
		return null;
	}

	private static String compressedArchiveLabel(String lowerName, String compressor) {
		if (lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz")) {
			return "tar.gz";
		}
		if (lowerName.endsWith(".tar.bz2") || lowerName.endsWith(".tbz2") || lowerName.endsWith(".tbz")) {
			return "tar.bz2";
		}
		if (lowerName.endsWith(".tar.xz") || lowerName.endsWith(".txz")) {
			return "tar.xz";
		}
		return prettyCompressorName(compressor);
	}

	private static String stripCompressedSuffix(String name, String lowerName) {
		if (lowerName.endsWith(".tar.gz")) {
			return name.substring(0, name.length() - 3);
		}
		if (lowerName.endsWith(".tgz")) {
			return name.substring(0, name.length() - 4) + ".tar";
		}
		if (lowerName.endsWith(".tar.bz2")) {
			return name.substring(0, name.length() - 4);
		}
		if (lowerName.endsWith(".tbz2")) {
			return name.substring(0, name.length() - 5) + ".tar";
		}
		if (lowerName.endsWith(".tbz")) {
			return name.substring(0, name.length() - 4) + ".tar";
		}
		if (lowerName.endsWith(".tar.xz")) {
			return name.substring(0, name.length() - 3);
		}
		if (lowerName.endsWith(".txz")) {
			return name.substring(0, name.length() - 4) + ".tar";
		}
		if (lowerName.endsWith(".gz") || lowerName.endsWith(".xz")) {
			return name.substring(0, Math.max(0, name.length() - 3));
		}
		if (lowerName.endsWith(".bz2")) {
			return name.substring(0, Math.max(0, name.length() - 4));
		}
		return name;
	}

	private static String prettyArchiveName(String archiveType) {
		if (archiveType == null) {
			return "Archive";
		}
		return switch (archiveType) {
		case ArchiveStreamFactory.ZIP -> "ZIP";
		case ArchiveStreamFactory.JAR -> "JAR";
		case ArchiveStreamFactory.TAR -> "TAR";
		case ArchiveStreamFactory.CPIO -> "CPIO";
		case ArchiveStreamFactory.AR -> "AR";
		case ArchiveStreamFactory.APK -> "APK";
		case ArchiveStreamFactory.APKM -> "APKM";
		case ArchiveStreamFactory.APKS -> "APKS";
		case ArchiveStreamFactory.XAPK -> "XAPK";
		default -> archiveType.toUpperCase(Locale.ROOT);
		};
	}

	private static String prettyCompressorName(String compressor) {
		if (compressor == null) {
			return "Compressed";
		}
		return switch (compressor) {
		case CompressorStreamFactory.GZIP -> "GZIP";
		case CompressorStreamFactory.BZIP2 -> "BZIP2";
		case CompressorStreamFactory.XZ -> "XZ";
		default -> compressor.toUpperCase(Locale.ROOT);
		};
	}

	private static String extensionLabel(String lowerName, String detectedLabel) {
		if (lowerName.endsWith(".jar")) {
			return "JAR";
		}
		if (lowerName.endsWith(".war")) {
			return "WAR";
		}
		if (lowerName.endsWith(".ear")) {
			return "EAR";
		}
		if (lowerName.endsWith(".apk")) {
			return "APK";
		}
		if (lowerName.endsWith(".xapk")) {
			return "XAPK";
		}
		if (lowerName.endsWith(".apks")) {
			return "APKS";
		}
		if (lowerName.endsWith(".apkm")) {
			return "APKM";
		}
		return detectedLabel;
	}

	private static Long extractCompressedSize(ArchiveEntry entry) {
		if (entry instanceof ZipArchiveEntry zipEntry) {
			return zipEntry.getCompressedSize() >= 0 ? zipEntry.getCompressedSize() : null;
		}
		return null;
	}

	private static Instant toInstant(Date date) {
		return date != null ? date.toInstant() : null;
	}

	private static Instant toInstant(FileTime fileTime) {
		return fileTime != null ? fileTime.toInstant() : null;
	}

	private static String resolveContainerName(PluginPathResource item) {
		if (item.getName() != null && !item.getName().isBlank()) {
			return item.getName();
		}
		if (item.getPath() != null && item.getPath().getFileName() != null) {
			return item.getPath().getFileName().toString();
		}
		return "Archive";
	}

	private static void checkCancelled(AtomicBoolean cancelled) throws IOException {
		if (cancelled.get() || Thread.currentThread().isInterrupted()) {
			throw new IOException("Archive loading cancelled.");
		}
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value;
	}

	private static final class SummaryBuilder {

		private final String containerName;
		private final long containerSize;
		private final Instant containerModified;
		private final List<ArchiveEntryInfo> entries = new ArrayList<>();
		private final Map<String, RootAccumulator> roots = new LinkedHashMap<>();
		private final List<String> warnings = new ArrayList<>();

		private String formatLabel = "Archive";
		private String comment;
		private boolean encrypted;
		private boolean entriesTruncated;
		private int entryCount;
		private int fileCount;
		private int directoryCount;
		private long totalUncompressedSize;
		private boolean totalUncompressedKnown = true;
		private long totalCompressedSize;
		private boolean totalCompressedKnown = true;
		private Instant earliestEntryModified;
		private Instant latestEntryModified;

		SummaryBuilder(String containerName, long containerSize, Instant containerModified) {
			this.containerName = containerName;
			this.containerSize = containerSize;
			this.containerModified = containerModified;
		}

		void addEntry(String rawName, boolean directory, long size, Long compressedSize, Instant modified) {
			String path = normalizePath(rawName);
			if (path == null) {
				return;
			}

			entryCount++;
			if (directory) {
				directoryCount++;
			} else {
				fileCount++;
				if (size >= 0) {
					totalUncompressedSize += size;
				} else {
					totalUncompressedKnown = false;
				}
				if (compressedSize != null && compressedSize >= 0) {
					totalCompressedSize += compressedSize;
				} else {
					totalCompressedKnown = false;
				}
			}

			if (modified != null) {
				if (earliestEntryModified == null || modified.isBefore(earliestEntryModified)) {
					earliestEntryModified = modified;
				}
				if (latestEntryModified == null || modified.isAfter(latestEntryModified)) {
					latestEntryModified = modified;
				}
			}

			if (entries.size() < MAX_DISPLAY_ENTRIES) {
				entries.add(new ArchiveEntryInfo(path, directory, size, compressedSize, modified));
			} else {
				entriesTruncated = true;
			}

			addRoot(path, directory, size, modified);
		}

		void addWarning(String warning) {
			if (warning == null || warning.isBlank() || warnings.contains(warning)) {
				return;
			}
			warnings.add(warning);
		}

		ArchiveMetadata build() {
			List<ArchiveRootInfo> rootEntries = new ArrayList<>();
			for (RootAccumulator root : roots.values()) {
				if (rootEntries.size() >= MAX_ROOT_ENTRIES) {
					break;
				}
				rootEntries.add(root.toInfo());
			}
			if (roots.size() > MAX_ROOT_ENTRIES) {
				addWarning("Root entry list truncated to " + MAX_ROOT_ENTRIES + " items.");
			}
			if (fileCount == 0) {
				totalCompressedKnown = false;
			}
			return new ArchiveMetadata(
					containerName,
					formatLabel,
					containerSize,
					containerModified,
					entryCount,
					fileCount,
					directoryCount,
					totalUncompressedSize,
					totalUncompressedKnown,
					totalCompressedSize,
					totalCompressedKnown,
					roots.size(),
					earliestEntryModified,
					latestEntryModified,
					Collections.unmodifiableList(rootEntries),
					Collections.unmodifiableList(new ArrayList<>(entries)),
					entriesTruncated,
					comment,
					encrypted,
					Collections.unmodifiableList(new ArrayList<>(warnings)));
		}

		private void addRoot(String path, boolean directory, long size, Instant modified) {
			String rootName = firstSegment(path);
			if (rootName == null) {
				return;
			}
			RootAccumulator root = roots.computeIfAbsent(rootName, RootAccumulator::new);
			root.directory = root.directory || directory || path.contains("/");
			root.descendantCount++;
			if (!directory && size > 0) {
				root.totalSize += size;
			}
			if (modified != null && (root.lastModified == null || modified.isAfter(root.lastModified))) {
				root.lastModified = modified;
			}
		}

		private static String normalizePath(String rawName) {
			if (rawName == null || rawName.isBlank()) {
				return null;
			}
			String value = rawName.replace('\\', '/');
			while (value.startsWith("./")) {
				value = value.substring(2);
			}
			while (value.startsWith("/")) {
				value = value.substring(1);
			}
			if (value.endsWith("/")) {
				value = value.substring(0, value.length() - 1);
			}
			return value.isBlank() ? null : value;
		}

		private static String firstSegment(String path) {
			int slash = path.indexOf('/');
			return slash >= 0 ? path.substring(0, slash) : path;
		}
	}

	private static final class RootAccumulator {

		private final String name;
		private boolean directory;
		private int descendantCount;
		private long totalSize;
		private Instant lastModified;

		RootAccumulator(String name) {
			this.name = name;
		}

		ArchiveRootInfo toInfo() {
			return new ArchiveRootInfo(name, directory, descendantCount, totalSize, lastModified);
		}
	}
}
