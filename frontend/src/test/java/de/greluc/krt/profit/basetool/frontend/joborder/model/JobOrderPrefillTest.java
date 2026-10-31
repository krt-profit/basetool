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

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins how the create-order query parameters prefill the form (REQ-INV-068). */
class JobOrderPrefillTest {

  private static final UUID MATERIAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID UNIT = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

  @Test
  void theMaterialLineAndTheRequestingUnitAreFilled() {
    JobOrderForm form = new JobOrderForm();

    new JobOrderPrefill(MATERIAL, 650, 4.5, UNIT).applyTo(form);

    JobOrderForm.JobOrderMaterialForm line = form.getMaterials().getFirst();
    assertThat(line.getMaterialId()).isEqualTo(MATERIAL);
    assertThat(line.getMinQuality()).isEqualTo(650);
    assertThat(line.getAmount()).isEqualTo(4.5);
    assertThat(form.getRequestingOrgUnitId()).isEqualTo(UNIT);
  }

  @Test
  void aMissingFloorKeepsTheLineDefault() {
    JobOrderForm form = new JobOrderForm();

    new JobOrderPrefill(MATERIAL, null, 2.0, null).applyTo(form);

    assertThat(form.getMaterials().getFirst().getMinQuality()).isEqualTo(650);
    assertThat(form.getRequestingOrgUnitId()).isNull();
  }

  @Test
  void withoutAMaterialTheFormStaysEmpty() {
    JobOrderForm form = new JobOrderForm();

    new JobOrderPrefill(null, 650, 4.5, null).applyTo(form);

    assertThat(form.getMaterials().getFirst().getMaterialId()).isNull();
    assertThat(form.getMaterials().getFirst().getAmount()).isNull();
  }
}
