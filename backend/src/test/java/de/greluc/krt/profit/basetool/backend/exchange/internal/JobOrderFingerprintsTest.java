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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.AuecProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.ItemLine;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.ItemProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.MaterialLine;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.MaterialProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.Progress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.Versions;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The exchange fingerprint of a job order's member-independent read (REQ-OXCH-003, ADR-0406): one
 * state gives one hash, and every carried field moves it.
 */
class JobOrderFingerprintsTest {

  private static final UUID ORDER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID SUPPORT_A = UUID.fromString("00000000-0000-4000-8000-000000000004");
  private static final UUID SUPPORT_B = UUID.fromString("00000000-0000-4000-8000-000000000005");
  private static final UUID ASSIGNEE_A = UUID.fromString("00000000-0000-4000-8000-000000000006");
  private static final UUID ASSIGNEE_B = UUID.fromString("00000000-0000-4000-8000-000000000007");
  private static final UUID MATERIAL = UUID.fromString("00000000-0000-4000-8000-000000000008");
  private static final UUID TIER = UUID.fromString("00000000-0000-4000-8000-000000000009");
  private static final UUID LINE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
  private static final UUID ITEM = UUID.fromString("00000000-0000-4000-8000-00000000000b");
  private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-0000000000ff");
  private static final Instant CREATED = Instant.parse("2026-11-01T10:15:30.123456Z");

  @Test
  void aFingerprintIsTheSha256OfTheRead() {
    assertThat(JobOrderFingerprints.of(material())).hasSize(32);
  }

  @Test
  void oneStateGivesOneFingerprintWhateverTheListOrder() {
    JobOrderExchangeRead first =
        material().toBuilder()
            .supportingUnitIds(List.of(SUPPORT_A, SUPPORT_B))
            .assigneeIds(List.of(ASSIGNEE_A, ASSIGNEE_B))
            .progress(
                new MaterialProgress(
                    List.of(materialLine(10, 4, 6, 2), new MaterialLine(OTHER, TIER, 1, 0, 1, 1))))
            .build();
    JobOrderExchangeRead second =
        material().toBuilder()
            .supportingUnitIds(List.of(SUPPORT_B, SUPPORT_A, SUPPORT_B))
            .assigneeIds(List.of(ASSIGNEE_B, ASSIGNEE_A))
            .progress(
                new MaterialProgress(
                    List.of(new MaterialLine(OTHER, TIER, 1, 0, 1, 1), materialLine(10, 4, 6, 2))))
            .build();

    assertThat(second).isEqualTo(first);
    assertThat(JobOrderFingerprints.of(second)).isEqualTo(JobOrderFingerprints.of(first));
  }

  @Test
  void anAmountWithoutItsTrailingZerosIsTheSameAmount() {
    JobOrderExchangeRead scaled =
        auec().toBuilder()
            .progress(
                new AuecProgress(
                    new BigDecimal("1000.00"),
                    BigDecimal.ZERO,
                    BigDecimal.TEN,
                    BigDecimal.ZERO,
                    false))
            .build();
    JobOrderExchangeRead plain =
        auec().toBuilder()
            .progress(
                new AuecProgress(
                    new BigDecimal("1000"),
                    BigDecimal.ZERO,
                    BigDecimal.TEN,
                    BigDecimal.ZERO,
                    false))
            .build();

    assertThat(JobOrderFingerprints.of(scaled)).isEqualTo(JobOrderFingerprints.of(plain));
  }

  @Test
  void aCommentThatSpellsTheContactIntoItIsADifferentRead() {
    JobOrderExchangeRead split = material().toBuilder().comment("ab").contact("c").build();
    JobOrderExchangeRead joined = material().toBuilder().comment("a").contact("bc").build();

    assertThat(JobOrderFingerprints.of(split)).isNotEqualTo(JobOrderFingerprints.of(joined));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("carriedFieldChanges")
  void everyCarriedFieldMovesTheFingerprint(
      JobOrderExchangeRead base, UnaryOperator<JobOrderExchangeRead> change) {
    JobOrderExchangeRead changed = change.apply(base);

    assertThat(changed).isNotEqualTo(base);
    assertThat(JobOrderFingerprints.of(changed)).isNotEqualTo(JobOrderFingerprints.of(base));
  }

  static Stream<Arguments> carriedFieldChanges() {
    JobOrderExchangeRead material = material();
    JobOrderExchangeRead item = item();
    JobOrderExchangeRead auec = auec();
    return Stream.of(
        change("id", material, r -> r.toBuilder().id(OTHER).build()),
        change("number", material, r -> r.toBuilder().number(42).build()),
        change("kind", material, r -> r.toBuilder().kind(JobOrderType.ITEM).build()),
        change("status", material, r -> r.toBuilder().status(JobOrderStatus.IN_PROGRESS).build()),
        change("priority", material, r -> r.toBuilder().priority(2).build()),
        change("priority emptied", material, r -> r.toBuilder().priority(null).build()),
        change(
            "createdAt", material, r -> r.toBuilder().createdAt(CREATED.plusNanos(1000)).build()),
        change("requestingUnit", material, r -> r.toBuilder().requestingUnitId(OTHER).build()),
        change("responsibleUnit", material, r -> r.toBuilder().responsibleUnitId(OTHER).build()),
        change(
            "supportingUnits",
            material,
            r -> r.toBuilder().supportingUnitIds(List.of(SUPPORT_A)).build()),
        change("comment", material, r -> r.toBuilder().comment("Other comment").build()),
        change("comment emptied", material, r -> r.toBuilder().comment(null).build()),
        change("contact", material, r -> r.toBuilder().contact("Other contact").build()),
        change("contact emptied", material, r -> r.toBuilder().contact(null).build()),
        change(
            "responsibilityInvalid",
            material,
            r -> r.toBuilder().responsibilityInvalid(true).build()),
        change(
            "lifecycle version",
            material,
            r -> r.toBuilder().versions(new Versions(2, null, 0)).build()),
        change("target version", auec, r -> r.toBuilder().versions(new Versions(1, 4L, 0)).build()),
        change(
            "comment version",
            material,
            r -> r.toBuilder().versions(new Versions(1, null, 1)).build()),
        change("assignees", material, r -> r.toBuilder().assigneeIds(List.of(ASSIGNEE_A)).build()),
        change("material requested", material, r -> progress(r, lines(materialLine(11, 4, 6, 2)))),
        change("material handedOver", material, r -> progress(r, lines(materialLine(10, 5, 6, 2)))),
        change("material remaining", material, r -> progress(r, lines(materialLine(10, 4, 5, 2)))),
        change("material open", material, r -> progress(r, lines(materialLine(10, 4, 6, 1.5)))),
        change(
            "material",
            material,
            r -> progress(r, lines(new MaterialLine(OTHER, TIER, 10, 4, 6, 2)))),
        change(
            "quality tier",
            material,
            r -> progress(r, lines(new MaterialLine(MATERIAL, OTHER, 10, 4, 6, 2)))),
        change("item ordered", item, r -> progress(r, items(new ItemLine(LINE, ITEM, 6, 3, 2, 3)))),
        change(
            "item manufactured",
            item,
            r -> progress(r, items(new ItemLine(LINE, ITEM, 5, 4, 2, 3)))),
        change(
            "item delivered", item, r -> progress(r, items(new ItemLine(LINE, ITEM, 5, 3, 3, 3)))),
        change("item open", item, r -> progress(r, items(new ItemLine(LINE, ITEM, 5, 3, 2, 2)))),
        change("item line", item, r -> progress(r, items(new ItemLine(OTHER, ITEM, 5, 3, 2, 3)))),
        change("game item", item, r -> progress(r, items(new ItemLine(LINE, OTHER, 5, 3, 2, 3)))),
        change("aUEC target", auec, r -> progress(r, money(1100, 400, 600, 0, false))),
        change("aUEC netHandedOver", auec, r -> progress(r, money(1000, 500, 600, 0, false))),
        change("aUEC remaining", auec, r -> progress(r, money(1000, 400, 500, 0, false))),
        change("aUEC overpaid", auec, r -> progress(r, money(1000, 400, 600, 1, false))),
        change("aUEC settlementOpen", auec, r -> progress(r, money(1000, 400, 600, 0, true))));
  }

  private static Arguments change(
      String field, JobOrderExchangeRead base, UnaryOperator<JobOrderExchangeRead> change) {
    return Arguments.argumentSet(field, base, change);
  }

  private static JobOrderExchangeRead material() {
    return base(JobOrderType.MATERIAL, lines(materialLine(10, 4, 6, 2))).build();
  }

  private static JobOrderExchangeRead item() {
    return base(JobOrderType.ITEM, items(new ItemLine(LINE, ITEM, 5, 3, 2, 3))).build();
  }

  private static JobOrderExchangeRead auec() {
    return base(JobOrderType.AUEC, money(1000, 400, 600, 0, false))
        .contact(null)
        .versions(new Versions(1, 3L, 0))
        .build();
  }

  private static JobOrderExchangeRead.JobOrderExchangeReadBuilder base(
      JobOrderType kind, Progress progress) {
    return JobOrderExchangeRead.builder()
        .id(ORDER)
        .number(41)
        .kind(kind)
        .status(JobOrderStatus.OPEN)
        .priority(1)
        .createdAt(CREATED)
        .requestingUnitId(UUID.fromString("00000000-0000-4000-8000-000000000002"))
        .responsibleUnitId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
        .supportingUnitIds(List.of(SUPPORT_A, SUPPORT_B))
        .comment("Bitte bis Freitag")
        .contact("Contact")
        .responsibilityInvalid(false)
        .versions(new Versions(1, null, 0))
        .assigneeIds(List.of(ASSIGNEE_A, ASSIGNEE_B))
        .progress(progress);
  }

  private static JobOrderExchangeRead progress(JobOrderExchangeRead read, Progress progress) {
    return read.toBuilder().progress(progress).build();
  }

  private static MaterialLine materialLine(
      double requested, double handedOver, double remaining, double open) {
    return new MaterialLine(MATERIAL, TIER, requested, handedOver, remaining, open);
  }

  private static MaterialProgress lines(MaterialLine line) {
    return new MaterialProgress(List.of(line));
  }

  private static ItemProgress items(ItemLine line) {
    return new ItemProgress(List.of(line));
  }

  private static AuecProgress money(
      long target, long handedOver, long remaining, long overpaid, boolean settlementOpen) {
    return new AuecProgress(
        BigDecimal.valueOf(target),
        BigDecimal.valueOf(handedOver),
        BigDecimal.valueOf(remaining),
        BigDecimal.valueOf(overpaid),
        settlementOpen);
  }
}
