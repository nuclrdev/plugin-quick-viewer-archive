# Archive Quick Viewer

A [Nuclr Commander](https://nuclr.dev) plugin that renders a read-only quick preview for common archive formats. It does not extract files or mount the archive. Instead, it shows the generally available metadata you usually want at a glance: file counts, total sizes, root entries, timestamps, and a bounded file listing.

---

## What It Shows

| Section | Details |
|---|---|
| **Summary** | Archive name, detected format, archive size, modified time, entry/file/directory counts, root entry count |
| **Totals** | Total unpacked size and, where available, packed size and compression ratio |
| **Details** | Earliest and latest entry timestamps, archive comment when present |
| **Root Entries** | Top-level files/folders with descendant counts, aggregate size, and latest timestamp |
| **Warnings** | Encryption flags, multi-volume hints, truncated lists, or metadata limits |
| **Files** | A bounded listing of archive members with path, size, and timestamp when available |

---

## Supported Formats

The current implementation supports quick inspection of:

- `zip`, `jar`, `war`, `ear`
- `apk`, `xapk`, `apks`, `apkm`
- `tar`
- `tar.gz`, `tgz`
- `tar.bz2`, `tbz2`, `tbz`
- `tar.xz`, `txz`
- `gz`, `bz2`, `xz`
- `7z`
- `rar`
- `cpio`
- `ar`

Some formats expose more metadata than others. For example, packed sizes are usually available for ZIP/RAR entries, but not for all stream-based archive formats.

---

## Design Notes

- **Read-only preview**: no extraction, mutation, or external tools
- **Cancellation-aware**: switching files cancels the in-flight parse
- **Asynchronous loading**: parsing runs off the Swing EDT on a virtual thread
- **Bounded UI output**: the file list is capped so very large archives do not flood the quick-view panel
- **Best-effort metadata**: timestamps, comments, compression totals, and encryption flags are shown when the underlying format exposes them

---

## Building

Prerequisites:

- **Java 21+**
- **Maven 3.9+**
- Local `plugins-sdk` install

Build and test:

```bash
mvn clean test
```

Package the plugin:

```bash
mvn clean package -DskipTests
```

Artifacts are written to `target/`:

```text
quick-view-archive-1.0.0.jar
quick-view-archive-1.0.0.zip
```

### Signed build

If you want the detached plugin signature as well:

```bash
mvn clean verify -Djarsigner.storepass=<keystore-password>
```

This expects the signing keystore at:

```text
C:/nuclr/key/nuclr-signing.p12
```

---

## Installation

Copy the packaged plugin into Nuclr Commander’s `plugins/` directory:

```text
quick-view-archive-1.0.0.zip
```

If you produced a signed build, copy the signature too:

```text
quick-view-archive-1.0.0.zip.sig
```

---

## Repository Layout

```text
src/
|-- main/java/dev/nuclr/plugin/core/quick/viewer/
|   |-- ArchiveQuickViewProvider.java   # Quick-view provider entry point
|   |-- ArchiveViewPanel.java           # Swing UI renderer
|   `-- archive/
|       |-- ArchiveParser.java          # Format detection and metadata extraction
|       |-- ArchiveMetadata.java        # Parsed archive summary model
|       |-- ArchiveEntryInfo.java       # Individual file entry model
|       `-- ArchiveRootInfo.java        # Top-level aggregate model
|-- main/resources/
|   |-- plugin.json
|   `-- README
`-- test/java/dev/nuclr/plugin/core/quick/viewer/archive/
    `-- ArchiveParserTest.java
```

---

## Implementation Notes

- ZIP-family archives are read with Apache Commons Compress `ZipFile`
- TAR and compressed TAR variants are inspected as streams
- 7z support uses Commons Compress plus `org.tukaani:xz`
- RAR support uses `junrar`
- Single-file compressed formats such as plain `.gz` can only expose limited metadata compared with container formats like `.zip` or `.tar.gz`

---

## License

Apache License 2.0. See [LICENSE](LICENSE).
