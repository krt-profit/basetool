/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jetbrains.annotations.NotNull;

/**
 * The three forms an upload may take (REQ-P4K-002), recognised by magic bytes, never by name: plain
 * JSON, gzip ({@code 1f 8b}) and zstd ({@code 28 b5 2f fd}, decoded by zstd-jni with a window of at
 * most {@value #ZSTD_WINDOW_LOG_MAX} bits).
 */
public enum P4kCompression {

  /** Uncompressed JSON. */
  PLAIN,

  /** A gzip member. */
  GZIP,

  /** A zstd frame. */
  ZSTD;

  /**
   * The largest zstd window the decoder accepts, as a power of two: 2^27 = 128 MiB, the window p4kx
   * writes with long-distance matching ({@code zstd -19 --long=27}).
   */
  static final int ZSTD_WINDOW_LOG_MAX = 27;

  /** The size of the buffer the gzip decoder reads through. */
  private static final int GZIP_BUFFER_BYTES = 64 * 1024;

  /** The gzip magic bytes. */
  private static final int[] GZIP_MAGIC = {0x1f, 0x8b};

  /** The zstd frame magic bytes, {@code 0xFD2FB528} in little-endian order. */
  private static final int[] ZSTD_MAGIC = {0x28, 0xb5, 0x2f, 0xfd};

  /**
   * Recognises the form of an upload by its first bytes.
   *
   * @param bytes the upload
   * @return {@link #GZIP} or {@link #ZSTD} on their magic bytes, else {@link #PLAIN}
   */
  @NotNull
  public static P4kCompression detect(byte @NotNull [] bytes) {
    if (startsWith(bytes, ZSTD_MAGIC)) {
      return ZSTD;
    }
    if (startsWith(bytes, GZIP_MAGIC)) {
      return GZIP;
    }
    return PLAIN;
  }

  /**
   * Opens the decompressed content of an upload, capped at a size limit.
   *
   * @param bytes the upload, in any of the three forms
   * @param limitBytes the most decompressed bytes the stream yields before it throws
   * @return the decompressed stream; reading past the limit throws {@link
   *     P4kContentTooLargeException}, a decoder failure {@link P4kCatalogueFormatException}
   */
  @NotNull
  public static InputStream openDecompressed(byte @NotNull [] bytes, long limitBytes) {
    InputStream raw = new ByteArrayInputStream(bytes);
    try {
      InputStream decoded =
          switch (detect(bytes)) {
            case PLAIN -> raw;
            case GZIP -> new GZIPInputStream(raw, GZIP_BUFFER_BYTES);
            case ZSTD -> new ZstdInputStream(raw).setLongMax(ZSTD_WINDOW_LOG_MAX);
          };
      return new GuardedInputStream(decoded, limitBytes);
    } catch (IOException e) {
      throw new P4kCatalogueFormatException("The compressed upload cannot be read.", e);
    }
  }

  /**
   * Returns the form an upload is stored in: a compressed upload as it came, a plain one gzipped.
   *
   * @param bytes the upload
   * @return the bytes to store, always compressed
   */
  public static byte @NotNull [] toStoredPayload(byte @NotNull [] bytes) {
    if (detect(bytes) != PLAIN) {
      return bytes;
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 8 + 64);
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Compressing an in-memory upload failed.", e);
    }
    return out.toByteArray();
  }

  /**
   * Whether {@code bytes} starts with {@code magic}.
   *
   * @param bytes the upload
   * @param magic the magic bytes as unsigned values
   * @return {@code true} on a match
   */
  private static boolean startsWith(byte @NotNull [] bytes, int @NotNull [] magic) {
    if (bytes.length < magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if ((bytes[i] & 0xff) != magic[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Counts the decompressed bytes against the limit and turns a decoder failure into a {@link
   * P4kCatalogueFormatException}.
   */
  private static final class GuardedInputStream extends FilterInputStream {

    /** The most bytes this stream yields. */
    private final long limitBytes;

    /** The bytes yielded so far. */
    private long count;

    /**
     * Wraps a decoder.
     *
     * @param in the decoder
     * @param limitBytes the most bytes to yield
     */
    private GuardedInputStream(@NotNull InputStream in, long limitBytes) {
      super(in);
      this.limitBytes = limitBytes;
    }

    /**
     * Reads one byte.
     *
     * @return the byte, or {@code -1} at the end
     * @throws IOException never; decoder failures are rethrown unchecked
     */
    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xff;
    }

    /**
     * Reads into a buffer, counting the bytes against the limit.
     *
     * @param b the buffer
     * @param off the offset
     * @param len the most bytes to read
     * @return the bytes read, or {@code -1} at the end
     * @throws IOException never; decoder failures are rethrown unchecked
     */
    @Override
    public int read(byte @NotNull [] b, int off, int len) throws IOException {
      int n;
      try {
        n = super.read(b, off, len);
      } catch (IOException e) {
        throw new P4kCatalogueFormatException("The compressed upload cannot be read.", e);
      }
      if (n > 0) {
        count += n;
        if (count > limitBytes) {
          throw new P4kContentTooLargeException(limitBytes);
        }
      }
      return n;
    }
  }
}
