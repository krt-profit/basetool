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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.Outpost;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.OutpostRepository;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link OutpostService}'s admin-override mutators. */
@ExtendWith(MockitoExtension.class)
class OutpostServiceTest {

  @Mock private OutpostRepository outpostRepository;

  @InjectMocks private OutpostService service;

  @Test
  void setLoadingDockOverride_storesValueAndFlag() {
    UUID id = UUID.randomUUID();
    Outpost outpost = new Outpost();
    outpost.setId(id);
    when(outpostRepository.findById(id)).thenReturn(Optional.of(outpost));
    when(outpostRepository.save(any(Outpost.class))).thenAnswer(i -> i.getArgument(0));

    service.setLoadingDockOverride(id, true);

    ArgumentCaptor<Outpost> cap = ArgumentCaptor.forClass(Outpost.class);
    verify(outpostRepository).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock());
    assertTrue(cap.getValue().getHasLoadingDockOverridden());
  }

  @Test
  void clearLoadingDockOverride_flipsFlagButLeavesValueAlone() {
    UUID id = UUID.randomUUID();
    Outpost outpost = new Outpost();
    outpost.setId(id);
    outpost.setHasLoadingDock(true);
    outpost.setHasLoadingDockOverridden(true);
    when(outpostRepository.findById(id)).thenReturn(Optional.of(outpost));
    when(outpostRepository.save(any(Outpost.class))).thenAnswer(i -> i.getArgument(0));

    service.clearLoadingDockOverride(id);

    ArgumentCaptor<Outpost> cap = ArgumentCaptor.forClass(Outpost.class);
    verify(outpostRepository).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock(), "value column must not be touched on clear");
    assertFalse(cap.getValue().getHasLoadingDockOverridden());
  }

  @Test
  void getOutpost_throwsNotFoundOnMissingId() {
    UUID id = UUID.randomUUID();
    when(outpostRepository.findById(id)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.getOutpost(id));
  }
}
