package dev.nuclr.plugin.core.quick.viewer.archive;

import java.time.Instant;

public record ArchiveRootInfo(
		String name,
		boolean directory,
		int descendantCount,
		long totalSize,
		Instant lastModified) {
}
