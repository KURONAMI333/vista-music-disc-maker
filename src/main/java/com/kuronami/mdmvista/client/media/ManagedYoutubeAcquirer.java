package com.kuronami.mdmvista.client.media;

import com.kuronami.mdmvista.network.YoutubeVideoUrls;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Pinned, addon-owned YouTube acquisition. It prefers combined MP4 and packet-copies split AV locally. */
public final class ManagedYoutubeAcquirer {
    private static final long MAX_MEDIA_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_RUNTIME_BYTES = 160L * 1024L * 1024L;
    private static final long PROCESS_TIMEOUT_SECONDS = 600;
    private static final long STREAM_RESOLVE_TIMEOUT_SECONDS = 60;
    private static final String VIDEO_SELECTOR = "bestvideo[ext=mp4][vcodec^=avc1][height<=480]"
            + "/bestvideo[ext=mp4][vcodec^=avc1][height<=1080][width<=1920]"
            + "/bestvideo[ext=mp4][vcodec!=none][height<=1080][width<=1920]"
            + "/bestvideo[ext=mp4][vcodec!=none][height<=1920][width<=1080]";
    private static final String AUDIO_SELECTOR = "bestaudio[ext=m4a][acodec!=none]";
    private static final String EJS_SENTINEL = "_internal/yt_dlp_ejs/yt/solver/core.min.js";
    private static final ConcurrentHashMap<Path, Object> CACHE_LOCKS = new ConcurrentHashMap<>();

    enum FormatKind { COMBINED_MP4, VIDEO_MP4, AUDIO_M4A }

    @FunctionalInterface
    interface MediaFetcher {
        Path fetch(FormatKind kind, Path staging, String canonicalUrl,
                BooleanSupplier current, long deadlineNanos) throws Exception;
    }

    private record Asset(String uri, String archiveSha256, String archiveName, String executableName,
            String zipMember, boolean fullArchive) {}
    private record RuntimeFiles(Path ytDlp, Path deno) {}
    public record MediaStreams(String videoUrl, String audioUrl) {}

    private final Path root;
    private final ManagedMuxedFileIndex index;
    private final MediaFetcher testFetcher;

    public ManagedYoutubeAcquirer(Path root, ManagedMuxedFileIndex index) throws IOException {
        this(root, index, null);
    }

    ManagedYoutubeAcquirer(Path root, ManagedMuxedFileIndex index, MediaFetcher testFetcher) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.index = Objects.requireNonNull(index, "index");
        this.testFetcher = testFetcher;
        Files.createDirectories(this.root);
        if (!this.root.toRealPath().equals(this.root)) throw new IOException("managed media root must be canonical");
    }

    /** Resolves two short-lived HTTPS stream URLs without downloading media. */
    public MediaStreams resolveStreams(String rawUrl, BooleanSupplier current) throws Exception {
        java.util.Objects.requireNonNull(current, "current");
        String canonical = YoutubeVideoUrls.canonicalVideoUrl(rawUrl);
        if (canonical.isBlank() || !canonical.equals(rawUrl) || testFetcher != null) return null;
        RuntimeFiles runtime = installRuntime(current, System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_TIMEOUT_SECONDS));
        requireCurrent(current);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STREAM_RESOLVE_TIMEOUT_SECONDS);
        String selector = "(" + VIDEO_SELECTOR + ")+(" + AUDIO_SELECTOR + ")";
        ProcessBuilder builder = new ProcessBuilder(runtime.ytDlp().toString(), "--ignore-config",
                "--no-plugin-dirs", "--no-remote-components", "--no-playlist", "--no-progress",
                "--no-warnings", "--no-cache-dir", "--js-runtimes", "deno:" + runtime.deno(),
                "-f", selector, "-g", "--", canonical);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        // stdout goes to a temp file (signed URLs; never logged) so a full pipe cannot stall yt-dlp.
        sweepStaleStreamFiles();
        Path urlFile = Files.createTempFile(root, ".streams-", ".txt");
        try {
            builder.redirectOutput(urlFile.toFile());
            Process process = builder.start();
            int exit = waitFor(process, null, current, deadline);
            if (exit != 0) return null;
            requireCurrent(current, deadline);
            if (Files.size(urlFile) > 65_536) return null;
            java.util.List<String> urls = new String(Files.readAllBytes(urlFile), StandardCharsets.UTF_8)
                    .lines().map(String::trim).filter(value -> value.startsWith("https://")).toList();
            if (urls.size() != 2 || urls.stream().anyMatch(value -> value.length() > 8192
                    || value.chars().anyMatch(Character::isWhitespace))) return null;
            return new MediaStreams(urls.get(0), urls.get(1));
        } finally {
            try {
                Files.deleteIfExists(urlFile);
            } catch (IOException | RuntimeException ignored) {
                urlFile.toFile().deleteOnExit();
            }
        }
    }

    /**
     * Best-effort removal of stream-URL temp files left by earlier runs; failures are ignored and nothing is logged.
     * Only files older than 10 minutes are removed, so a concurrent resolve (bounded by its 60 s deadline) keeps its file.
     */
    private void sweepStaleStreamFiles() {
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10);
        try (java.nio.file.DirectoryStream<Path> stale = Files.newDirectoryStream(root, ".streams-*.txt")) {
            for (Path file : stale) {
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < cutoff) Files.deleteIfExists(file);
                } catch (IOException | RuntimeException ignored) {}
            }
        } catch (IOException | RuntimeException ignored) {}
    }

    /** Returns a cached muxed file or downloads one. The supplied predicate must become false when the MDM job is stale. */
    public Path acquire(UUID sourceId, long generation, String sourceKey, String rawUrl,
            BooleanSupplier current) throws Exception {
        java.util.Objects.requireNonNull(current, "current");
        String canonical = YoutubeVideoUrls.canonicalVideoUrl(rawUrl);
        if (sourceId == null || generation < 0 || sourceKey == null || sourceKey.isBlank()
                || canonical.isBlank() || !canonical.equals(rawUrl)) return null;
        Path target = index.pathFor(sourceId, canonical);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_TIMEOUT_SECONDS);
        Object lock = CACHE_LOCKS.computeIfAbsent(target, ignored -> new Object());
        synchronized (lock) {
            requireCurrent(current);
            if (Files.isRegularFile(target) && Files.size(target) > 0 && Files.size(target) <= MAX_MEDIA_BYTES)
                return target.toRealPath();
            RuntimeFiles runtime = testFetcher == null ? installRuntime(current, deadline) : null;
            requireCurrent(current);
            Path staging = Files.createTempDirectory(root, ".acquire-");
            try {
                Path combined = fetch(runtime, FormatKind.COMBINED_MP4, staging, canonical, current, deadline);
                requireCurrent(current, deadline);
                if (isBoundedStagingFile(staging, combined, MAX_MEDIA_BYTES)) {
                    ensureMuxNativeRuntime(current, deadline);
                    if (SplitStreamPacketMuxer.hasVideoAndAudio(combined, current, deadline)) {
                        requireCurrent(current, deadline);
                        commit(staging, combined, target);
                        return target.toRealPath();
                    }
                }
                Files.deleteIfExists(staging.resolve("combined.mp4"));
                Files.deleteIfExists(staging.resolve("combined.mp4.part"));

                Path video = fetch(runtime, FormatKind.VIDEO_MP4, staging, canonical, current, deadline);
                requireCurrent(current, deadline);
                if (!isBoundedStagingFile(staging, video, MAX_MEDIA_BYTES)) return null;
                Path audio = fetch(runtime, FormatKind.AUDIO_M4A, staging, canonical, current, deadline);
                requireCurrent(current, deadline);
                if (!isBoundedStagingFile(staging, audio, MAX_MEDIA_BYTES)) return null;
                long inputBytes = Math.addExact(Files.size(video), Files.size(audio));
                if (inputBytes > MAX_MEDIA_BYTES) return null;

                ensureMuxNativeRuntime(current, deadline);
                Path media = staging.resolve("muxed.partial.mp4");
                SplitStreamPacketMuxer.MuxStats mux = SplitStreamPacketMuxer.mux(
                        video, audio, media, current, deadline, MAX_MEDIA_BYTES);
                if (mux.video().packets() <= 0 || mux.audio().packets() <= 0
                        || !SplitStreamPacketMuxer.hasVideoAndAudio(media, current, deadline)) return null;
                requireCurrent(current, deadline);
                Files.createDirectories(target.getParent());
                commit(staging, media, target);
                return target.toRealPath();
            } finally {
                deleteTree(staging);
            }
        }
    }

    private Path fetch(RuntimeFiles runtime, FormatKind kind, Path staging, String canonical,
            BooleanSupplier current, long deadline) throws Exception {
        if (testFetcher != null) return testFetcher.fetch(kind, staging, canonical, current, deadline);
        return downloadWithYtDlp(runtime, kind, staging, canonical, current, deadline);
    }

    private Path downloadWithYtDlp(RuntimeFiles runtime, FormatKind kind, Path staging, String canonical,
            BooleanSupplier current, long deadline) throws Exception {
        requireCurrent(current, deadline);
        String stem = switch (kind) {
            case COMBINED_MP4 -> "combined";
            case VIDEO_MP4 -> "video";
            case AUDIO_M4A -> "audio";
        };
        String selector = switch (kind) {
            case COMBINED_MP4 -> "best[ext=mp4][vcodec^=avc1][height<=480]"
                    + "/best[ext=mp4][vcodec^=avc1]/best[ext=mp4][vcodec!=none][acodec!=none]";
            case VIDEO_MP4 -> VIDEO_SELECTOR;
            case AUDIO_M4A -> AUDIO_SELECTOR;
        };
        Path template = staging.resolve(stem + ".%(ext)s");
        ProcessBuilder builder = new ProcessBuilder(runtime.ytDlp().toString(), "--ignore-config",
                "--no-plugin-dirs", "--no-remote-components", "--no-playlist", "--no-progress",
                "--no-warnings", "--no-cache-dir", "--fixup", "never",
                "--js-runtimes", "deno:" + runtime.deno(), "-f", selector,
                "-o", template.toString(), "--", canonical);
        builder.directory(staging.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        int exit = waitFor(process, staging, current, deadline);
        if (exit != 0) return null;
        requireCurrent(current, deadline);
        Path expected = staging.resolve(stem + switch (kind) {
            case COMBINED_MP4, VIDEO_MP4 -> ".mp4";
            case AUDIO_M4A -> ".m4a";
        });
        return isBoundedStagingFile(staging, expected, MAX_MEDIA_BYTES) ? expected : null;
    }

    private void ensureMuxNativeRuntime(BooleanSupplier current, long deadline) throws IOException {
        requireCurrent(current, deadline);
        ManagedNativeRuntime.ensure(root.resolveSibling("native-cache"),
                () -> current.getAsBoolean() && System.nanoTime() < deadline, deadline);
    }

    private static boolean isBoundedStagingFile(Path staging, Path candidate, long maxBytes) throws IOException {
        if (candidate == null || !Files.isRegularFile(candidate) || Files.size(candidate) <= 0
                || Files.size(candidate) > maxBytes) return false;
        Path realStage = staging.toRealPath();
        Path realCandidate = candidate.toRealPath();
        return realCandidate.startsWith(realStage);
    }

    private static void commit(Path staging, Path media, Path target) throws IOException {
        if (!isBoundedStagingFile(staging, media, MAX_MEDIA_BYTES))
            throw new IOException("completed media is outside the bounded staging directory");
        Files.createDirectories(target.getParent());
        moveAtomically(media, target);
    }

    private synchronized RuntimeFiles installRuntime(BooleanSupplier current, long deadline) throws Exception {
        requireCurrent(current, deadline);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        final Asset downloader;
        final Asset js;
        if (os.startsWith("windows") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            downloader = new Asset("https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp_win.zip",
                    "30b4c14aafab6082becff7881e41b76df46dc43ea7633479410a91e29da492bf",
                    "yt-dlp-2026.08.19-win.zip", "yt-dlp.exe", "yt-dlp.exe", true);
            js = new Asset("https://github.com/denoland/deno/releases/download/v2.9.6/deno-x86_64-pc-windows-msvc.zip",
                    "15e5300b0ba3c3695a7621d90160a746ec9e710228cee639afa9d580f6e3cd11",
                    "deno-2.9.6-win-x64.zip", "deno.exe", "deno.exe", false);
        } else if ((os.startsWith("mac") || os.startsWith("darwin"))
                && (arch.equals("aarch64") || arch.equals("arm64"))) {
            downloader = new Asset("https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp_macos.zip",
                    "07e54b0865303c864006925913bce2604f8ee8cc6f18699bac9c309f9328a6d8",
                    "yt-dlp-2026.08.19-macos-arm64.zip", "yt-dlp_macos", "yt-dlp_macos", true);
            js = new Asset("https://github.com/denoland/deno/releases/download/v2.9.6/deno-aarch64-apple-darwin.zip",
                    "213a2f304f04d3c9cb5220669afad138f60a5aab1fe80962abdeb8f35807a472",
                    "deno-2.9.6-macos-arm64.zip", "deno", "deno", false);
        } else {
            throw new IOException("YouTube acquisition is unavailable on this OS/CPU");
        }
        String platform = os.startsWith("windows") ? "windows-x86_64" : "macos-aarch64";
        Path bin = root.resolve("runtime").resolve(platform).resolve("yt-dlp-2026.08.19-deno-2.9.6");
        Files.createDirectories(bin);
        Path yt = installZipAsset(downloader, bin, current, deadline);
        Path deno = installZipAsset(js, bin, current, deadline);
        requireCurrent(current, deadline);
        return new RuntimeFiles(yt, deno);
    }

    private Path installZipAsset(Asset asset, Path bin, BooleanSupplier current, long deadline) throws Exception {
        Path archive = bin.resolve(asset.archiveName());
        if (!Files.isRegularFile(archive) || !asset.archiveSha256().equals(sha256(archive))) {
            Path part = archive.resolveSibling(archive.getFileName() + ".part");
            Files.deleteIfExists(part);
            try {
                download(URI.create(asset.uri()), part, MAX_DOWNLOAD_BYTES, current, deadline);
                requireCurrent(current, deadline);
                if (!asset.archiveSha256().equals(sha256(part))) throw new IOException("runtime download checksum mismatch");
                requireCurrent(current, deadline);
                moveAtomically(part, archive);
            } finally {
                Files.deleteIfExists(part);
            }
        }
        requireCurrent(current, deadline);
        if (asset.fullArchive()) return installBundle(asset, archive, bin, current, deadline);
        Path executable = bin.resolve(asset.executableName());
        Path verified = bin.resolve(asset.executableName() + ".archive-sha256");
        if (Files.isRegularFile(executable) && Files.isRegularFile(verified)) {
            String marker = asset.archiveSha256() + "\n" + sha256(executable) + "\n";
            if (Files.readString(verified).equals(marker)) return executable.toRealPath();
        }
        Path temp = Files.createTempFile(bin, ".runtime-", ".part");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            requireCurrent(current, deadline);
            ZipEntry entry = zip.getEntry(asset.zipMember());
            if (entry == null || entry.isDirectory() || entry.getSize() < 1 || entry.getSize() > MAX_EXTRACTED_RUNTIME_BYTES)
                throw new IOException("pinned runtime archive has an unexpected executable entry");
            try (InputStream in = zip.getInputStream(entry)) { copyBounded(in, temp, MAX_EXTRACTED_RUNTIME_BYTES, current, deadline); }
            requireCurrent(current, deadline);
            moveAtomically(temp, executable);
            if (!isWindows()) executable.toFile().setExecutable(true, true);
            Files.writeString(verified, asset.archiveSha256() + "\n" + sha256(executable) + "\n");
            return executable.toRealPath();
        } finally { Files.deleteIfExists(temp); }
    }

    private Path installBundle(Asset asset, Path archive, Path bin, BooleanSupplier current, long deadline) throws Exception {
        Path installed = bin.resolve(asset.executableName() + "-bundle");
        Path executable = installed.resolve(asset.zipMember()).normalize();
        Path marker = installed.resolve(".archive-sha256");
        if (Files.isRegularFile(executable) && Files.isRegularFile(installed.resolve(EJS_SENTINEL))
                && Files.isRegularFile(marker)
                && Files.readString(marker).equals(asset.archiveSha256() + "\n" + sha256(executable) + "\n"))
            return executable.toRealPath();

        Path staging = Files.createTempDirectory(bin, ".bundle-");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            long declaredTotal = 0L;
            long extractedTotal = 0L;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                requireCurrent(current, deadline);
                ZipEntry entry = entries.nextElement();
                Path output = staging.resolve(entry.getName()).normalize();
                if (!output.startsWith(staging) || entry.getName().startsWith("/"))
                    throw new IOException("pinned runtime archive contains an unsafe path");
                if (entry.getSize() < 0) throw new IOException("pinned runtime archive has unknown member size");
                declaredTotal = Math.addExact(declaredTotal, entry.getSize());
                if (declaredTotal > MAX_EXTRACTED_RUNTIME_BYTES)
                    throw new IOException("pinned runtime archive exceeds its extraction size limit");
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                Files.createDirectories(output.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    extractedTotal = Math.addExact(extractedTotal,
                            copyBounded(in, output, MAX_EXTRACTED_RUNTIME_BYTES, current, deadline));
                }
                if (extractedTotal > MAX_EXTRACTED_RUNTIME_BYTES)
                    throw new IOException("pinned runtime archive exceeds its extraction size limit");
            }
            Path stagedExecutable = staging.resolve(asset.zipMember()).normalize();
            requireCurrent(current, deadline);
            if (!stagedExecutable.startsWith(staging) || !Files.isRegularFile(stagedExecutable))
                throw new IOException("pinned runtime bundle is missing its executable");
            if (!Files.isRegularFile(staging.resolve(EJS_SENTINEL)))
                throw new IOException("pinned runtime bundle is missing its EJS component");
            if (!isWindows()) stagedExecutable.toFile().setExecutable(true, true);
            Files.writeString(staging.resolve(".archive-sha256"),
                    asset.archiveSha256() + "\n" + sha256(stagedExecutable) + "\n");
            requireCurrent(current, deadline);
            if (Files.exists(installed)) deleteTree(installed);
            moveAtomically(staging, installed);
            return installed.resolve(asset.zipMember()).toRealPath();
        } catch (Exception failure) {
            deleteTree(staging);
            throw failure;
        } catch (Error failure) {
            deleteTree(staging);
            throw failure;
        }
    }

    private static int waitFor(Process process, Path staging, BooleanSupplier current, long deadline) throws Exception {
        try {
            while (process.isAlive()) {
                if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()
                        || System.nanoTime() >= deadline
                        || (staging != null && directoryBytes(staging) > MAX_MEDIA_BYTES + 1024 * 1024)) {
                    terminate(process);
                    return -1;
                }
                if (process.waitFor(250, TimeUnit.MILLISECONDS)) break;
            }
            return process.exitValue();
        } catch (InterruptedException interrupted) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(child -> { try { child.destroyForcibly(); } catch (RuntimeException ignored) {} });
        process.destroyForcibly();
        try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void download(URI uri, Path target, long maxBytes, BooleanSupplier current, long deadline) throws IOException {
        requireCurrent(current, deadline);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(timeoutMillis(deadline, 15_000));
        connection.setReadTimeout(timeoutMillis(deadline, 30_000));
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "MDM-Vista-addon/0.1");
        try {
            int status = connection.getResponseCode();
            String host = connection.getURL().getHost();
            if (status != 200 || !(host.equalsIgnoreCase("github.com")
                    || host.equalsIgnoreCase("objects.githubusercontent.com")
                    || host.equalsIgnoreCase("release-assets.githubusercontent.com")))
                throw new IOException("pinned runtime download failed");
            requireCurrent(current, deadline);
            try (InputStream in = connection.getInputStream()) { copyBounded(in, target, maxBytes, current, deadline); }
        } finally { connection.disconnect(); }
    }

    private static long copyBounded(InputStream in, Path target, long maxBytes) throws IOException {
        return copyBounded(in, target, maxBytes, () -> true, Long.MAX_VALUE);
    }

    private static long copyBounded(InputStream in, Path target, long maxBytes, BooleanSupplier current, long deadline) throws IOException {
        try (var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            for (int count; (count = in.read(buffer)) >= 0;) {
                requireCurrent(current, deadline);
                total += count;
                if (total > maxBytes) throw new IOException("runtime/media size limit exceeded");
                out.write(buffer, 0, count);
            }
            return total;
        }
    }

    private static long directoryBytes(Path dir) throws IOException {
        try (var files = Files.walk(dir)) { return files.filter(Files::isRegularFile).mapToLong(p -> {
            try { return Files.size(p); } catch (IOException e) { return Long.MAX_VALUE; }
        }).sum(); }
    }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = in.read(buffer)) >= 0;) if (count > 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"); }
    private static void requireCurrent(BooleanSupplier current) throws IOException {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) throw new IOException("YouTube acquisition was superseded");
    }
    private static void requireCurrent(BooleanSupplier current, long deadline) throws IOException {
        requireCurrent(current);
        if (System.nanoTime() >= deadline) throw new IOException("YouTube acquisition exceeded its 10 minute deadline");
    }
    private static int timeoutMillis(long deadline, int maximum) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("YouTube acquisition exceeded its 10 minute deadline");
        return (int) Math.max(1L, Math.min(maximum, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }
    private static void moveAtomically(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) { paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
            try { Files.deleteIfExists(path); } catch (IOException ignored) {}
        }); } catch (IOException ignored) {}
    }
}
