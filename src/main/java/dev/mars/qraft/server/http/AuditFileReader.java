/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.qraft.server.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Reads an audit file's lines backwards, newest first (UI-6 Tenants, Namespaces and Audit task list, decision 5), a
 * chunk at a time, so a read near the end costs what it reads and not the whole file. A read starts at the end, or at
 * the offset a previous read stopped at, and stops once the consumer refuses a line, or once {@code scanLimit} bytes have
 * been read. A line cut by that limit is left for the next read, unless the read has met no whole line, which is then
 * read whole, so every read gets on. A file that {@code AuditFileSink} is appending to is read up to the size it had
 * when the read began.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-17
 * @version 1.0
 */
final class AuditFileReader {
    private static final int CHUNK = 64 * 1024;

    /**
     * What a read covered: the file's size, the bytes it read, whether it reached the file's start, and the offset the
     * next read goes on from, or -1 when it reached the start.
     */
    record Scan(long size, long scanned, boolean complete, long next) { }

    private AuditFileReader() { }

    /**
     * Gives {@code consumer} each whole line of {@code file} before offset {@code from}, or before its end when
     * {@code from} is past it, newest first, until it answers false, the file's start, or {@code scanLimit} bytes. A file
     * that does not exist is an empty one.
     */
    static Scan readBackwards(Path file, long from, long scanLimit, Predicate<String> consumer) throws IOException {
        if (!Files.exists(file)) return new Scan(0, 0, true, -1);
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long size = in.length();
            long start = Math.min(from, size);
            long floor = Math.max(0, start - scanLimit);
            // The bytes of the line being read, last byte first, and the offset just after it.
            ByteArrayOutputStream reversed = new ByteArrayOutputStream();
            long lineEnd = start;
            long position = start;
            byte[] chunk = new byte[CHUNK];
            while (position > 0) {
                int length = (int) Math.min(CHUNK, position);
                position -= length;
                in.seek(position);
                in.readFully(chunk, 0, length);
                for (int at = length - 1; at >= 0; at--) {
                    long offset = position + at;
                    // Below the floor, the line being read is left for the next read, unless no whole line has been
                    // met, so that every read gets on.
                    if (offset < floor && lineEnd != start) return new Scan(size, start - (offset + 1), false, lineEnd);
                    if (chunk[at] != '\n') {
                        reversed.write(chunk[at]);
                        continue;
                    }
                    if (!emit(reversed, consumer)) return new Scan(size, start - offset, false, lineEnd);
                    lineEnd = offset;
                }
            }
            // The file's first line has no newline before it.
            if (!emit(reversed, consumer)) return new Scan(size, start, false, lineEnd);
            return new Scan(size, start, true, -1);
        }
    }

    /** Gives the line gathered, if any, to {@code consumer}: whether the read goes on. */
    private static boolean emit(ByteArrayOutputStream reversed, Predicate<String> consumer) {
        byte[] bytes = reversed.toByteArray();
        reversed.reset();
        if (bytes.length == 0) return true;
        for (int left = 0, right = bytes.length - 1; left < right; left++, right--) {
            byte swap = bytes[left];
            bytes[left] = bytes[right];
            bytes[right] = swap;
        }
        String line = new String(bytes, StandardCharsets.UTF_8).strip();
        return line.isEmpty() || consumer.test(line);
    }
}
