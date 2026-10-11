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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShipLocationUsageTest {

  @Mock private ShipRepository shipRepository;

  @InjectMocks private ShipLocationUsage usage;

  @Test
  void namesTheShipsAndAsksBeforeTheOtherUsages() {
    assertThat(usage.users()).isEqualTo("ships");
    assertThat(usage.position()).isEqualTo(10);
  }

  @Test
  void reportsALocationAsUsedExactlyWhileAShipIsStationedAtIt() {
    UUID used = UUID.randomUUID();
    UUID free = UUID.randomUUID();
    when(shipRepository.existsByLocationId(used)).thenReturn(true);
    when(shipRepository.existsByLocationId(free)).thenReturn(false);

    assertThat(usage.isUsed(used)).isTrue();
    assertThat(usage.isUsed(free)).isFalse();
  }
}
