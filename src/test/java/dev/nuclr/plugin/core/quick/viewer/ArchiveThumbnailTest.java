package dev.nuclr.plugin.core.quick.viewer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrResource;

class ArchiveThumbnailTest {

	private final ArchiveQuickViewProvider provider = new ArchiveQuickViewProvider();

	@Test
	void drawsAListingPageWithinTheBoxBeforeInit() throws Exception {
		assertTrue(provider.supportsThumbnails());

		BufferedImage image = provider.thumbnail(resource("bundle.zip", zip()), 120, 120, new AtomicBoolean());

		assertNotNull(image);
		assertTrue(image.getWidth() <= 120 && image.getHeight() <= 120);
	}

	@Test
	void returnsNullForDamagedForeignOrCancelled() throws Exception {
		assertNull(provider.thumbnail(resource("broken.zip", "not a zip".getBytes(StandardCharsets.US_ASCII)), 120,
				120, new AtomicBoolean()));
		assertNull(provider.thumbnail(resource("bundle.txt", zip()), 120, 120, new AtomicBoolean()));
		assertNull(provider.thumbnail(resource("bundle.zip", zip()), 120, 120, new AtomicBoolean(true)));
	}

	private static byte[] zip() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("docs/"));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("docs/readme.txt"));
			zip.write("hello".getBytes(StandardCharsets.US_ASCII));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("app.jar"));
			zip.write(new byte[64]);
			zip.closeEntry();
		}
		return out.toByteArray();
	}

	private static NuclrResource resource(String name, byte[] content) {
		NuclrResource resource = new NuclrResource(null) {
			private static final long serialVersionUID = 1L;

			@Override
			public InputStream openInputStream(OpenOption... options) {
				return new ByteArrayInputStream(content);
			}
		};
		resource.setUuid(name);
		resource.setName(name);
		resource.setLength(content.length);
		return resource;
	}
}
