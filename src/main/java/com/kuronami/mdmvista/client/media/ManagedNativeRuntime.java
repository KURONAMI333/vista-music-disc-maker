package com.kuronami.mdmvista.client.media;

import org.bytedeco.javacpp.Loader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs only the current host's pinned JavaCPP/FFmpeg native archives on the MDM playback worker. */
public final class ManagedNativeRuntime {
    private static final long MAX_ARCHIVE_BYTES = 30L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 160L * 1024 * 1024;
    private static final long MAX_INSTALL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(180);
    private static final java.util.concurrent.locks.ReentrantLock LOCK = new java.util.concurrent.locks.ReentrantLock();
    private static volatile Path loadedPath;

    private record Platform(String classifier, String os, String arch, String ffmpegSha, String javacppSha) {}
    private record Artifact(String name, String sha256, String prefix) {}
    private ManagedNativeRuntime() {}

    public static Path ensure(Path cacheRoot) throws IOException {
        return ensure(cacheRoot, () -> true);
    }

    public static Path ensure(Path cacheRoot, java.util.function.BooleanSupplier stillCurrent) throws IOException {
        return ensure(cacheRoot, stillCurrent, Long.MAX_VALUE);
    }

    static Path ensure(Path cacheRoot, java.util.function.BooleanSupplier stillCurrent,
            long callerDeadlineNanos) throws IOException {
        Platform platform = platform();
        Path root = cacheRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        root = root.toRealPath();
        long deadline = Math.min(System.nanoTime() + MAX_INSTALL_NANOS, callerDeadlineNanos);
        try {
            if (!LOCK.tryLock(remainingMillis(deadline), java.util.concurrent.TimeUnit.MILLISECONDS))
                throw new java.net.SocketTimeoutException("native runtime lock exceeded its 180 second bound");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("native runtime lock interrupted");
        }
        try {
            checkState(deadline, stillCurrent);
            if (loadedPath != null && loadedPath.startsWith(root) && Files.isDirectory(loadedPath)) return loadedPath;
            Path target = root.resolve("javacv-1.5.11-" + platform.classifier()).normalize();
            if (!target.startsWith(root)) throw new SecurityException("native cache path escaped managed root");
            List<Artifact> artifacts = artifacts(platform);
            if (!verifyInstalled(target, artifacts, deadline, stillCurrent))
                install(root, target, artifacts, deadline, stillCurrent);
            checkState(deadline, stillCurrent);
            String nativePath = target.toRealPath().toString();
            loadFfmpegWithAddonPaths(nativePath, deadline, stillCurrent);
            loadedPath = target.toRealPath();
            return loadedPath;
        } finally {
            LOCK.unlock();
        }
    }

    private static Platform platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64")))
            return new Platform("windows-x86_64", "windows", "x86_64",
                    "63205da750f3fbb7fa0e5d23b9e00fc1257e478c33d02dcee9299268309d594b",
                    "cb67ca46bfaf54782d38554aab591f2caffa9a89124789866afc8c8203034cf3");
        if (os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64")))
            return new Platform("macosx-arm64", "macosx", "arm64",
                    "bae41342be096b898e71060ccba2010af5ee8f5dc722d573c12efef791bf1d10",
                    "ae071440706f62d20825f177308e996ae7aeffea49c0eb3e88cfeea2fe302928");
        throw new UnsupportedOperationException("managed JavaCV native runtime is unavailable for " + os + "/" + arch);
    }

    private static List<Artifact> artifacts(Platform p) {
        return List.of(
                new Artifact("javacpp-1.5.11-" + p.classifier() + ".jar", p.javacppSha(), "org/bytedeco/javacpp/" + p.classifier() + "/"),
                new Artifact("ffmpeg-7.1-1.5.11-" + p.classifier() + ".jar", p.ffmpegSha(), "org/bytedeco/ffmpeg/" + p.classifier() + "/"));
    }

    private static boolean verifyInstalled(Path target, List<Artifact> artifacts, long deadline,
            java.util.function.BooleanSupplier stillCurrent) throws IOException {
        Path index = target.resolve("native-files.sha256");
        if (!Files.isRegularFile(index)) return false;
        Map<String, String> expected = readIndex(index);
        if (expected.isEmpty()) return false;
        long total = 0;
        for (Map.Entry<String, String> item : expected.entrySet()) {
            checkState(deadline, stillCurrent);
            Path file = target.resolve(item.getKey()).normalize();
            if (!file.startsWith(target) || !Files.isRegularFile(file)) return false;
            total += Files.size(file);
            if (total > MAX_EXPANDED_BYTES || !sha256(file, deadline, stillCurrent).equals(item.getValue())) return false;
        }
        // The published macOS arm64 classifier currently contains an x86_64 libatomic. FFmpeg's
        // arm64 dylibs do not link it; reject old caches that extracted this mismatched optional preload.
        if (pMacArm64() && expected.containsKey("libatomic.1.dylib")) return false;
        String suffix = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ".dll" : ".dylib";
        String prefix = suffix.equals(".dylib") ? "lib" : "";
        return expected.containsKey(prefix + "jnijavacpp" + suffix)
                && expected.containsKey(prefix + "jniavcodec" + suffix);
    }

    private static void install(Path root, Path target, List<Artifact> artifacts, long deadline,
            java.util.function.BooleanSupplier stillCurrent) throws IOException {
        Platform platform = platform();
        Path archiveDir = root.resolve("archives");
        Files.createDirectories(archiveDir);
        Path staging = root.resolve("native-staging").normalize();
        if (!staging.startsWith(root)) throw new SecurityException("invalid native staging path");
        deleteTree(staging);
        Files.createDirectories(staging);
        long expanded = 0;
        Map<String, String> hashes = new TreeMap<>();
        try {
            for (Artifact artifact : artifacts) {
                Path archive = archiveDir.resolve(artifact.name());
                if (!Files.isRegularFile(archive) || Files.size(archive) > MAX_ARCHIVE_BYTES
                        || !sha256(archive, deadline, stillCurrent).equals(artifact.sha256()))
                    download(archive, artifact, deadline, stillCurrent);
                try (InputStream raw = Files.newInputStream(archive);
                     ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                    for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                        checkState(deadline, stillCurrent);
                        if (entry.isDirectory() || !entry.getName().startsWith(artifact.prefix())) continue;
                        String fileName = entry.getName().substring(artifact.prefix().length());
                        if (!isNative(fileName)) continue; // The upstream jar also carries optional ffmpeg/ffprobe executables.
                        if (platform.os().equals("macosx") && platform.arch().equals("arm64")
                                && fileName.equals("libatomic.1.dylib")) continue;
                        if (!fileName.matches("[A-Za-z0-9._-]+"))
                            throw new IOException("unexpected native archive entry: " + entry.getName());
                        Path out = staging.resolve(fileName).normalize();
                        if (!out.startsWith(staging) || hashes.containsKey(fileName))
                            throw new IOException("duplicate or unsafe native archive entry");
                        MessageDigest digest = digest();
                        try (var stream = new BufferedOutputStream(Files.newOutputStream(out, StandardOpenOption.CREATE_NEW))) {
                            byte[] buffer = new byte[64 * 1024];
                            for (int count; (count = zip.read(buffer)) != -1;) {
                                checkState(deadline, stillCurrent);
                                expanded += count;
                                if (expanded > MAX_EXPANDED_BYTES) throw new IOException("native expansion exceeds size limit");
                                digest.update(buffer, 0, count); stream.write(buffer, 0, count);
                            }
                        }
                        hashes.put(fileName, HexFormat.of().formatHex(digest.digest()));
                        if (hashes.size() > 256) throw new IOException("too many native files");
                    }
                }
            }
            if (hashes.isEmpty()) throw new IOException("native archives contained no supported libraries");
            Path index = staging.resolve("native-files.sha256");
            List<String> lines = new ArrayList<>();
            hashes.forEach((name, hash) -> lines.add(hash + "  " + name));
            Files.write(index, lines, java.nio.charset.StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            if (Files.exists(target)) deleteTree(target);
            try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(staging, target); }
            if (!verifyInstalled(target, artifacts, deadline, stillCurrent))
                throw new IOException("installed native cache failed its post-check");
        } catch (IOException | RuntimeException failure) {
            deleteTree(staging);
            throw failure;
        }
    }

    private static void download(Path destination, Artifact artifact, long deadline,
            java.util.function.BooleanSupplier stillCurrent) throws IOException {
        String relative = artifact.name().startsWith("ffmpeg-")
                ? "ffmpeg/7.1-1.5.11/" : "javacpp/1.5.11/";
        URI uri = URI.create("https://repo.maven.apache.org/maven2/org/bytedeco/" + relative + artifact.name());
        Path part = destination.resolveSibling(destination.getFileName() + ".part");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        int remainingMillis = remainingMillis(deadline);
        connection.setConnectTimeout(Math.min(15_000, remainingMillis));
        connection.setReadTimeout(Math.min(30_000, remainingMillis));
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("native archive download returned HTTP " + connection.getResponseCode());
            long declared = connection.getContentLengthLong();
            if (declared < 0 || declared > MAX_ARCHIVE_BYTES) throw new IOException("native archive has invalid size");
            MessageDigest digest = digest(); long total = 0;
            try (InputStream in = connection.getInputStream(); var out = Files.newOutputStream(part,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = in.read(buffer)) != -1;) {
                    checkState(deadline, stillCurrent);
                    total += count;
                    if (total > MAX_ARCHIVE_BYTES) throw new IOException("native archive exceeds size limit");
                    digest.update(buffer, 0, count); out.write(buffer, 0, count);
                }
            }
            if (total != declared || !HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256()))
                throw new IOException("native archive checksum/length mismatch");
            try { Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException failure) {
            Files.deleteIfExists(part);
            throw failure;
        } finally { connection.disconnect(); }
    }

    private static boolean isNative(String name) {
        return name.endsWith(".dll") || name.endsWith(".dylib") || name.endsWith(".so");
    }
    private static void loadFfmpegWithAddonPaths(String nativePath, long deadline,
            java.util.function.BooleanSupplier stillCurrent) throws IOException {
        checkState(deadline, stillCurrent);
        ClassLoader classLoader = ManagedNativeRuntime.class.getClassLoader();
        Path directory = Path.of(nativePath);
        try {
            Class<?> avutil = Class.forName("org.bytedeco.ffmpeg.global.avutil", false, classLoader);
            Class<?> swresample = Class.forName("org.bytedeco.ffmpeg.global.swresample", false, classLoader);
            Class<?> avcodec = Class.forName("org.bytedeco.ffmpeg.global.avcodec", false, classLoader);
            Class<?> avformat = Class.forName("org.bytedeco.ffmpeg.global.avformat", false, classLoader);
            Class<?> swscale = Class.forName("org.bytedeco.ffmpeg.global.swscale", false, classLoader);
            Class<?> avfilter = Class.forName("org.bytedeco.ffmpeg.global.avfilter", false, classLoader);
            Class<?> avdevice = Class.forName("org.bytedeco.ffmpeg.global.avdevice", false, classLoader);
            Class<?> javaCpp = Loader.class;
            synchronized (Loader.class) {
                checkState(deadline, stillCurrent);
                Map<String, String> loaded = Loader.getLoadedLibraries();
                List<NativeLibrary> libraries = nativeLibraries(platform());
                for (NativeLibrary library : libraries) {
                    String existing = loaded.get(library.key());
                    if (existing != null && !samePath(existing, directory.resolve(library.file())))
                        throw new IOException("JavaCPP already loaded " + library.key() + " from another path");
                }
                for (NativeLibrary library : libraries) {
                    checkState(deadline, stillCurrent);
                    String existing = loaded.get(library.key());
                    if (existing != null) continue;
                    Class<?> owner = switch (library.owner()) {
                        case "javacpp" -> javaCpp;
                        case "avutil" -> avutil;
                        case "swresample" -> swresample;
                        case "avcodec" -> avcodec;
                        case "avformat" -> avformat;
                        case "swscale" -> swscale;
                        case "avfilter" -> avfilter;
                        case "avdevice" -> avdevice;
                        default -> throw new IllegalStateException("unknown FFmpeg native owner " + library.owner());
                    };
                    Path file = directory.resolve(library.file()).toRealPath();
                    String result = Loader.loadLibrary(owner, new java.net.URL[]{file.toUri().toURL()}, library.key());
                    if (result == null || !samePath(result, file))
                        throw new IOException("JavaCPP did not register managed native " + library.key());
                    loaded = Loader.getLoadedLibraries();
                }
            }
            // Generated global classes call Loader.load() from their static initializer. Their
            // normal JavaCPP keys are already registered to the managed absolute paths above.
            Class.forName("org.bytedeco.javacv.FFmpegFrameGrabber", true, classLoader);
            checkState(deadline, stillCurrent);
        } catch (ClassNotFoundException | LinkageError | java.net.MalformedURLException failure) {
            throw new IOException("unable to load the addon-owned JavaCV/FFmpeg libraries", failure);
        }
    }
    private record NativeLibrary(String owner, String file, String key) {}
    private static List<NativeLibrary> nativeLibraries(Platform p) {
        boolean windows = p.os().equals("windows");
        String prefix = windows ? "" : "lib", suffix = windows ? ".dll" : ".dylib";
        String[] ffmpeg = windows
                ? new String[]{"avutil-59", "swresample-5", "avcodec-61", "avformat-61", "swscale-8", "avfilter-10", "avdevice-61"}
                : new String[]{"avutil.59", "swresample.5", "avcodec.61", "avformat.61", "swscale.8", "avfilter.10", "avdevice.61"};
        String[] owners = {"avutil", "swresample", "avcodec", "avformat", "swscale", "avfilter", "avdevice"};
        List<NativeLibrary> result = new ArrayList<>();
        result.add(new NativeLibrary("javacpp", prefix + "jnijavacpp" + suffix, "jnijavacpp"));
        for (int i = 0; i < ffmpeg.length; i++) {
            String base = ffmpeg[i];
            String key = windows ? base : base.replaceFirst("\\.[0-9]+$", "@." + base.substring(base.lastIndexOf('.') + 1));
            result.add(new NativeLibrary(owners[i], prefix + base + suffix, key));
        }
        for (String owner : owners)
            result.add(new NativeLibrary(owner, prefix + "jni" + owner + suffix, "jni" + owner));
        return result;
    }
    private static boolean samePath(String loaded, Path expected) {
        try { return Path.of(loaded).toRealPath().equals(expected.toRealPath()); }
        catch (IOException | RuntimeException invalid) { return false; }
    }
    private static boolean pMacArm64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"));
    }
    private static int remainingMillis(long deadline) throws IOException {
        checkDeadline(deadline);
        long remaining = deadline - System.nanoTime();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
    }
    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("native installation interrupted");
        if (System.nanoTime() >= deadline) throw new java.net.SocketTimeoutException("native install exceeded its 180 second bound");
    }
    private static void checkState(long deadline, java.util.function.BooleanSupplier stillCurrent) throws IOException {
        checkDeadline(deadline);
        if (!stillCurrent.getAsBoolean()) throw new java.util.concurrent.CancellationException("source changed during native installation");
    }
    private static Map<String, String> readIndex(Path index) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (String line : Files.readAllLines(index, java.nio.charset.StandardCharsets.US_ASCII)) {
            if (line.length() < 67 || line.charAt(64) != ' ' || line.charAt(65) != ' ') return Map.of();
            String hash = line.substring(0, 64), name = line.substring(66);
            if (!hash.matches("[0-9a-f]{64}") || !name.matches("[A-Za-z0-9._-]+")) return Map.of();
            result.put(name, hash);
        }
        return result;
    }
    private static String sha256(Path path) throws IOException {
        return sha256(path, Long.MAX_VALUE, () -> true);
    }
    private static String sha256(Path path, long deadline, java.util.function.BooleanSupplier stillCurrent) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = in.read(buffer)) != -1;) {
                checkState(deadline, stillCurrent);
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isSymbolicLink(path)) throw new SecurityException("refusing to delete symlink in native cache");
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }
}
