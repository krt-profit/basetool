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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link QuickAccessHrefs}. */
class QuickAccessHrefsTest {

  /** The name is encoded into the search parameter, so it cannot add parameters. */
  @Test
  void encodesTheNameIntoTheSearchParameter() {
    assertThat(QuickAccessHrefs.filteredBy("/hangar/squadron", "Cutlass Black & Co"))
        .isEqualTo("/hangar/squadron?search=Cutlass%20Black%20%26%20Co");
  }
}
