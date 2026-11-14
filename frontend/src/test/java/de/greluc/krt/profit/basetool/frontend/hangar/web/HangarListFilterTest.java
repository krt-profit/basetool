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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipOrigin;
import org.junit.jupiter.api.Test;

/**
 * The hangar tabs' list filters (REQ-HANGAR-002, REQ-HANGAR-017): a crafted query value filters
 * nothing, and the pagination links keep exactly the active filters.
 */
class HangarListFilterTest {

  @Test
  void theQueryIsReadLeniently() {
    assertThat(HangarListFilter.of("  Cutlass ", "TRUE", "IN_GAME"))
        .isEqualTo(new HangarListFilter("Cutlass", Boolean.TRUE, ShipOrigin.IN_GAME));
    assertThat(HangarListFilter.of(" ", "maybe", "RENTED")).isEqualTo(HangarListFilter.NONE);
    assertThat(HangarListFilter.of(null, "false", null).fitted()).isFalse();
  }

  @Test
  void theFilterControlsHoldTheActiveValuesOrNothing() {
    HangarListFilter filter = new HangarListFilter(null, Boolean.FALSE, ShipOrigin.PLEDGE);

    assertThat(filter.fittedParam()).isEqualTo("false");
    assertThat(filter.originParam()).isEqualTo("PLEDGE");
    assertThat(HangarListFilter.NONE.fittedParam()).isEmpty();
    assertThat(HangarListFilter.NONE.originParam()).isEmpty();
  }

  @Test
  void thePaginationLinkKeepsTheActiveFilters() {
    assertThat(HangarListFilter.NONE.baseUrl("/hangar")).isEqualTo("/hangar");
    assertThat(new HangarListFilter("Cutlass", Boolean.TRUE, ShipOrigin.IN_GAME).baseUrl("/hangar"))
        .isEqualTo("/hangar?search=Cutlass&fitted=true&origin=IN_GAME");
    assertThat(HangarListFilter.searchOnly(" Hull ").baseUrl("/hangar/squadron"))
        .isEqualTo("/hangar/squadron?search=Hull");
  }
}
