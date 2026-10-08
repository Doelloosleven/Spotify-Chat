package dev.spotifychat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads UTF-8 text lines with a byte limit. BufferedReader.readLine keeps reading as long as no line break
 * comes, so a server could make it fill memory; here a too long line is skipped up to its line break and
 * never stored.
 */
final class BoundedLineReader {
    private final InputStream in;
    private final byte[] buf;

    /** in should be buffered: it's read a byte at a time */
    BoundedLineReader(InputStream in, int maxBytes) {
        this.in = in;
        this.buf = new byte[maxBytes];
    }

    /** The next line without its \r\n or \n, or null at the end of the stream. */
    String readLine() throws IOException {
        while (true) {
            int len = 0;
            boolean tooLong = false;
            int b;
            while ((b = in.read()) != -1 && b != '\n') {
                if (tooLong) continue;
                if (len == buf.length) {
                    tooLong = true;
                    continue;
                }
                buf[len++] = (byte) b;
            }
            if (tooLong) {
                if (b == -1) return null;
                continue; // skipped; read the next line
            }
            if (b == -1 && len == 0) return null;
            if (len > 0 && buf[len - 1] == '\r') len--;
            return new String(buf, 0, len, StandardCharsets.UTF_8);
        }
    }
}
