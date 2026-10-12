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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.Poi;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.PoiRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.PoiService;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link PoiService}'s admin-override mutators. */
@ExtendWith(MockitoExtension.class)
class PoiServiceTest {

  @Mock private PoiRepository poiRepository;

  @InjectMocks private PoiService service;

  @Test
  void setLoadingDockOverride_storesValueAndFlag() {
    UUID id = UUID.randomUUID();
    Poi poi = new Poi();
    poi.setId(id);
    when(poiRepository.findById(id)).thenReturn(Optional.of(poi));
    when(poiRepository.save(any(Poi.class))).thenAnswer(i -> i.getArgument(0));

    service.setLoadingDockOverride(id, true);

    ArgumentCaptor<Poi> cap = ArgumentCaptor.forClass(Poi.class);
    verify(poiRepository).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock());
    assertTrue(cap.getValue().getHasLoadingDockOverridden());
  }

  @Test
  void clearLoadingDockOverride_flipsFlagButLeavesValueAlone() {
    UUID id = UUID.randomUUID();
    Poi poi = new Poi();
    poi.setId(id);
    poi.setHasLoadingDock(true);
    poi.setHasLoadingDockOverridden(true);
    when(poiRepository.findById(id)).thenReturn(Optional.of(poi));
    when(poiRepository.save(any(Poi.class))).thenAnswer(i -> i.getArgument(0));

    service.clearLoadingDockOverride(id);

    ArgumentCaptor<Poi> cap = ArgumentCaptor.forClass(Poi.class);
    verify(poiRepository).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock(), "value column must not be touched on clear");
    assertFalse(cap.getValue().getHasLoadingDockOverridden());
  }

  @Test
  void getPoi_throwsNotFoundOnMissingId() {
    UUID id = UUID.randomUUID();
    when(poiRepository.findById(id)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.getPoi(id));
  }
}
