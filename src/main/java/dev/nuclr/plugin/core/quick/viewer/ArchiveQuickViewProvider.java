package dev.nuclr.plugin.core.quick.viewer;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import org.apache.commons.io.FilenameUtils;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.quick.viewer.archive.ArchiveMetadata;
import dev.nuclr.plugin.core.quick.viewer.archive.ArchiveParser;
import dev.nuclr.plugin.core.quick.viewer.archive.ArchiveRootInfo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArchiveQuickViewProvider implements QuickViewNuclrPlugin {

	private static final Set<String> EXTENSIONS = Set.of("zip", "jar", "war", "ear", "apk", "xapk", "apks", "apkm",
			"tar", "gz", "tgz", "bz2", "tbz2", "tbz", "xz", "txz", "7z", "rar", "cpio", "ar");

	private NuclrPluginContext context;
	private ArchiveViewPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private NuclrResource currentResource;
	private String uuid = java.util.UUID.randomUUID().toString();

	@Override
	public JComponent panel() {
		if (panel == null) {
			panel = new ArchiveViewPanel();
		}
		return panel;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
	}

	@Override
	public void init() {
	}

	@Override
	public NuclrPluginContext getContext() {
		return this.context;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		String extension = extension(resource);
		if (extension == null) {
			extension = extension(resource.getPath());
		}
		if (extension == null || extension.isEmpty()) {
			return false;
		}
		return EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
	}

	private static String extension(Path path) {
		if (path == null) {
			// Path-less (virtual) resources are matched by name instead.
			return null;
		}
		var name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
		return FilenameUtils.getExtension(name);
	}
	
	private static String extension(NuclrResource resource) {
		if (resource == null || resource.getName() == null) {
			return null;
		}
		String name = resource.getName();
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return null;
		}
		return name.substring(dot + 1);
	}

	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentCancelled = cancelled;
		panel();
		this.currentResource = resource;
		return panel.load(resource, cancelled);
	}

	@Override
	public boolean supportsThumbnails() {
		return true;
	}

	/** A listing page: the archive's name and totals, then its top-level entries. */
	@Override
	public BufferedImage thumbnail(NuclrResource resource, int maxWidth, int maxHeight, AtomicBoolean cancelled) {
		if (maxWidth <= 0 || maxHeight <= 0 || !supports(resource)) {
			return null;
		}
		AtomicBoolean token = cancelled != null ? cancelled : new AtomicBoolean();
		try {
			ArchiveMetadata metadata = ArchiveParser.parse(resource, token);
			if (token.get()) {
				return null;
			}
			List<PageThumbnail.Line> lines = new ArrayList<>();
			lines.add(PageThumbnail.Line.title(metadata.containerName() != null ? metadata.containerName() : resource.getName()));
			lines.add(PageThumbnail.Line.muted(metadata.formatLabel() + " · " + metadata.fileCount() + " files · "
					+ ArchiveViewPanel.formatSize(metadata.totalUncompressedKnown()
							? metadata.totalUncompressedSize() : metadata.containerSize())));
			lines.add(PageThumbnail.Line.blank());
			for (ArchiveRootInfo root : metadata.rootEntries()) {
				lines.add(PageThumbnail.Line.mono(root.directory() ? root.name() + "/" : root.name()));
			}
			int hidden = metadata.rootEntryCount() - metadata.rootEntries().size();
			if (hidden > 0) {
				lines.add(PageThumbnail.Line.muted("… and " + hidden + " more"));
			}
			return PageThumbnail.render(lines, maxWidth, maxHeight, token);
		} catch (Exception e) {
			log.debug("No thumbnail for {}: {}", resource.getName(), e.toString());
			return null;
		}
	}

	@Override
	public void closeResource() {
		if (currentCancelled != null) {
			currentCancelled.set(true);
			currentCancelled = null;
		}
		if (panel != null) {
			panel.clear();
		}
	}

	@Override
	public void unload() {
		closeResource();
		panel = null;
		context = null;
	}


	@Override
	public boolean onFocusGained() {
		return false;
	}

	@Override
	public void onFocusLost() {
	}

	@Override
	public boolean isFocused() {
		return false;
	}



	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {

	}

	@Override
	public NuclrResource getCurrentResource() {
		return this.currentResource;
	}

	@Override
	public String uuid() {
		return uuid;
	}

}
