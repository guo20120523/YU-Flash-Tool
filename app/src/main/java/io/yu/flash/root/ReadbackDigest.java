package io.yu.flash.root;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import io.yu.flash.core.ExactRangeDigest;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;

/** Installed-APK app_process entry point. Read-only: no dd, output file, or block write. */
public final class ReadbackDigest {
    public static void main(String[] args) {
        try {
            if (args.length != 4 || !args[0].matches("/dev/block/[A-Za-z0-9_./:-]+") ||
                !args[1].matches("[0-9]+:[0-9]+") || !args[3].matches("[a-f0-9-]{36}")) {
                throw new IOException("Invalid readback request");
            }
            long length = Long.parseLong(args[2]);
            if (length < 0) throw new IOException("Negative readback length");
            int closeOnExec = android.os.Build.VERSION.SDK_INT >= 27 ? OsConstants.O_CLOEXEC : RootWriter.API26_O_CLOEXEC;
            FileDescriptor fd = Os.open(args[0], OsConstants.O_RDONLY | closeOnExec | OsConstants.O_NOFOLLOW, 0);
            ExactRangeDigest.Result result;
            // Android FileInputStream(FileDescriptor) borrows the fd; close Os.open explicitly.
            // No receipt is emitted unless both hashing and descriptor close succeed.
            try (FileInputStream input = new FileInputStream(fd)) {
                StructStat stat = Os.fstat(fd);
                if (!OsConstants.S_ISBLK(stat.st_mode) || !RootWriter.identity(stat.st_rdev).equals(args[1])) {
                    throw new IOException("Readback block identity mismatch");
                }
                result = ExactRangeDigest.prefix(input, length);
            } finally {
                Os.close(fd);
            }
            System.out.println("YU_READBACK " + args[3] + " " + args[1] + " " + result.bytes + " " + result.sha256);
        } catch (Throwable e) {
            System.err.println("Readback failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
