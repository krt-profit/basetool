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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.Department;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.BereichRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrganisationsleitungRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Mockito unit tests for {@link OrgHierarchyService} (epic #692, REQ-ORG-014). Pins the Bereich/OL
 * creation contract (name uniqueness, the OL singleton guard, parent-kind validation on create) and
 * the set-parent kind pairing + optimistic-lock semantics.
 */
@ExtendWith(MockitoExtension.class)
class OrgHierarchyServiceTest {

  @Mock private BereichRepository bereichRepository;
  @Mock private OrganisationsleitungRepository organisationsleitungRepository;
  @Mock private OrgUnitRepository orgUnitRepository;

  @InjectMocks private OrgHierarchyService service;

  @Test
  void listAllOrgUnits_delegatesToRepository() {
    Squadron staffel = new Squadron();
    staffel.setId(UUID.randomUUID());
    when(orgUnitRepository.findAllActiveWithParent()).thenReturn(List.of(staffel));

    List<OrgUnit> result = service.listAllOrgUnits();

    assertEquals(1, result.size());
    assertSame(staffel, result.get(0));
    verify(orgUnitRepository).findAllActiveWithParent();
  }

  @Test
  void createBereich_unparented_persists() {
    when(bereichRepository.existsByNameIgnoreCase("Profit")).thenReturn(false);
    when(bereichRepository.save(any(Bereich.class))).thenAnswer(inv -> inv.getArgument(0));

    Bereich result =
        service.createBereich("Profit", "PRF", "the profit area", null, Department.PROFIT);

    assertEquals("Profit", result.getName());
    assertNull(result.getParent());
    assertEquals(Department.PROFIT, result.getDepartment());
  }

  @Test
  void createBereich_duplicateName_throws() {
    when(bereichRepository.existsByNameIgnoreCase("Profit")).thenReturn(true);

    assertThrows(
        DuplicateEntityException.class,
        () -> service.createBereich("Profit", "PRF", null, null, null));
    verify(bereichRepository, never()).save(any());
  }

  @Test
  void createBereich_parentNotOrganisationsleitung_throwsBadRequest() {
    UUID parentId = UUID.randomUUID();
    Squadron notAnOl = new Squadron();
    notAnOl.setId(parentId);
    when(bereichRepository.existsByNameIgnoreCase("Profit")).thenReturn(false);
    when(orgUnitRepository.findById(parentId)).thenReturn(Optional.of(notAnOl));

    assertThrows(
        BadRequestException.class,
        () -> service.createBereich("Profit", "PRF", null, parentId, null));
    verify(bereichRepository, never()).save(any());
  }

  @Test
  void createBereich_withOlParent_persistsWithParent() {
    UUID olId = UUID.randomUUID();
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    when(bereichRepository.existsByNameIgnoreCase("Profit")).thenReturn(false);
    when(orgUnitRepository.findById(olId)).thenReturn(Optional.of(ol));
    when(bereichRepository.save(any(Bereich.class))).thenAnswer(inv -> inv.getArgument(0));

    Bereich result = service.createBereich("Profit", "PRF", null, olId, null);

    assertSame(ol, result.getParent());
  }

  @Test
  void createOrganisationsleitung_first_persists() {
    when(organisationsleitungRepository.findAllByActiveTrue()).thenReturn(List.of());
    when(organisationsleitungRepository.save(any(Organisationsleitung.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    Organisationsleitung result = service.createOrganisationsleitung("Leitung", "OL", null);

    assertEquals("Leitung", result.getName());
  }

  @Test
  void createOrganisationsleitung_secondRejected() {
    when(organisationsleitungRepository.findAllByActiveTrue())
        .thenReturn(List.of(new Organisationsleitung()));

    assertThrows(
        DuplicateEntityException.class,
        () -> service.createOrganisationsleitung("Leitung", "OL", null));
    verify(organisationsleitungRepository, never()).save(any());
  }

  @Test
  void setParent_squadronToBereich_persists() {
    UUID squadronId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    Squadron squadron = new Squadron();
    squadron.setId(squadronId);
    squadron.setVersion(0L);
    Bereich bereich = new Bereich();
    bereich.setId(bereichId);
    when(orgUnitRepository.findById(squadronId)).thenReturn(Optional.of(squadron));
    when(orgUnitRepository.findById(bereichId)).thenReturn(Optional.of(bereich));
    when(orgUnitRepository.saveAndFlush(any(OrgUnit.class))).thenAnswer(inv -> inv.getArgument(0));

    OrgUnit result = service.setParent(squadronId, bereichId, 0L);

    assertSame(bereich, result.getParent());
  }

  @Test
  void setParent_squadronToOl_throwsBadRequest() {
    UUID squadronId = UUID.randomUUID();
    UUID olId = UUID.randomUUID();
    Squadron squadron = new Squadron();
    squadron.setId(squadronId);
    squadron.setVersion(0L);
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    when(orgUnitRepository.findById(squadronId)).thenReturn(Optional.of(squadron));
    when(orgUnitRepository.findById(olId)).thenReturn(Optional.of(ol));

    assertThrows(BadRequestException.class, () -> service.setParent(squadronId, olId, 0L));
    verify(orgUnitRepository, never()).saveAndFlush(any());
  }

  @Test
  void setParent_staleVersion_throwsOptimisticLock() {
    UUID squadronId = UUID.randomUUID();
    Squadron squadron = new Squadron();
    squadron.setId(squadronId);
    squadron.setVersion(5L);
    when(orgUnitRepository.findById(squadronId)).thenReturn(Optional.of(squadron));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> service.setParent(squadronId, UUID.randomUUID(), 0L));
    verify(orgUnitRepository, never()).saveAndFlush(any());
  }

  @Test
  void setParent_nullParent_detaches() {
    UUID squadronId = UUID.randomUUID();
    Squadron squadron = new Squadron();
    squadron.setId(squadronId);
    squadron.setVersion(0L);
    Bereich oldParent = new Bereich();
    squadron.setParent(oldParent);
    when(orgUnitRepository.findById(squadronId)).thenReturn(Optional.of(squadron));
    when(orgUnitRepository.saveAndFlush(any(OrgUnit.class))).thenAnswer(inv -> inv.getArgument(0));

    OrgUnit result = service.setParent(squadronId, null, 0L);

    assertNull(result.getParent());
  }
}
