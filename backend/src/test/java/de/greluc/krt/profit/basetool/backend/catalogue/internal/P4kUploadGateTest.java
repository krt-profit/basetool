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

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kRejectionReason;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kUploadRejectedException;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogueReader;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCompression;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The synchronous upload gate (REQ-P4K-001, REQ-P4K-002). */
class P4kUploadGateTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private P4kUploadGate gate(long decompressedLimit, long heldLimit) {
    return new P4kUploadGate(
        new P4kCatalogueReader(JsonMapper.builder().build()),
        new P4kImportProperties(decompressedLimit, heldLimit),
        new P4kRejections(meters));
  }

  private P4kUploadGate gate() {
    return gate(1_000_000_000L, 128_000_000L);
  }

  private double rejected(@NotNull P4kRejectionReason reason) {
    var counter =
        meters
            .find(CatalogueMetricNames.P4K_IMPORT_REJECTED)
            .tag("reason", reason.tagValue())
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private void assertRefused(byte @NotNull [] upload, @NotNull P4kRejectionReason reason) {
    assertThatThrownBy(() -> gate().check(upload))
        .isInstanceOfSatisfying(
            P4kUploadRejectedException.class, e -> assertThat(e.getReason()).isEqualTo(reason));
    assertThat(rejected(reason)).isEqualTo(1.0);
  }

  @Test
  void liveCatalogueOfVersion2_isAcceptedInEveryForm_andStoredCompressed() {
    byte[] plain = P4kTestCatalogues.fixtureBytes();
    for (byte[] upload :
        new byte[][] {plain, P4kTestCatalogues.gzip(plain), P4kTestCatalogues.zstd(plain)}) {
      P4kAcceptedUpload accepted = gate().check(upload);
      assertThat(accepted.uploadedBytes()).isEqualTo(upload.length);
      assertThat(P4kCompression.detect(accepted.storedPayload()))
          .isNotEqualTo(P4kCompression.PLAIN);
    }
    assertThat(meters.find(CatalogueMetricNames.P4K_IMPORT_REJECTED).counters()).isEmpty();
  }

  @Test
  void zstdWithTheLiveWindowOf128MiB_isAccepted() {
    byte[] zstd = P4kTestCatalogues.zstd(P4kTestCatalogues.fixtureBytes());

    assertThat(windowLog(zstd)).isEqualTo(27);
    assertThat(gate().check(zstd).storedPayload()).isEqualTo(zstd);
  }

  @Test
  void zstdWithAWindowAboveTheContractsMaximum_isRefused() {
    byte[] zstd = P4kTestCatalogues.zstd(P4kTestCatalogues.fixtureBytes());
    zstd[5] = (byte) ((28 - 10) << 3);

    assertThat(windowLog(zstd)).isEqualTo(28);
    assertRefused(zstd, P4kRejectionReason.SCHEMA_VERSION);
  }

  /**
   * Reads the window log of a zstd frame that is not single-segment (RFC 8878, 3.1.1.1.2).
   *
   * @param frame the frame
   * @return the window log the header declares
   */
  private static int windowLog(byte @NotNull [] frame) {
    assertThat(frame[4] & 0x20).as("single-segment flag").isZero();
    return ((frame[5] & 0xff) >>> 3) + 10;
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3})
  void otherSchemaVersion_isRefused(int version) {
    ObjectNode root = P4kTestCatalogues.tree();
    root.put("schemaVersion", version);

    assertRefused(P4kTestCatalogues.bytes(root), P4kRejectionReason.SCHEMA_VERSION);
  }

  @Test
  void missingSchemaVersion_asInTheOldFlatFormat_isRefused() {
    byte[] oldFormat =
        "{\"manufacturers\":[],\"items\":[{\"guid\":\"x\",\"name\":\"A\"}]}"
            .getBytes(StandardCharsets.UTF_8);

    assertRefused(oldFormat, P4kRejectionReason.SCHEMA_VERSION);
  }

  @Test
  void unreadableContent_countsAsSchemaVersion() {
    assertRefused("not json".getBytes(StandardCharsets.UTF_8), P4kRejectionReason.SCHEMA_VERSION);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PTU", "EPTU", "TECH-PREVIEW", "HOTFIX"})
  void testChannel_isRefused(String channel) {
    ObjectNode root = P4kTestCatalogues.tree();
    ((ObjectNode) root.get("source")).put("channel", channel);

    assertRefused(P4kTestCatalogues.bytes(root), P4kRejectionReason.CHANNEL);
  }

  @Test
  void liveCatalogueWithoutBuild_isRefused() {
    ObjectNode root = P4kTestCatalogues.tree();
    ((ObjectNode) root.get("source")).putNull("build");

    assertRefused(P4kTestCatalogues.bytes(root), P4kRejectionReason.BUILD_MISSING);
  }

  @Test
  void nonDecimalChangeNumber_countsAsMissingBuild() {
    assertRefused(P4kTestCatalogues.withChangeNumber("12.9"), P4kRejectionReason.BUILD_MISSING);
  }

  @Test
  void uploadAboveTheUploadLimit_isRefusedWithoutReadingIt() {
    byte[] upload = new byte[(int) P4kUploadGate.UPLOAD_LIMIT_BYTES + 1];
    Arrays.fill(upload, (byte) ' ');

    assertRefused(upload, P4kRejectionReason.TOO_LARGE);
  }

  @Test
  void contentAboveTheDecompressedLimit_isRefused_evenWhenItsHeadIsFine() {
    byte[] zstd = P4kTestCatalogues.zstd(P4kTestCatalogues.fixtureBytes());

    assertThatThrownBy(() -> gate(10_000, 128_000_000L).check(zstd))
        .isInstanceOfSatisfying(
            P4kUploadRejectedException.class,
            e -> assertThat(e.getReason()).isEqualTo(P4kRejectionReason.TOO_LARGE));
    assertThat(rejected(P4kRejectionReason.TOO_LARGE)).isEqualTo(1.0);
  }

  @Test
  void heldKindsAboveTheHeldLimit_areRefusedAsTooLarge() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "items", P4kTestCatalogues.SHIELD)
        .put("className", "A".repeat(20_000));
    byte[] zstd = P4kTestCatalogues.zstd(P4kTestCatalogues.bytes(root));

    assertThatThrownBy(() -> gate(1_000_000_000L, 10_000).check(zstd))
        .isInstanceOfSatisfying(
            P4kUploadRejectedException.class,
            e -> assertThat(e.getReason()).isEqualTo(P4kRejectionReason.TOO_LARGE));
    assertThat(rejected(P4kRejectionReason.TOO_LARGE)).isEqualTo(1.0);
  }

  @Test
  void kindsTheImportSkips_doNotCountAgainstTheHeldLimit() {
    byte[] fixture = P4kTestCatalogues.fixtureBytes();
    ObjectNode root = P4kTestCatalogues.tree();
    root.put("unreadTail", "L".repeat(fixture.length * 4));

    P4kAcceptedUpload accepted =
        gate(1_000_000_000L, fixture.length).check(P4kTestCatalogues.bytes(root));

    assertThat(accepted.uploadedBytes()).isGreaterThan(fixture.length * 4);
  }
}
