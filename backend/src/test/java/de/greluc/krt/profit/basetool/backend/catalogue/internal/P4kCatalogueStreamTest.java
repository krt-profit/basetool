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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogueReader;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCompression;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kContentTooLargeException;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Streaming the catalogue out of its three forms (REQ-P4K-002). */
class P4kCatalogueStreamTest {

  /** The decompressed size of the generated LIVE-sized catalogue. */
  private static final long LIVE_SIZE_BYTES = 256L * 1024 * 1024;

  private final P4kCatalogueReader reader = new P4kCatalogueReader(JsonMapper.builder().build());

  private P4kCatalogue read(byte @NotNull [] upload, long limit) throws IOException {
    try (InputStream in = P4kCompression.openDecompressed(upload, limit)) {
      return reader.read(in);
    }
  }

  @Test
  void theSameCatalogue_readsAlikeAsPlainGzipAndZstd() throws IOException {
    byte[] plain = P4kTestCatalogues.fixtureBytes();

    P4kCatalogue fromPlain = read(plain, Long.MAX_VALUE);
    P4kCatalogue fromGzip = read(P4kTestCatalogues.gzip(plain), Long.MAX_VALUE);
    P4kCatalogue fromZstd = read(P4kTestCatalogues.zstd(plain), Long.MAX_VALUE);

    assertThat(fromPlain.items()).hasSize(9);
    assertThat(fromPlain.ships()).hasSize(4);
    assertThat(fromGzip).isEqualTo(fromPlain);
    assertThat(fromZstd).isEqualTo(fromPlain);
  }

  @Test
  void theFormIsRecognisedByMagicBytes_notByName() {
    byte[] plain = P4kTestCatalogues.fixtureBytes();

    assertThat(P4kCompression.detect(plain)).isEqualTo(P4kCompression.PLAIN);
    assertThat(P4kCompression.detect(P4kTestCatalogues.gzip(plain))).isEqualTo(P4kCompression.GZIP);
    assertThat(P4kCompression.detect(P4kTestCatalogues.zstd(plain))).isEqualTo(P4kCompression.ZSTD);
  }

  @Test
  void contentPastTheLimit_stopsTheRead() {
    byte[] gzip = P4kTestCatalogues.gzip(P4kTestCatalogues.fixtureBytes());

    assertThatThrownBy(() -> read(gzip, 4_096)).isInstanceOf(P4kContentTooLargeException.class);
  }

  @Test
  void liveSizedZstdCatalogue_readsWithAHeapBoundWellBelowItsSize() throws IOException {
    byte[] zstd = liveSizedZstd();
    System.gc();
    long before = liveHeap();
    try (LiveHeapSampler sampler =
        new LiveHeapSampler(P4kCompression.openDecompressed(zstd, 1_000_000_000L))) {
      P4kCatalogue catalogue = reader.read(sampler);

      assertThat(catalogue.items()).hasSize(9);
      assertThat(sampler.bytesRead).isGreaterThan(LIVE_SIZE_BYTES - 1024);
      assertThat(sampler.peakLiveHeap - before).isLessThan(LIVE_SIZE_BYTES / 8);
    }
  }

  /**
   * Returns the heap in use, which right after a collection is the live set.
   *
   * @return the used heap in bytes
   */
  private static long liveHeap() {
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  /** Collects garbage every {@value #SAMPLE_BYTES} bytes read and keeps the largest live set. */
  private static final class LiveHeapSampler extends FilterInputStream {

    /** The bytes between two samples. */
    private static final long SAMPLE_BYTES = 32L * 1024 * 1024;

    private long bytesRead;
    private long nextSample = SAMPLE_BYTES;
    private long peakLiveHeap;

    private LiveHeapSampler(@NotNull InputStream in) {
      super(in);
    }

    @Override
    public int read(byte @NotNull [] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n > 0) {
        bytesRead += n;
        if (bytesRead >= nextSample) {
          nextSample += SAMPLE_BYTES;
          System.gc();
          peakLiveHeap = Math.max(peakLiveHeap, liveHeap());
        }
      }
      return n;
    }
  }

  /**
   * Builds a zstd catalogue of {@value #LIVE_SIZE_BYTES} bytes: the fixture with a {@code loadouts}
   * kind padded to LIVE size, streamed into the encoder so the plain form is never held.
   *
   * @return the compressed catalogue
   * @throws IOException never; the sink is in memory
   */
  private static byte @NotNull [] liveSizedZstd() throws IOException {
    ObjectNode root = P4kTestCatalogues.tree();
    root.putArray("loadouts");
    String json = new String(P4kTestCatalogues.bytes(root), StandardCharsets.UTF_8);
    String marker = "\"loadouts\":[]";
    int at = json.indexOf(marker);
    byte[] head = (json.substring(0, at) + "\"loadouts\":[").getBytes(StandardCharsets.UTF_8);
    byte[] tail = ("]" + json.substring(at + marker.length())).getBytes(StandardCharsets.UTF_8);
    byte[] element =
        "{\"entityGuid\":\"0079c5d5-1678-4f8c-85ba-18ca8f642af6\",\"ports\":[{\"name\":\"hardpoint\"}]},"
            .getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    try (OutputStream out =
        P4kTestCatalogues.longWindowZstd(sink, P4kTestCatalogues.LIVE_WINDOW_LOG)) {
      out.write(head);
      long written = head.length + tail.length;
      while (written + element.length < LIVE_SIZE_BYTES) {
        out.write(element);
        written += element.length;
      }
      out.write("{}".getBytes(StandardCharsets.UTF_8));
      out.write(tail);
    }
    return sink.toByteArray();
  }
}
