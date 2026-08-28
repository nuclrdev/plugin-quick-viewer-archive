package dev.nuclr.plugin.core.quick.viewer;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import org.apache.commons.io.FilenameUtils;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
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
