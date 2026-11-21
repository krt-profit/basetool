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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.support.QualityTierFixtures;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins how an order edit reconciles material lines in place (REQ-ORDERS-077, ADR-0400). */
class JobOrderMaterialLinesTest {

  private final QualityTier none = QualityTierFixtures.none();
  private final QualityTier good = QualityTierFixtures.good();
  private final Material titanium = material();
  private final Material agricium = material();

  private JobOrderMaterialLines lines;
  private JobOrder order;

  @BeforeEach
  void setUp() {
    MaterialRepository materials = mock(MaterialRepository.class);
    QualityTierService tiers = mock(QualityTierService.class);
    for (Material material : List.of(titanium, agricium)) {
      lenient().when(materials.findById(material.getId())).thenReturn(Optional.of(material));
    }
    lenient().when(tiers.resolveForRequirement(isNull(), isNull(), any())).thenReturn(none);
    lenient().when(tiers.resolveForRequirement(isNull(), eq(650), any())).thenReturn(good);
    lines = new JobOrderMaterialLines(materials, tiers);
    order = new JobOrder();
    order.setId(UUID.randomUUID());
  }

  private static @NotNull Material material() {
    Material material = new Material();
    material.setId(UUID.randomUUID());
    return material;
  }

  private @NotNull JobOrderMaterial existing(
      @NotNull Material material, @NotNull QualityTier tier, double requested, double credited) {
    JobOrderMaterial line =
        JobOrderMaterial.builder()
            .id(UUID.randomUUID())
            .material(material)
            .qualityTier(tier)
            .amount(requested)
            .build();
    line.credit(credited);
    order.addMaterial(line);
    return line;
  }

  private static @NotNull CreateJobOrderMaterialDto payload(
      @NotNull Material material, Integer minQuality, double amount) {
    return new CreateJobOrderMaterialDto(material.getId(), minQuality, amount);
  }

  /** A credited line takes the edited requested amount and keeps its identity and credit. */
  @Test
  void aMatchedLineKeepsItsIdentityAndCredit() {
    JobOrderMaterial line = existing(titanium, good, 10.0, 4.0);

    lines.reconcile(order, List.of(payload(titanium, 650, 12.0)));

    assertThat(order.getMaterials()).containsExactly(line);
    assertThat(line.getAmount()).isEqualTo(12.0);
    assertThat(line.getCreditedAmount()).isEqualTo(4.0);
    assertThat(line.remainingAmount()).isEqualTo(8.0);
  }

  /** Setting a credited line to exactly its credit ends it without losing the delivery. */
  @Test
  void aLineMayBeSetToItsHandedOverAmount() {
    JobOrderMaterial line = existing(titanium, good, 10.0, 4.0);

    lines.reconcile(order, List.of(payload(titanium, 650, 4.0)));

    assertThat(line.remainingAmount()).isZero();
  }

  /** A line may not be requested below what was already handed over against it. */
  @Test
  void loweringALineBelowItsCreditIsRefused() {
    existing(titanium, good, 10.0, 4.0);

    assertThatThrownBy(() -> lines.reconcile(order, List.of(payload(titanium, 650, 3.999))))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(JobOrderMaterialLines.BELOW_HANDED_OVER);
  }

  /** A credited line cannot be dropped from the order. */
  @Test
  void removingACreditedLineIsRefused() {
    existing(titanium, good, 10.0, 4.0);

    assertThatThrownBy(() -> lines.reconcile(order, List.of(payload(agricium, null, 5.0))))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(JobOrderMaterialLines.HANDED_OVER_LINE_REQUIRED);
  }

  /** Moving a credited line to another tier is a removal and therefore refused. */
  @Test
  void changingTheTierOfACreditedLineIsRefused() {
    existing(titanium, good, 10.0, 4.0);

    assertThatThrownBy(() -> lines.reconcile(order, List.of(payload(titanium, null, 10.0))))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(JobOrderMaterialLines.HANDED_OVER_LINE_REQUIRED);
  }

  /** A credited line stays while a new line of another tier takes the rest of the need. */
  @Test
  void aNewTierBesideACreditedLineIsAdded() {
    JobOrderMaterial credited = existing(titanium, good, 10.0, 4.0);

    lines.reconcile(order, List.of(payload(titanium, 650, 4.0), payload(titanium, null, 6.0)));

    assertThat(order.getMaterials())
        .extracting(JobOrderMaterial::getQualityTier, JobOrderMaterial::getAmount)
        .containsExactlyInAnyOrder(tuple(good, 4.0), tuple(none, 6.0));
    assertThat(order.getMaterials()).contains(credited);
  }

  /** A line without handovers can still be removed or re-tiered freely. */
  @Test
  void anUncreditedLineIsRemovedOrReplaced() {
    existing(titanium, good, 10.0, 0.0);
    existing(agricium, none, 3.0, 0.0);

    lines.reconcile(order, List.of(payload(titanium, null, 7.0)));

    assertThat(order.getMaterials())
        .extracting(
            JobOrderMaterial::getMaterial,
            JobOrderMaterial::getQualityTier,
            JobOrderMaterial::getAmount)
        .containsExactly(tuple(titanium, none, 7.0));
  }

  /** Payload rows of one material and tier are merged before they are matched. */
  @Test
  void payloadRowsOfOnePairAreSummed() {
    JobOrderMaterial line = existing(titanium, good, 10.0, 4.0);

    lines.reconcile(order, List.of(payload(titanium, 650, 3.0), payload(titanium, 650, 2.0)));

    assertThat(order.getMaterials()).containsExactly(line);
    assertThat(line.getAmount()).isEqualTo(5.0);
  }
}
