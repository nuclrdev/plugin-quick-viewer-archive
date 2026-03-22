package dev.nuclr.plugin.core.quick.viewer.archive;

import java.time.Instant;

public record ArchiveEntryInfo(
		String path,
		boolean directory,
		long size,
		Long compressedSize,
		Instant lastModified) {
}
