package dev.nuclr.plugin.core.quick.viewer.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrResourcePath;

class ArchiveParserTest {

	@Test
	void parsesZipSummary() throws Exception {
		Path archive = Files.createTempFile("archive-parser-", ".zip");
		try {
			try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive)) {
				addZipEntry(output, "README.txt", "hello zip");
				output.putArchiveEntry(new ZipArchiveEntry("docs/"));
				output.closeArchiveEntry();
				addZipEntry(output, "docs/manual.txt", "manual");
			}

			ArchiveMetadata metadata = ArchiveParser.parse(resourceFor(archive), new AtomicBoolean(false));
			assertEquals("ZIP", metadata.formatLabel());
			assertEquals(3, metadata.entryCount());
			assertEquals(2, metadata.fileCount());
			assertEquals(1, metadata.directoryCount());
			assertEquals(2, metadata.rootEntryCount());
			assertEquals(15, metadata.totalUncompressedSize());
			assertFalse(metadata.entries().isEmpty());
			assertTrue(metadata.rootEntries().stream().anyMatch(root -> root.name().equals("docs") && root.directory()));
		} finally {
			Files.deleteIfExists(archive);
		}
	}

	@Test
	void parsesTarGzSummary() throws Exception {
		Path archive = Files.createTempFile("archive-parser-", ".tar.gz");
		try {
			try (OutputStream file = Files.newOutputStream(archive);
					GZIPOutputStream gzip = new GZIPOutputStream(file);
					TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
				addTarEntry(tar, "bin/run.sh", "#!/bin/sh\n");
				addTarEntry(tar, "notes.txt", "archive notes");
			}

			ArchiveMetadata metadata = ArchiveParser.parse(resourceFor(archive), new AtomicBoolean(false));
			assertEquals("tar.gz", metadata.formatLabel());
			assertEquals(2, metadata.entryCount());
			assertEquals(2, metadata.fileCount());
			assertEquals(0, metadata.directoryCount());
			assertEquals(2, metadata.rootEntryCount());
			assertEquals(23, metadata.totalUncompressedSize());
		} finally {
			Files.deleteIfExists(archive);
		}
	}

	@Test
	void parsesSevenZipSummary() throws Exception {
		Path archive = Files.createTempFile("archive-parser-", ".7z");
		try {
			try (SevenZOutputFile output = new SevenZOutputFile(archive.toFile())) {
				byte[] content = "seven zip".getBytes();
				SevenZArchiveEntry entry = new SevenZArchiveEntry();
				entry.setName("folder/data.txt");
				entry.setSize(content.length);
				entry.setLastModifiedDate(Date.from(Instant.parse("2024-01-02T03:04:05Z")));
				output.putArchiveEntry(entry);
				output.write(content);
				output.closeArchiveEntry();
			}

			ArchiveMetadata metadata = ArchiveParser.parse(resourceFor(archive), new AtomicBoolean(false));
			assertEquals("7z", metadata.formatLabel());
			assertEquals(1, metadata.entryCount());
			assertEquals(1, metadata.fileCount());
			assertEquals(0, metadata.directoryCount());
			assertEquals(1, metadata.rootEntryCount());
			assertEquals(9, metadata.totalUncompressedSize());
			assertNotNull(metadata.latestEntryModified());
			assertTrue(metadata.rootEntries().get(0).directory());
		} finally {
			Files.deleteIfExists(archive);
		}
	}

	private static NuclrResourcePath resourceFor(Path path) throws IOException {
		NuclrResourcePath resource = new NuclrResourcePath();
		resource.setPath(path);
		resource.setName(path.getFileName().toString());
		resource.setSizeBytes(Files.size(path));
		return resource;
	}

	private static void addZipEntry(ZipArchiveOutputStream output, String name, String text) throws IOException {
		byte[] content = text.getBytes();
		ZipArchiveEntry entry = new ZipArchiveEntry(name);
		entry.setSize(content.length);
		output.putArchiveEntry(entry);
		output.write(content);
		output.closeArchiveEntry();
	}

	private static void addTarEntry(TarArchiveOutputStream output, String name, String text) throws IOException {
		byte[] content = text.getBytes();
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(content.length);
		output.putArchiveEntry(entry);
		output.write(content);
		output.closeArchiveEntry();
	}
}
