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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Pins the edit-form prefill of {@link JobOrderForm#forEdit(JobOrderDto)}. */
class JobOrderFormTest {

  /**
   * Builds an order carrying only what the prefill reads.
   *
   * @param materials the order's material lines, or {@code null}
   * @return the order
   */
  private static @NotNull JobOrderDto order(@Nullable List<JobOrderMaterialDto> materials) {
    return new JobOrderDto(
        UUID.randomUUID(),
        7,
        null,
        null,
        "Tester",
        "Bitte bis Freitag",
        3,
        "OPEN",
        "MATERIAL",
        false,
        materials,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Instant.now(),
        5L,
        null,
        false);
  }

  /**
   * Builds a material line.
   *
   * @param materialId the material's id
   * @param minQuality the minimum quality
   * @param amount the ordered amount
   * @return the line
   */
  private static @NotNull JobOrderMaterialDto line(
      @NotNull UUID materialId, int minQuality, double amount) {
    MaterialDto material =
        new MaterialDto(
            materialId,
            "Agricium",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            1L);
    return new JobOrderMaterialDto(
        UUID.randomUUID(), material, minQuality, null, amount, 0.0, List.of(), amount, 1L);
  }

  /** Comment, version and every material line are copied in order. */
  @Test
  void copiesCommentVersionAndMaterialLines() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    JobOrderForm form =
        JobOrderForm.forEdit(order(List.of(line(first, 800, 12.5), line(second, 0, 3))));

    assertThat(form.getComment()).isEqualTo("Bitte bis Freitag");
    assertThat(form.getVersion()).isEqualTo(5L);
    assertThat(form.getMaterials())
        .extracting(
            JobOrderForm.JobOrderMaterialForm::getMaterialId,
            JobOrderForm.JobOrderMaterialForm::getMinQuality,
            JobOrderForm.JobOrderMaterialForm::getAmount)
        .containsExactly(tuple(first, 800, 12.5), tuple(second, 0, 3.0));
  }

  /** An order without material lines keeps the single empty line the form starts with. */
  @Test
  void keepsOneEmptyLineWhenTheOrderHasNone() {
    for (List<JobOrderMaterialDto> none : Arrays.asList(null, List.<JobOrderMaterialDto>of())) {
      JobOrderForm form = JobOrderForm.forEdit(order(none));

      assertThat(form.getMaterials()).hasSize(1);
      assertThat(form.getMaterials().getFirst().getMaterialId()).isNull();
      assertThat(form.getMaterials().getFirst().getMinQuality()).isEqualTo(650);
    }
  }
}
