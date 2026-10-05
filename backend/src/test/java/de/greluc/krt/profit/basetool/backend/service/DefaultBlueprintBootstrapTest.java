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

package de.greluc.krt.profit.basetool.backend.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.repository.DefaultBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Pins the one-time seed flag of {@link DefaultBlueprintBootstrap} in the system settings. */
@ExtendWith(MockitoExtension.class)
class DefaultBlueprintBootstrapTest {

  @Mock private SystemSettings systemSettings;
  @Mock private DefaultBlueprintRepository defaultBlueprintRepository;
  @Mock private BlueprintProductService blueprintProductService;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private BlueprintNameNormalizer normalizer;
  @Mock private DefaultBlueprintProvisioningService provisioningService;
  @Mock private DefaultBlueprintKeyService keyService;

  @InjectMocks private DefaultBlueprintBootstrap bootstrap;

  @Test
  void aSetFlagInAnyCaseSkipsTheSeedAndLeavesTheFlagAlone() {
    when(systemSettings.getSettingValue(DefaultBlueprintBootstrap.SEEDED_FLAG_KEY))
        .thenReturn(Optional.of("TRUE"));

    bootstrap.run();

    verify(defaultBlueprintRepository, never()).save(any());
    verify(systemSettings, never()).putSettingValue(anyString(), anyString());
    verify(keyService).refresh();
    verify(provisioningService).grantDefaultsToAllUsers();
  }

  @Test
  void anAbsentFlagSeedsAndThenSetsTheFlag() {
    when(systemSettings.getSettingValue(DefaultBlueprintBootstrap.SEEDED_FLAG_KEY))
        .thenReturn(Optional.empty());
    when(normalizer.normalize(anyString())).thenReturn("key");
    when(defaultBlueprintRepository.existsByProductKey("key")).thenReturn(true);

    bootstrap.run();

    verify(systemSettings).putSettingValue(DefaultBlueprintBootstrap.SEEDED_FLAG_KEY, "true");
  }

  @Test
  void aFlagOtherThanTrueSeedsAgainAndOverwritesTheFlag() {
    when(systemSettings.getSettingValue(DefaultBlueprintBootstrap.SEEDED_FLAG_KEY))
        .thenReturn(Optional.of("false"));
    when(normalizer.normalize(anyString())).thenReturn("");

    bootstrap.run();

    verify(defaultBlueprintRepository, never()).save(any());
    verify(systemSettings).putSettingValue(DefaultBlueprintBootstrap.SEEDED_FLAG_KEY, "true");
  }
}
