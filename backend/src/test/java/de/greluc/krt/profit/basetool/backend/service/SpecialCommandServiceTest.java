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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Mockito unit tests for {@link SpecialCommandService}. Pins the CRUD contract that the admin SK UI
 * relies on: case-insensitive uniqueness on create/update, optimistic-lock failures, soft- delete +
 * activate semantics, NotFoundException propagation, and the {@link SpecialCommand} constructor's
 * enforcement of {@code isPromotionEnabled = false}.
 */
@ExtendWith(MockitoExtension.class)
class SpecialCommandServiceTest {

  @Mock private SpecialCommandRepository specialCommandRepository;

  @InjectMocks private SpecialCommandService specialCommandService;

  private SpecialCommand alpha;
  private UUID alphaId;

  @BeforeEach
  void setUp() {
    alphaId = UUID.randomUUID();
    alpha = new SpecialCommand();
    alpha.setId(alphaId);
    alpha.setName("Alpha");
    alpha.setShorthand("ALF");
    alpha.setDescription("First special command");
  }

  @Test
  void getAllSpecialCommands_activeOnly_callsActiveFinder() {
    when(specialCommandRepository.findAllByActiveTrue()).thenReturn(List.of(alpha));

    List<SpecialCommand> all = specialCommandService.getAllSpecialCommands(false);

    assertEquals(1, all.size());
    assertSame(alpha, all.get(0));
    verify(specialCommandRepository, times(1)).findAllByActiveTrue();
    verify(specialCommandRepository, never()).findAll();
  }

  @Test
  void getAllSpecialCommands_includeInactive_callsFindAll() {
    when(specialCommandRepository.findAll()).thenReturn(List.of(alpha));

    List<SpecialCommand> all = specialCommandService.getAllSpecialCommands(true);

    assertEquals(1, all.size());
    verify(specialCommandRepository, times(1)).findAll();
    verify(specialCommandRepository, never()).findAllByActiveTrue();
  }

  @Test
  void getAllSpecialCommands_paged_activeOnly_callsActiveFinder() {
    Pageable pageable = PageRequest.of(0, 20);
    Page<SpecialCommand> page = new PageImpl<>(List.of(alpha));
    when(specialCommandRepository.findAllByActiveTrue(pageable)).thenReturn(page);

    Page<SpecialCommand> result = specialCommandService.getAllSpecialCommands(pageable, false);

    assertEquals(1, result.getTotalElements());
    verify(specialCommandRepository, times(1)).findAllByActiveTrue(pageable);
  }

  @Test
  void getAllSpecialCommands_paged_includeInactive_callsFindAll() {
    Pageable pageable = PageRequest.of(0, 20);
    Page<SpecialCommand> page = new PageImpl<>(List.of(alpha));
    when(specialCommandRepository.findAll(pageable)).thenReturn(page);

    Page<SpecialCommand> result = specialCommandService.getAllSpecialCommands(pageable, true);

    assertEquals(1, result.getTotalElements());
    verify(specialCommandRepository, times(1)).findAll(pageable);
  }

  @Test
  void getSpecialCommandById_present_returnsEntity() {
    when(specialCommandRepository.findById(alphaId)).thenReturn(Optional.of(alpha));

    SpecialCommand found = specialCommandService.getSpecialCommandById(alphaId);

    assertSame(alpha, found);
  }

  @Test
  void getSpecialCommandById_absent_throwsNotFound() {
    UUID missing = UUID.randomUUID();
    when(specialCommandRepository.findById(missing)).thenReturn(Optional.empty());

    NotFoundException ex =
        assertThrows(
            NotFoundException.class, () -> specialCommandService.getSpecialCommandById(missing));
    assertTrue(ex.getMessage().contains("SpecialCommand"));
  }

  @Test
  void createSpecialCommand_uniqueName_persists() {
    when(specialCommandRepository.existsByNameIgnoreCase("Alpha")).thenReturn(false);
    when(specialCommandRepository.save(alpha)).thenReturn(alpha);

    SpecialCommand saved = specialCommandService.createSpecialCommand(alpha);

    assertSame(alpha, saved);
    verify(specialCommandRepository).save(alpha);
  }

  @Test
  void createSpecialCommand_duplicateName_throwsDuplicate() {
    when(specialCommandRepository.existsByNameIgnoreCase("Alpha")).thenReturn(true);

    DuplicateEntityException ex =
        assertThrows(
            DuplicateEntityException.class,
            () -> specialCommandService.createSpecialCommand(alpha));
    assertTrue(ex.getMessage().contains("Alpha"));
    verify(specialCommandRepository, never()).save(any());
  }

  @Test
  void createSpecialCommand_enforcesPromotionDisabled() {
    SpecialCommand fresh = new SpecialCommand();
    fresh.setName("Bravo");
    fresh.setShorthand("BRV");
    assertFalse(
        fresh.isPromotionEnabled(),
        "SpecialCommand constructor must initialise isPromotionEnabled = false");
  }

  @Test
  void updateSpecialCommand_uniqueName_persists() {
    SpecialCommandDto dto =
        new SpecialCommandDto(alphaId, "Alpha Renamed", "ALR", "New desc", true, false, 0L);
    when(specialCommandRepository.existsByNameIgnoreCaseAndIdNot("Alpha Renamed", alphaId))
        .thenReturn(false);
    when(specialCommandRepository.findById(alphaId)).thenReturn(Optional.of(alpha));
    when(specialCommandRepository.save(alpha)).thenReturn(alpha);
    alpha.setVersion(0L);

    SpecialCommand updated = specialCommandService.updateSpecialCommand(alphaId, dto);

    assertEquals("Alpha Renamed", updated.getName());
    assertEquals("ALR", updated.getShorthand());
    assertEquals("New desc", updated.getDescription());
    verify(specialCommandRepository).save(alpha);
  }

  @Test
  void updateSpecialCommand_duplicateName_throwsDuplicate() {
    SpecialCommandDto dto =
        new SpecialCommandDto(alphaId, "Existing Name", "EXN", null, true, false, 0L);
    when(specialCommandRepository.existsByNameIgnoreCaseAndIdNot("Existing Name", alphaId))
        .thenReturn(true);

    DuplicateEntityException ex =
        assertThrows(
            DuplicateEntityException.class,
            () -> specialCommandService.updateSpecialCommand(alphaId, dto));
    assertTrue(ex.getMessage().contains("Existing Name"));
    verify(specialCommandRepository, never()).findById(any());
    verify(specialCommandRepository, never()).save(any());
  }

  @Test
  void updateSpecialCommand_staleVersion_throwsOptimisticLock() {
    SpecialCommandDto dto = new SpecialCommandDto(alphaId, "Alpha", "ALF", null, true, false, 0L);
    alpha.setVersion(5L);
    when(specialCommandRepository.existsByNameIgnoreCaseAndIdNot("Alpha", alphaId))
        .thenReturn(false);
    when(specialCommandRepository.findById(alphaId)).thenReturn(Optional.of(alpha));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> specialCommandService.updateSpecialCommand(alphaId, dto));
    verify(specialCommandRepository, never()).save(any());
  }

  @Test
  void updateSpecialCommand_missingId_throwsNotFound() {
    SpecialCommandDto dto = new SpecialCommandDto(alphaId, "Alpha", "ALF", null, true, false, 0L);
    when(specialCommandRepository.existsByNameIgnoreCaseAndIdNot("Alpha", alphaId))
        .thenReturn(false);
    when(specialCommandRepository.findById(alphaId)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class, () -> specialCommandService.updateSpecialCommand(alphaId, dto));
  }

  @Test
  void setProfitEligible_flipsFlagAndPersists() {
    alpha.setProfitEligible(false);
    when(specialCommandRepository.findById(alphaId)).thenReturn(Optional.of(alpha));
    when(specialCommandRepository.save(alpha)).thenReturn(alpha);

    SpecialCommand updated = specialCommandService.setProfitEligible(alphaId, true);

    assertTrue(updated.isProfitEligible(), "Toggle must flip the flag on");
    verify(specialCommandRepository).save(alpha);
  }

  @Test
  void setProfitEligible_missingId_throwsNotFound() {
    when(specialCommandRepository.findById(alphaId)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class, () -> specialCommandService.setProfitEligible(alphaId, true));
    verify(specialCommandRepository, never()).save(any());
  }

  @Test
  void deleteSpecialCommand_present_flipsActiveToFalse() {
    alpha.setActive(true);
    when(specialCommandRepository.findById(alphaId)).thenReturn(Optional.of(alpha));
    when(specialCommandRepository.save(alpha)).thenReturn(alpha);

    specialCommandService.deleteSpecialCommand(alphaId);

    assertFalse(alpha.isActive());
    verify(specialCommandRepository).save(alpha);
  }

  @Test
  void deleteSpecialCommand_missing_throwsNotFound() {
    UUID missing = UUID.randomUUID();
    when(specialCommandRepository.findById(missing)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class, () -> specialCommandService.deleteSpecialCommand(missing));
    verify(specialCommandRepository, never()).save(any());
  }

  @Test
  void activateSpecialCommand_flipsActiveToTrue() {
    alpha.setActive(false);
    when(specialCommandRepository.findById(alphaId)).thenReturn(Optional.of(alpha));
    when(specialCommandRepository.save(alpha)).thenReturn(alpha);

    specialCommandService.activateSpecialCommand(alphaId);

    assertTrue(alpha.isActive());
    verify(specialCommandRepository).save(alpha);
  }

  @Test
  void activateSpecialCommand_missing_throwsNotFound() {
    UUID missing = UUID.randomUUID();
    when(specialCommandRepository.findById(missing)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class, () -> specialCommandService.activateSpecialCommand(missing));
  }
}
