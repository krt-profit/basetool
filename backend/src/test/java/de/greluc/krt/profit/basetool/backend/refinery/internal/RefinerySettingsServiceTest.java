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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link RefinerySettingsService}, the typed view of the refinery settings. */
@ExtendWith(MockitoExtension.class)
class RefinerySettingsServiceTest {

  @Mock private SystemSettings systemSettings;
  @InjectMocks private RefinerySettingsService service;

  @Test
  @DisplayName("a stored rounding mode is returned as stored")
  void storedModeIsReturned() {
    when(systemSettings.getSettingValue("refinery.rounding.mode"))
        .thenReturn(Optional.of(" HALF_UP "));

    assertThat(service.read()).isEqualTo(new RefinerySettingsDto("HALF_UP"));
  }

  @Test
  @DisplayName("an absent rounding mode falls back to UP")
  void absentModeFallsBack() {
    when(systemSettings.getSettingValue("refinery.rounding.mode")).thenReturn(Optional.empty());

    assertThat(service.read()).isEqualTo(new RefinerySettingsDto("UP"));
  }

  @Test
  @DisplayName("a value that names no rounding mode falls back to UP")
  void unknownModeFallsBack() {
    when(systemSettings.getSettingValue("refinery.rounding.mode"))
        .thenReturn(Optional.of("TO_THE_MOON"));

    assertThat(service.read()).isEqualTo(new RefinerySettingsDto("UP"));
  }
}
