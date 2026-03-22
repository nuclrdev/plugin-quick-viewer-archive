package dev.nuclr.plugin.core.quick.viewer;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.ApplicationPluginContext;
import dev.nuclr.plugin.MenuResource;
import dev.nuclr.plugin.PluginManifest;
import dev.nuclr.plugin.PluginPathResource;
import dev.nuclr.plugin.QuickViewProviderPlugin;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArchiveQuickViewProvider implements QuickViewProviderPlugin {

	private static final Set<String> EXTENSIONS = Set.of(
			"zip",
			"jar",
			"war",
			"ear",
			"apk",
			"xapk",
			"apks",
			"apkm",
			"tar",
			"gz",
			"tgz",
			"bz2",
			"tbz2",
			"tbz",
			"xz",
			"txz",
			"7z",
			"rar",
			"cpio",
			"ar");

	private ApplicationPluginContext context;
	private ArchiveViewPanel panel;
	private volatile AtomicBoolean currentCancelled;

	@Override
	public PluginManifest getPluginInfo() {
		ObjectMapper objectMapper = context != null ? context.getObjectMapper() : new ObjectMapper();
		try (InputStream is = getClass().getResourceAsStream("/plugin.json")) {
			if (is != null) {
				return objectMapper.readValue(is, PluginManifest.class);
			}
		} catch (Exception e) {
			log.error("Error reading /plugin.json for ArchiveQuickViewProvider", e);
		}
		return null;
	}

	@Override
	public JComponent getPanel() {
		if (panel == null) {
			panel = new ArchiveViewPanel();
		}
		return panel;
	}

	@Override
	public List<MenuResource> getMenuItems(PluginPathResource source) {
		return List.of();
	}

	@Override
	public void load(ApplicationPluginContext context) {
		this.context = context;
	}

	@Override
	public boolean supports(PluginPathResource resource) {
		if (resource == null || resource.getExtension() == null) {
			return false;
		}
		return EXTENSIONS.contains(resource.getExtension().toLowerCase(Locale.ROOT));
	}

	@Override
	public boolean openItem(PluginPathResource resource, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentCancelled = cancelled;
		getPanel();
		return panel.load(resource, cancelled);
	}

	@Override
	public void closeItem() {
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
		closeItem();
		panel = null;
		context = null;
	}

	@Override
	public int getPriority() {
		return 1;
	}

	@Override
	public void onFocusGained() {
		// Quick view providers do not need focus-specific behavior.
	}

	@Override
	public void onFocusLost() {
		// Quick view providers do not need focus-specific behavior.
	}
}
