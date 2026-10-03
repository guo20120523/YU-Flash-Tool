package io.yu.flash.root;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import io.yu.flash.core.VerifiedCopy;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.util.Arrays;

/** Root app_process entry, loaded ONLY from installed base.apk; never a general shell endpoint. */
public final class RootWriter {
    private static String text(String path) throws IOException { return new String(Files.readAllBytes(new File(path).toPath()), java.nio.charset.StandardCharsets.UTF_8).trim(); }
    private static void check(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
    // Android 8 bionic UAPI defines O_CLOEXEC=02000000 before the SDK exposes its Java name.
    // https://android.googlesource.com/platform/bionic/+/android-8.0.0_r1/libc/kernel/uapi/asm-generic/fcntl.h
    static final int API26_O_CLOEXEC = 0x80000;
    private static FileDescriptor open(String path, int flags, int mode) throws Exception {
        int closeOnExec = android.os.Build.VERSION.SDK_INT >= 27 ? OsConstants.O_CLOEXEC : API26_O_CLOEXEC;
        return Os.open(path, flags | closeOnExec | OsConstants.O_NOFOLLOW, mode);
    }
    private static final class Endpoint implements VerifiedCopy.Endpoint, AutoCloseable {
        final FileDescriptor fd;
        Endpoint(String path, int flags) throws Exception { fd = open(path, flags, 0); }
        public int read(byte[] b, int o, int n) throws IOException { try { return Os.read(fd, b, o, n); } catch (Exception e) { throw new IOException(e); } }
        public int write(byte[] b, int o, int n) throws IOException { try { return Os.write(fd, b, o, n); } catch (Exception e) { throw new IOException(e); } }
        public void rewind() throws IOException { try { Os.lseek(fd, 0, OsConstants.SEEK_SET); } catch (Exception e) { throw new IOException(e); } }
        public void sync() throws IOException { try { Os.fsync(fd); } catch (Exception e) { throw new IOException(e); } }
        public void close() throws Exception { Os.close(fd); }
    }
    // Bionic/Linux dev_t layout, not a cast to 8-bit major/minor.
    static String identity(long dev) {
        long major = ((dev >>> 8) & 0xfffL) | ((dev >>> 32) & 0xfffff000L);
        long minor = (dev & 0xffL) | ((dev >>> 12) & 0xffffff00L);
        return major + ":" + minor;
    }
    private static String property(String name) throws Exception {
        Process p = new ProcessBuilder("/system/bin/getprop", name).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[256]; int n;
        try (InputStream in = p.getInputStream()) { while ((n = in.read(b)) >= 0) { check(out.size() + n < 4096, "Property output too large"); out.write(b, 0, n); } }
        check(p.waitFor() == 0, "Property query failed"); return out.toString("UTF-8").trim();
    }
    private static String lockDirectory() throws Exception {
        StructStat data = Os.lstat("/data");
        check(OsConstants.S_ISDIR(data.st_mode) && (data.st_uid == 0 || data.st_uid == 1000) &&
            (data.st_mode & 0002) == 0 && ((data.st_mode & 0020) == 0 || data.st_gid == 0 || data.st_gid == 1000), "Untrusted /data parent");
        String directory = "/data/yu-flash-tool-writer";
        try { Os.mkdir(directory, 0700); }
        catch (android.system.ErrnoException e) { if (e.errno != OsConstants.EEXIST) throw e; }
        StructStat stat = Os.lstat(directory);
        check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == 0 && (stat.st_mode & 0777) == 0700 &&
            new File(directory).getCanonicalPath().equals(directory), "Unsafe root writer directory");
        return directory;
    }
    private static void guard(JSONObject r, Endpoint source, Endpoint target, String marker, String token) throws Exception {
        check(Os.getuid() == 0, "Not root");
        JSONObject permit = new JSONObject(text(marker));
        check(permit.getString("token").equals(token) && permit.getString("bootId").equals(text("/proc/sys/kernel/random/boot_id")), "Stale write permit");
        String device = r.getString("device"), alias = r.getString("alias"), id = r.getString("identity");
        check(device.matches("/dev/block/[A-Za-z0-9_./:-]+") && alias.startsWith("/dev/block/") && alias.contains("/by-name/"), "Invalid target paths");
        check(new File(alias).getCanonicalPath().equals(device) && new File(device).getCanonicalPath().equals(device), "Target alias changed");
        String block = new File(device).getName(), sys = "/sys/class/block/" + block;
        StructStat stat = Os.fstat(target.fd);
        check(OsConstants.S_ISBLK(stat.st_mode) && identity(stat.st_rdev).equals(id), "Pinned block identity mismatch");
        check(Os.stat(device).st_rdev == stat.st_rdev && text(sys + "/dev").equals(id), "Device identity changed");
        check(!block.contains("rpmb") && new File(sys + "/partition").isFile() && new File(sys).getCanonicalPath().startsWith("/sys/devices/") && !new File(sys).getCanonicalPath().contains("/virtual/"), "Not a physical partition");
        check(text(sys + "/ro").equals("0") && !new File(sys + "/dm").exists(), "Read-only or mapped target");
        check(new File("/sys/dev/block/" + id).getCanonicalPath().equals(new File(sys).getCanonicalPath()), "Sysfs identity binding failed");
        File parent = new File(sys).getCanonicalFile().getParentFile();
        for (File graph : new File[]{new File(sys, "holders"), new File(parent, "holders"), new File(parent, "slaves")}) {
            String[] entries = graph.list(); check(entries != null && entries.length == 0, "Mapped/held target or unknown topology");
        }
        File slaves = new File(sys, "slaves");
        if (slaves.exists()) { String[] entries = slaves.list(); check(entries != null && entries.length == 0, "Partition has slaves"); }
        long length = r.getLong("bytes");
        check(Math.multiplyExact(Long.parseLong(text(sys + "/size")), 512L) == length && Os.lseek(target.fd, 0, OsConstants.SEEK_END) == length, "Target capacity changed");
        target.rewind();
        StructStat input = Os.fstat(source.fd);
        check(OsConstants.S_ISREG(input.st_mode) && input.st_size == length, "Source must be full equal-length regular raw image");
        // Inspect every readable mount namespace. Any persistent inaccessible process fails closed.
        File[] processes = new File("/proc").listFiles(); check(processes != null, "Cannot inspect mount namespaces");
        boolean sawInit = false;
        for (File process : processes) {
            if (!process.getName().matches("[0-9]+")) continue;
            String mounts;
            try { mounts = text(process + "/mountinfo"); }
            catch (IOException e) { if (!process.exists()) continue; throw new IOException("Cannot inspect mount namespace " + process.getName(), e); }
            if (process.getName().equals("1")) { check(!mounts.isEmpty(), "Empty init mounts"); sawInit = true; }
            if (mounts.isEmpty()) continue; // kernel threads have no userspace mount namespace
            for (String line : mounts.split("\n")) {
                String[] fields = line.split(" "); check(fields.length > 6, "Malformed mount information");
                check(!fields[2].equals(id), "Target mounted in a process namespace");
            }
        }
        check(sawInit, "Missing init mount namespace");
        String swap = text("/proc/swaps");
        check(swap.startsWith("Filename"), "Cannot inspect swap state");
        for (String line : swap.split("\n")) {
            String path = line.split("\\s+")[0];
            if (path.startsWith("/")) check(!new File(path).getCanonicalPath().equals(device), "Target used as swap");
        }
        check(property("ro.boot.flash.locked").equals("0") && property("ro.boot.vbmeta.device_state").equals("unlocked"), "Bootloader unlock not confirmed");
        check(!property("ro.boot.dynamic_partitions").equals("true") && !property("ro.boot.dynamic_partitions_retrofit").equals("true"), "Dynamic device refused");
        String virtual = property("ro.virtual_ab.enabled");
        check(virtual.equals("false") || (virtual.isEmpty() && android.os.Build.VERSION.SDK_INT < 29), "Virtual A/B not excluded");
        String slot = property("ro.boot.slot_suffix");
        String name = r.getString("name"), base = name.replaceFirst("_[ab]$", "");
        check(name.equals(new File(alias).getName()) && name.equals(permit.getString("partition")), "Partition name/alias/permit mismatch");
        String targetSlot = name.endsWith("_a") ? "_a" : name.endsWith("_b") ? "_b" : "";
        check((slot.isEmpty() && targetSlot.isEmpty()) || ((slot.equals("_a") || slot.equals("_b")) && !targetSlot.isEmpty() && !slot.equals(targetSlot)), "Current/unknown slot refused");
        check(Arrays.asList("boot", "init_boot", "vendor_boot", "recovery", "system", "vendor", "product", "odm", "system_ext", "vendor_dlkm", "odm_dlkm", "system_dlkm").contains(base), "Unsupported partition name");
    }
    public static void main(String[] args) {
        try {
            check(Os.getuid() == 0, "Not root");
            if (args.length == 2 && args[0].equals("--probe")) {
                File sourceFile = File.createTempFile("writer-source-", ".tmp", new File(args[1]));
                File targetFile = File.createTempFile("writer-target-", ".tmp", new File(args[1]));
                try {
                    byte[] bytes = new byte[4096]; Arrays.fill(bytes, (byte) 0x5a);
                    try (FileOutputStream out = new FileOutputStream(sourceFile)) { out.write(bytes); out.getFD().sync(); }
                    byte[] original = new byte[4096]; Arrays.fill(original, (byte) 0x33);
                    try (FileOutputStream out = new FileOutputStream(targetFile)) { out.write(original); out.getFD().sync(); }
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                    StringBuilder hash = new StringBuilder();
                    for (byte b : digest.digest(bytes)) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                    StringBuilder originalHash = new StringBuilder();
                    for (byte b : digest.digest(original)) originalHash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                    try (Endpoint source = new Endpoint(sourceFile.getPath(), OsConstants.O_RDONLY);
                         Endpoint target = new Endpoint(targetFile.getPath(), OsConstants.O_RDWR | OsConstants.O_EXCL)) {
                        VerifiedCopy.execute(source, target, bytes.length, hash.toString(), originalHash.toString(), () -> {}, (phase, count) -> {});
                    }
                    System.out.println("YU_WRITER_READY");
                } finally { sourceFile.delete(); targetFile.delete(); }
                return;
            }
            check(args.length == 4, "Invalid root writer invocation");
            String request = args[0], marker = args[1], token = args[2], status = args[3];
            check(token.matches("[a-f0-9-]{36}"), "Invalid token");
            // Stable shared inode; never unlink it. A surviving helper excludes another helper.
            String lockDirectory = lockDirectory();
            FileDescriptor lockFd = open(lockDirectory + "/writer.lock", OsConstants.O_CREAT | OsConstants.O_RDWR, 0600);
            StructStat lockStat = Os.fstat(lockFd);
            check(OsConstants.S_ISREG(lockStat.st_mode) && lockStat.st_uid == 0 && lockStat.st_nlink == 1, "Unsafe writer lock");
            try (FileOutputStream lockStream = new FileOutputStream(lockFd); FileLock lock = lockStream.getChannel().tryLock()) {
                check(lock != null, "Another root writer is active");
                JSONObject permit = new JSONObject(text(marker));
                check(permit.getString("token").equals(token) && permit.getString("bootId").equals(text("/proc/sys/kernel/random/boot_id")), "Stale launch permit");
                // One-use capability, created under the stable lock. Never reused even after failure.
                FileDescriptor used = open(lockDirectory + "/used-" + token, OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_WRONLY, 0600);
                try { Os.fsync(used); } finally { Os.close(used); }
                JSONObject r = new JSONObject(text(request));
                String verifiedHash;
                try (Endpoint source = new Endpoint(r.getString("image"), OsConstants.O_RDONLY);
                     Endpoint target = new Endpoint(r.getString("device"), OsConstants.O_RDWR | OsConstants.O_EXCL)) {
                    String hash = VerifiedCopy.execute(source, target, r.getLong("bytes"), r.getString("imageHash"), r.getString("backupHash"), () -> {
                        try { guard(r, source, target, marker, token); } catch (Exception e) { throw new IOException(e); }
                    }, (phase, bytes) -> {
                        try (FileOutputStream out = new FileOutputStream(status, true)) {
                            out.write((phase + " " + bytes + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII)); out.getFD().sync();
                        }
                    });
                    verifiedHash = hash;
                } // Both descriptors closed successfully before acknowledging the operation.
                System.out.println("YU_VERIFIED " + token + " " + r.getString("identity") + " " + r.getLong("bytes") + " " + verifiedHash);
            }
        } catch (Throwable failure) {
            System.err.println("Root writer failed; target may be partial: " + failure);
            System.exit(1);
        }
    }
}
