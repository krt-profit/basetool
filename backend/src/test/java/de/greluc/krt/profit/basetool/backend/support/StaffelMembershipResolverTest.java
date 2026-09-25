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

package de.greluc.krt.profit.basetool.backend.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@link StaffelMembershipResolver}, the "name-sorted primary Staffel" rule
 * (REQ-ORG-017): empty input, the single-Staffel fast path, case-insensitive ordering and the
 * dangling-membership skip.
 */
@ExtendWith(MockitoExtension.class)
class StaffelMembershipResolverTest {

  @Mock private SquadronRepository squadronRepository;

  @Mock private OrgUnitRepository orgUnitRepository;

  @InjectMocks private StaffelMembershipResolver resolver;

  @Test
  void resolveNameSortedStaffelIds_empty_returnsEmptyAndNeverHitsSquadronTable() {
    assertTrue(resolver.resolveNameSortedStaffelIds(List.of()).isEmpty());
    verifyNoInteractions(squadronRepository, orgUnitRepository);
  }

  @Test
  void resolveNameSortedStaffelIds_single_returnsItAfterCheapExistenceCheck() {
    UUID userId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    when(squadronRepository.existsById(squadronId)).thenReturn(true);

    List<UUID> result =
        resolver.resolveNameSortedStaffelIds(List.of(staffelRow(userId, squadronId)));

    assertEquals(List.of(squadronId), result);
    verify(squadronRepository).existsById(squadronId);
    verify(orgUnitRepository, never()).findAllById(any());
  }

  @Test
  void resolveNameSortedStaffelIds_singleDangling_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    UUID danglingId = UUID.randomUUID();
    when(squadronRepository.existsById(danglingId)).thenReturn(false);

    List<UUID> result =
        resolver.resolveNameSortedStaffelIds(List.of(staffelRow(userId, danglingId)));

    assertTrue(result.isEmpty());
  }

  @Test
  void resolveNameSortedStaffelIds_two_returnsNameSortedPrimaryFirst() {
    UUID userId = UUID.randomUUID();
    UUID alphaId = UUID.randomUUID();
    UUID bravoId = UUID.randomUUID();
    when(orgUnitRepository.findAllById(any()))
        .thenReturn(List.of((OrgUnit) squadron(bravoId, "Bravo"), squadron(alphaId, "alpha")));

    List<UUID> result =
        resolver.resolveNameSortedStaffelIds(
            List.of(staffelRow(userId, bravoId), staffelRow(userId, alphaId)));

    assertEquals(List.of(alphaId, bravoId), result);
  }

  @Test
  void resolveNameSortedStaffelIds_two_skipsDanglingRow() {
    UUID userId = UUID.randomUUID();
    UUID aliveId = UUID.randomUUID();
    UUID danglingId = UUID.randomUUID();
    when(orgUnitRepository.findAllById(any()))
        .thenReturn(List.of((OrgUnit) squadron(aliveId, "Alpha")));

    List<UUID> result =
        resolver.resolveNameSortedStaffelIds(
            List.of(staffelRow(userId, danglingId), staffelRow(userId, aliveId)));

    assertEquals(List.of(aliveId), result);
  }

  @Test
  void resolveNameSortedStaffeln_empty_returnsEmptyAndNeverHitsSquadronTable() {
    assertTrue(resolver.resolveNameSortedStaffeln(List.of()).isEmpty());
    verifyNoInteractions(squadronRepository, orgUnitRepository);
  }

  @Test
  void resolveNameSortedStaffeln_two_returnsNameSortedEntities() {
    UUID userId = UUID.randomUUID();
    UUID alphaId = UUID.randomUUID();
    UUID bravoId = UUID.randomUUID();
    Squadron alpha = squadron(alphaId, "Alpha");
    Squadron bravo = squadron(bravoId, "Bravo");
    when(orgUnitRepository.findAllById(any())).thenReturn(List.of((OrgUnit) bravo, alpha));

    List<Squadron> result =
        resolver.resolveNameSortedStaffeln(
            List.of(staffelRow(userId, bravoId), staffelRow(userId, alphaId)));

    assertEquals(List.of(alpha, bravo), result);
    verify(orgUnitRepository).findAllById(any());
  }

  @Test
  void resolveNameSortedStaffeln_filtersNonSquadronKinds() {
    UUID userId = UUID.randomUUID();
    UUID staffelId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    Squadron staffel = squadron(staffelId, "Alpha");
    SpecialCommand sk = new SpecialCommand();
    sk.setId(skId);
    sk.setName("SK Logistik");
    when(orgUnitRepository.findAllById(any())).thenReturn(List.of(sk, staffel));

    List<Squadron> result =
        resolver.resolveNameSortedStaffeln(
            List.of(staffelRow(userId, skId), staffelRow(userId, staffelId)));

    assertEquals(List.of(staffel), result);
  }

  /** Builds a {@code SQUADRON}-kind membership row pointing the user at the given squadron. */
  private static OrgUnitMembership staffelRow(UUID userId, UUID squadronId) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, squadronId));
    m.setKind(OrgUnitKind.SQUADRON);
    return m;
  }

  /** Builds a squadron fixture with the given id and name. */
  private static Squadron squadron(UUID id, String name) {
    Squadron s = new Squadron();
    s.setId(id);
    s.setName(name);
    return s;
  }
}
