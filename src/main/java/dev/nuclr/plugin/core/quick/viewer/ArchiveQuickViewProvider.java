package dev.nuclr.plugin.core.quick.viewer;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResourcePath;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArchiveQuickViewProvider implements NuclrPlugin {

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

	private NuclrPluginContext context;
	private ArchiveViewPanel panel;
	private volatile AtomicBoolean currentCancelled;

	@Override
	public JComponent panel() {
		if (panel == null) {
			panel = new ArchiveViewPanel();
		}
		return panel;
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResourcePath source) {
		return List.of();
	}

	@Override
	public void load(NuclrPluginContext context) {
		this.context = context;
	}

	@Override
	public boolean supports(NuclrResourcePath resource) {
		if (resource == null || resource.getExtension() == null) {
			return false;
		}
		return EXTENSIONS.contains(resource.getExtension().toLowerCase(Locale.ROOT));
	}

	@Override
	public boolean openResource(NuclrResourcePath resource, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentCancelled = cancelled;
		panel();
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
	public int priority() {
		return 1;
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
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public String id() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String name() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String version() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String description() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String author() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String license() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String website() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String pageUrl() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String docUrl() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Developer type() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
		
	}
}
