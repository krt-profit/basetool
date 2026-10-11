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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link JobOrderSettingsService}, the typed view of the job-order settings. */
@ExtendWith(MockitoExtension.class)
class JobOrderSettingsServiceTest {

  @Mock private SystemSettings systemSettings;
  @InjectMocks private JobOrderSettingsService service;

  @Test
  @DisplayName("the stored thresholds are returned as integers")
  void storedValuesAreParsed() {
    when(systemSettings.getSettingValue("job_order.age_yellow_days")).thenReturn(Optional.of("14"));
    when(systemSettings.getSettingValue("job_order.age_red_days")).thenReturn(Optional.of(" 60 "));

    assertThat(service.read()).isEqualTo(new JobOrderSettingsDto(14, 60));
  }

  @Test
  @DisplayName("an absent threshold falls back to its default")
  void absentValuesFallBack() {
    when(systemSettings.getSettingValue("job_order.age_yellow_days")).thenReturn(Optional.empty());
    when(systemSettings.getSettingValue("job_order.age_red_days")).thenReturn(Optional.empty());

    assertThat(service.read()).isEqualTo(new JobOrderSettingsDto(30, 90));
  }

  @Test
  @DisplayName("a malformed or negative threshold falls back to its own default only")
  void malformedValuesFallBackIndividually() {
    when(systemSettings.getSettingValue("job_order.age_yellow_days"))
        .thenReturn(Optional.of("soon"));
    when(systemSettings.getSettingValue("job_order.age_red_days")).thenReturn(Optional.of("-5"));

    assertThat(service.read()).isEqualTo(new JobOrderSettingsDto(30, 90));

    when(systemSettings.getSettingValue("job_order.age_yellow_days")).thenReturn(Optional.of("7"));

    assertThat(service.read()).isEqualTo(new JobOrderSettingsDto(7, 90));
  }
}
