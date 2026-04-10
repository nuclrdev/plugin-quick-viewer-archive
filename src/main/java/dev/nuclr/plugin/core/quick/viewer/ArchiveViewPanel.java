package dev.nuclr.plugin.core.quick.viewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;

import dev.nuclr.platform.plugin.NuclrResourcePath;
import dev.nuclr.plugin.core.quick.viewer.archive.ArchiveEntryInfo;
import dev.nuclr.plugin.core.quick.viewer.archive.ArchiveMetadata;
import dev.nuclr.plugin.core.quick.viewer.archive.ArchiveParser;
import dev.nuclr.plugin.core.quick.viewer.archive.ArchiveRootInfo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArchiveViewPanel extends JPanel {

	private static final Font MONO_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);
	private static final Font MONO_SMALL = new Font(Font.MONOSPACED, Font.PLAIN, 11);
	private static final DateTimeFormatter DATE_FMT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	private volatile Thread loadThread;

	public ArchiveViewPanel() {
		setLayout(new BorderLayout());
		showMessage("No file selected.");
	}

	public boolean load(NuclrResourcePath item, AtomicBoolean cancelled) {
		Thread previous = loadThread;
		if (previous != null) {
			previous.interrupt();
		}
		showMessage("Loading...");
		loadThread = Thread.ofVirtual().name("archive-quick-view-" + item.getName()).start(() -> {
			try {
				ArchiveMetadata metadata = ArchiveParser.parse(item, cancelled);
				if (cancelled.get()) {
					return;
				}
				SwingUtilities.invokeLater(() -> showMetadata(metadata));
			} catch (Exception e) {
				if (cancelled.get()) {
					return;
				}
				log.error("Failed to inspect archive: {}", item.getName(), e);
				String message = e.getMessage();
				if (message == null || message.isBlank()) {
					message = e.getClass().getSimpleName();
				}
				if (message.length() > 300) {
					message = message.substring(0, 300) + "...";
				}
				String finalMessage = message;
				SwingUtilities.invokeLater(() -> showError(finalMessage));
			}
		});
		return true;
	}

	public void clear() {
		Thread previous = loadThread;
		if (previous != null) {
			previous.interrupt();
		}
		loadThread = null;
		SwingUtilities.invokeLater(() -> showMessage(""));
	}

	private void showMessage(String text) {
		removeAll();
		add(new JLabel(text, JLabel.CENTER), BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	private void showError(String message) {
		removeAll();
		JPanel errorPanel = new JPanel(new BorderLayout());
		errorPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

		JLabel title = new JLabel("Archive preview unavailable", JLabel.CENTER);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));

		JLabel detail = new JLabel("<html><center>" + escapeHtml(message) + "</center></html>", JLabel.CENTER);
		detail.setForeground(UIManager.getColor("Label.disabledForeground"));

		errorPanel.add(title, BorderLayout.NORTH);
		errorPanel.add(detail, BorderLayout.CENTER);
		add(errorPanel, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	private void showMetadata(ArchiveMetadata metadata) {
		removeAll();
		JPanel content = buildContent(metadata);
		JScrollPane scrollPane = new JScrollPane(
				content,
				ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		add(scrollPane, BorderLayout.CENTER);
		SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(0));
		revalidate();
		repaint();
	}

	private JPanel buildContent(ArchiveMetadata metadata) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

		FormSection summary = new FormSection("Summary");
		summary.addRow("Name", metadata.containerName());
		summary.addRow("Format", metadata.formatLabel());
		summary.addRow("Archive size", formatSize(metadata.containerSize()));
		if (metadata.containerModified() != null) {
			summary.addRow("Modified", formatInstant(metadata.containerModified()));
		}
		summary.addRow("Entries", Integer.toString(metadata.entryCount()));
		summary.addRow("Files", Integer.toString(metadata.fileCount()));
		summary.addRow("Directories", Integer.toString(metadata.directoryCount()));
		summary.addRow("Root entries", Integer.toString(metadata.rootEntryCount()));
		summary.addRow("Total unpacked", formatMaybeSize(metadata.totalUncompressedKnown(), metadata.totalUncompressedSize()));
		if (metadata.totalCompressedKnown()) {
			summary.addRow("Packed entries", formatSize(metadata.totalCompressedSize()));
			if (metadata.totalCompressedSize() > 0 && metadata.totalUncompressedKnown()) {
				summary.addRow("Ratio", formatRatio(metadata.totalCompressedSize(), metadata.totalUncompressedSize()));
			}
		}
		if (metadata.encrypted()) {
			summary.addRow("Encrypted", "Yes");
		}
		panel.add(summary);
		panel.add(vgap(6));

		boolean hasDetails = metadata.earliestEntryModified() != null
				|| metadata.latestEntryModified() != null
				|| metadata.comment() != null;
		if (hasDetails) {
			FormSection details = new FormSection("Details");
			if (metadata.earliestEntryModified() != null) {
				details.addRow("Earliest entry", formatInstant(metadata.earliestEntryModified()));
			}
			if (metadata.latestEntryModified() != null) {
				details.addRow("Latest entry", formatInstant(metadata.latestEntryModified()));
			}
			if (metadata.comment() != null) {
				details.addRow("Comment", metadata.comment());
			}
			panel.add(details);
			panel.add(vgap(6));
		}

		List<ArchiveRootInfo> roots = metadata.rootEntries();
		if (!roots.isEmpty()) {
			FormSection rootSection = new FormSection("Root Entries (" + roots.size() + ")");
			for (ArchiveRootInfo root : roots) {
				String descriptor = root.directory()
						? root.descendantCount() + " items, " + formatSize(root.totalSize())
						: formatSize(root.totalSize());
				rootSection.addFileRow(root.name() + (root.directory() ? "/" : ""), descriptor, root.lastModified());
			}
			panel.add(rootSection);
			panel.add(vgap(6));
		}

		if (!metadata.warnings().isEmpty()) {
			FormSection warnings = new FormSection("Warnings");
			for (String warning : metadata.warnings()) {
				warnings.addMonoText("! " + warning);
			}
			panel.add(warnings);
			panel.add(vgap(6));
		}

		String filesHeader = "Files (" + metadata.fileCount() + " files, "
				+ formatMaybeSize(metadata.totalUncompressedKnown(), metadata.totalUncompressedSize()) + ")";
		FormSection files = new FormSection(filesHeader);
		for (ArchiveEntryInfo entry : metadata.entries()) {
			String path = entry.path() + (entry.directory() ? "/" : "");
			String detail = entry.directory() ? "(dir)" : formatMaybeSize(entry.size() >= 0, Math.max(0L, entry.size()));
			files.addFileRow(path, detail, entry.lastModified());
		}
		if (metadata.entriesTruncated()) {
			files.addMonoText("... file list truncated");
		}
		panel.add(files);
		panel.add(Box.createVerticalGlue());

		return panel;
	}

	private static String formatInstant(Instant instant) {
		return DATE_FMT.format(instant);
	}

	private static String formatMaybeSize(boolean known, long bytes) {
		return known ? formatSize(bytes) : "Unknown";
	}

	private static String formatRatio(long compressed, long uncompressed) {
		if (compressed <= 0 || uncompressed < 0) {
			return "Unknown";
		}
		double ratio = (double) uncompressed / (double) compressed;
		return String.format("%.2fx", ratio);
	}

	static String formatSize(long bytes) {
		if (bytes < 0) {
			return "Unknown";
		}
		if (bytes < 1_024) {
			return bytes + " B";
		}
		double kb = bytes / 1_024.0;
		if (kb < 1_024) {
			return String.format("%.1f KB", kb);
		}
		double mb = kb / 1_024.0;
		if (mb < 1_024) {
			return String.format("%.2f MB", mb);
		}
		double gb = mb / 1_024.0;
		if (gb < 1_024) {
			return String.format("%.2f GB", gb);
		}
		return String.format("%.2f TB", gb / 1_024.0);
	}

	static String escapeHtml(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static Component vgap(int height) {
		return Box.createVerticalStrut(height);
	}

	private static class FormSection extends JPanel {

		private final JPanel grid;
		private int row;

		FormSection(String title) {
			setLayout(new BorderLayout(0, 2));
			setAlignmentX(LEFT_ALIGNMENT);
			setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
			setOpaque(false);

			JLabel header = new JLabel(title);
			header.setFont(header.getFont().deriveFont(Font.BOLD));
			header.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
			add(header, BorderLayout.NORTH);

			grid = new JPanel(new java.awt.GridBagLayout());
			grid.setOpaque(false);

			Color borderColor = UIManager.getColor("Separator.foreground");
			if (borderColor == null) {
				borderColor = Color.GRAY;
			}
			Border left = BorderFactory.createMatteBorder(0, 2, 0, 0, borderColor);
			Border padding = BorderFactory.createEmptyBorder(2, 8, 2, 2);
			grid.setBorder(BorderFactory.createCompoundBorder(left, padding));
			add(grid, BorderLayout.CENTER);
		}

		void addRow(String label, String value) {
			java.awt.GridBagConstraints key = new java.awt.GridBagConstraints();
			key.gridx = 0;
			key.gridy = row;
			key.anchor = java.awt.GridBagConstraints.NORTHWEST;
			key.insets = new java.awt.Insets(1, 0, 1, 10);

			java.awt.GridBagConstraints val = new java.awt.GridBagConstraints();
			val.gridx = 1;
			val.gridy = row;
			val.anchor = java.awt.GridBagConstraints.NORTHWEST;
			val.fill = java.awt.GridBagConstraints.HORIZONTAL;
			val.weightx = 1.0;
			val.insets = new java.awt.Insets(1, 0, 1, 0);
			row++;

			JLabel keyLabel = new JLabel(label + ":");
			keyLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

			JLabel valueLabel = new JLabel("<html>" + escapeHtml(value) + "</html>");
			grid.add(keyLabel, key);
			grid.add(valueLabel, val);
		}

		void addMonoText(String text) {
			java.awt.GridBagConstraints constraints = span(row++);
			JLabel label = new JLabel(escapeHtml(text));
			label.setFont(MONO_FONT);
			grid.add(label, constraints);
		}

		void addFileRow(String path, String detail, Instant lastModified) {
			java.awt.GridBagConstraints pathConstraints = new java.awt.GridBagConstraints();
			pathConstraints.gridx = 0;
			pathConstraints.gridy = row;
			pathConstraints.anchor = java.awt.GridBagConstraints.NORTHWEST;
			pathConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
			pathConstraints.weightx = 1.0;
			pathConstraints.insets = new java.awt.Insets(1, 0, 1, 6);

			java.awt.GridBagConstraints detailConstraints = new java.awt.GridBagConstraints();
			detailConstraints.gridx = 1;
			detailConstraints.gridy = row;
			detailConstraints.anchor = java.awt.GridBagConstraints.NORTHEAST;
			detailConstraints.insets = new java.awt.Insets(1, 0, 1, 0);
			row++;

			String detailText = detail;
			if (lastModified != null) {
				detailText = detail + "  |  " + formatInstant(lastModified);
			}

			JLabel pathLabel = new JLabel(escapeHtml(path));
			pathLabel.setFont(MONO_SMALL);

			JLabel detailLabel = new JLabel(escapeHtml(detailText));
			detailLabel.setFont(MONO_SMALL);
			detailLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

			grid.add(pathLabel, pathConstraints);
			grid.add(detailLabel, detailConstraints);
		}

		private java.awt.GridBagConstraints span(int targetRow) {
			java.awt.GridBagConstraints constraints = new java.awt.GridBagConstraints();
			constraints.gridx = 0;
			constraints.gridy = targetRow;
			constraints.gridwidth = 2;
			constraints.anchor = java.awt.GridBagConstraints.NORTHWEST;
			constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
			constraints.weightx = 1.0;
			return constraints;
		}
	}
}
