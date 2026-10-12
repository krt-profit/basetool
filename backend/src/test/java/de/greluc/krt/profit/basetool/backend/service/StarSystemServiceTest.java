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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.StarSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.StarSystemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.StarSystemService;
import de.greluc.krt.profit.basetool.backend.kernel.DuplicateEntityException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StarSystemServiceTest {

  @Mock private StarSystemRepository starSystemRepository;

  @InjectMocks private StarSystemService starSystemService;

  @Test
  void createStarSystem_DuplicateName_ShouldThrowException() {
    StarSystem starSystem = new StarSystem();
    starSystem.setName("Stanton");

    when(starSystemRepository.existsByNameIgnoreCase("Stanton")).thenReturn(true);

    assertThrows(
        DuplicateEntityException.class, () -> starSystemService.createStarSystem(starSystem));
  }

  @Test
  void deleteStarSystem_Success() {
    UUID systemId = UUID.randomUUID();
    StarSystem system = new StarSystem();
    system.setId(systemId);
    system.setName("Stanton");

    when(starSystemRepository.findById(systemId)).thenReturn(Optional.of(system));

    assertDoesNotThrow(() -> starSystemService.deleteStarSystem(systemId));
    verify(starSystemRepository, times(1)).delete(system);
  }
}
