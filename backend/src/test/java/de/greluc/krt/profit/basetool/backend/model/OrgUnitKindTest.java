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

package de.greluc.krt.profit.basetool.backend.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins every {@link OrgUnitKind} predicate over all constants (ADR-0238). */
class OrgUnitKindTest {

  @Test
  void onlyStaffelnAndSpezialkommandosAreTenantUnits() {
    Map<OrgUnitKind, Boolean> expected =
        Map.of(
            OrgUnitKind.SQUADRON, true,
            OrgUnitKind.SPECIAL_COMMAND, true,
            OrgUnitKind.BEREICH, false,
            OrgUnitKind.ORGANISATIONSLEITUNG, false);

    assertThat(expected).containsOnlyKeys(Arrays.asList(OrgUnitKind.values()));
    for (OrgUnitKind kind : OrgUnitKind.values()) {
      assertThat(kind.isTenantUnit()).as(kind.name()).isEqualTo(expected.get(kind));
    }
  }
}
