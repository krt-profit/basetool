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

/** Unit tests for {@link InstanceMarkerAdvice}: the model flag mirrors the configured instance. */
class InstanceMarkerAdviceTest {

  @Test
  void theTestSystemSettingTurnsTheFlagOn() {
    assertThat(new InstanceMarkerAdvice(new InstanceProperties(true)).testSystem()).isTrue();
  }

  @Test
  void productionLeavesTheFlagOff() {
    assertThat(new InstanceMarkerAdvice(new InstanceProperties(false)).testSystem()).isFalse();
  }
}
