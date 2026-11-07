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

import java.util.List;
import org.junit.jupiter.api.Test;

/** Game versions compare semantically as major.minor.patch (REQ-OBS-022). */
class P4kGameVersionTest {

  @Test
  void aWikiBuildReadsAsItsPatchVersion() {
    assertThat(P4kGameVersion.parse("4.10.2-LIVE.12881860"))
        .isEqualTo(new P4kGameVersion(4, 10, 2));
  }

  @Test
  void aVersionWithoutPatchReadsAsPatchZero() {
    assertThat(P4kGameVersion.parse("4.10")).isEqualTo(new P4kGameVersion(4, 10, 0));
  }

  @Test
  void textWithoutAVersionReadsAsUnknown() {
    assertThat(P4kGameVersion.parse("LIVE")).isNull();
    assertThat(P4kGameVersion.parse(null)).isNull();
    assertThat(P4kGameVersion.parse(" ")).isNull();
  }

  @Test
  void componentsCompareAsNumbersNotAsText() {
    assertThat(new P4kGameVersion(4, 10, 0)).isGreaterThan(new P4kGameVersion(4, 9, 9));
    assertThat(new P4kGameVersion(4, 10, 10)).isGreaterThan(new P4kGameVersion(4, 10, 9));
    assertThat(new P4kGameVersion(5, 0, 0)).isGreaterThan(new P4kGameVersion(4, 99, 99));
  }

  @Test
  void theHighestOfSeveralTextsSkipsTheUnreadableOnes() {
    assertThat(P4kGameVersion.highest(List.of("4.9.1", "junk", "4.10.0-LIVE.1", "4.10")))
        .isEqualTo(new P4kGameVersion(4, 10, 0));
    assertThat(P4kGameVersion.highest(List.of("junk"))).isNull();
  }

  @Test
  void aVersionPrintsAsMajorMinorPatch() {
    assertThat(new P4kGameVersion(4, 10, 2)).hasToString("4.10.2");
  }
}
