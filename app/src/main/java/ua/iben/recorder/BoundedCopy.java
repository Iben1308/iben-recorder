package ua.iben.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class BoundedCopy {
    static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        if (input == null || output == null || limit < 0) throw new IOException("Invalid copy stream or limit");
        byte[] buffer = new byte[65536]; long total = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Copy interrupted");
            int n = input.read(buffer);
            if (n < 0) break;
            if (n == 0) continue;
            if (n > limit - total) throw new IOException("File exceeds the available copy budget");
            output.write(buffer, 0, n); total += n;
        }
        output.flush(); return total;
    }
}
