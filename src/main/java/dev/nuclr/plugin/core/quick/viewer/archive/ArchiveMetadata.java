package dev.nuclr.plugin.core.quick.viewer.archive;

import java.time.Instant;
import java.util.List;

public record ArchiveMetadata(
		String containerName,
		String formatLabel,
		long containerSize,
		Instant containerModified,
		int entryCount,
		int fileCount,
		int directoryCount,
		long totalUncompressedSize,
		boolean totalUncompressedKnown,
		long totalCompressedSize,
		boolean totalCompressedKnown,
		int rootEntryCount,
		Instant earliestEntryModified,
		Instant latestEntryModified,
		List<ArchiveRootInfo> rootEntries,
		List<ArchiveEntryInfo> entries,
		boolean entriesTruncated,
		String comment,
		boolean encrypted,
		List<String> warnings) {
}
