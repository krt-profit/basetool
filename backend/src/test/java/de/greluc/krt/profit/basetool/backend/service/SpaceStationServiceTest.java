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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SpaceStationService;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link SpaceStationService}'s admin-override mutators. */
@ExtendWith(MockitoExtension.class)
class SpaceStationServiceTest {

  @Mock private SpaceStationRepository spaceStationRepository;

  @InjectMocks private SpaceStationService service;

  @Test
  void setLoadingDockOverride_storesValueAndFlag() {
    UUID id = UUID.randomUUID();
    SpaceStation station = new SpaceStation();
    station.setId(id);
    when(spaceStationRepository.findById(id)).thenReturn(Optional.of(station));
    when(spaceStationRepository.save(any(SpaceStation.class))).thenAnswer(i -> i.getArgument(0));

    service.setLoadingDockOverride(id, true);

    ArgumentCaptor<SpaceStation> cap = ArgumentCaptor.forClass(SpaceStation.class);
    verify(spaceStationRepository).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock());
    assertTrue(cap.getValue().getHasLoadingDockOverridden());
  }

  @Test
  void clearLoadingDockOverride_flipsFlagButLeavesValueAlone() {
    UUID id = UUID.randomUUID();
    SpaceStation station = new SpaceStation();
    station.setId(id);
    station.setHasLoadingDock(true);
    station.setHasLoadingDockOverridden(true);
    when(spaceStationRepository.findById(id)).thenReturn(Optional.of(station));
    when(spaceStationRepository.save(any(SpaceStation.class))).thenAnswer(i -> i.getArgument(0));

    service.clearLoadingDockOverride(id);

    ArgumentCaptor<SpaceStation> cap = ArgumentCaptor.forClass(SpaceStation.class);
    verify(spaceStationRepository).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock(), "value column must not be touched on clear");
    assertFalse(cap.getValue().getHasLoadingDockOverridden());
  }

  @Test
  void getSpaceStation_throwsNotFoundOnMissingId() {
    UUID id = UUID.randomUUID();
    when(spaceStationRepository.findById(id)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.getSpaceStation(id));
  }
}
