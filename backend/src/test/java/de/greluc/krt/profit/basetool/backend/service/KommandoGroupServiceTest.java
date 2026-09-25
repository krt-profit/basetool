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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.KommandoGroupMapper;
import de.greluc.krt.profit.basetool.backend.mapper.KommandoGroupMapperImpl;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateKommandoGroupRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.KommandoGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateKommandoGroupRequest;
import de.greluc.krt.profit.basetool.backend.repository.KommandoGroupRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link KommandoGroupService} (REQ-ROLE-003): create, rename and delete, the
 * per-squadron limit and parent-kind 400s, the referenced-group delete guard, optimistic locking
 * and the {@code ROLE} audit events.
 */
@ExtendWith(MockitoExtension.class)
class KommandoGroupServiceTest {

  @Mock private KommandoGroupRepository kommandoGroupRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private AuditService auditService;
  @Mock private OrgChartService orgChartService;

  @Spy private KommandoGroupMapper kommandoGroupMapper = new KommandoGroupMapperImpl();

  @InjectMocks private KommandoGroupService service;

  private UUID squadronId;
  private Squadron squadron;

  @BeforeEach
  void setUp() {
    squadronId = UUID.randomUUID();
    squadron = new Squadron();
    squadron.setId(squadronId);
    squadron.setName("Alpha");
    squadron.setShorthand("ALF");
  }

  @Test
  void createGroup_persistsAndAudits() {
    when(orgUnitRepository.findById(squadronId)).thenReturn(Optional.of(squadron));
    when(kommandoGroupRepository.countBySquadronId(squadronId)).thenReturn(2L);
    when(kommandoGroupRepository.saveAndFlush(any(KommandoGroup.class)))
        .thenAnswer(
            inv -> {
              KommandoGroup g = inv.getArgument(0);
              g.setId(UUID.randomUUID());
              return g;
            });

    KommandoGroupDto dto =
        service.createGroup(squadronId, new CreateKommandoGroupRequest("  Jagd  "));

    assertEquals("Jagd", dto.name(), "name is trimmed");
    assertEquals(2, dto.sortIndex(), "appended at the end (count = 2)");
    assertEquals(squadronId, dto.squadronId());
    verify(orgChartService).mirrorCreateKommandoGroup(any(KommandoGroup.class));
    verify(auditService)
        .record(eq(AuditEventType.KOMMANDO_GROUP_CREATED), any(), eq("Jagd"), eq(null), any());
  }

  @Test
  void createGroup_fifthGroup_throwsBadRequest() {
    when(orgUnitRepository.findById(squadronId)).thenReturn(Optional.of(squadron));
    when(kommandoGroupRepository.countBySquadronId(squadronId)).thenReturn(4L);

    assertThrows(
        BadRequestException.class,
        () -> service.createGroup(squadronId, new CreateKommandoGroupRequest("Fifth")));
    verify(kommandoGroupRepository, never()).saveAndFlush(any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void createGroup_notSquadron_throwsBadRequest() {
    UUID skId = UUID.randomUUID();
    SpecialCommand sc = new SpecialCommand();
    sc.setId(skId);
    when(orgUnitRepository.findById(skId)).thenReturn(Optional.of(sc));

    assertThrows(
        BadRequestException.class,
        () -> service.createGroup(skId, new CreateKommandoGroupRequest("Nope")));
    verify(kommandoGroupRepository, never()).saveAndFlush(any());
  }

  @Test
  void updateGroup_renamesAndAudits() {
    UUID groupId = UUID.randomUUID();
    KommandoGroup group =
        KommandoGroup.builder().squadron(squadron).name("Alpha").sortIndex(0).build();
    group.setId(groupId);
    group.setVersion(3L);
    when(kommandoGroupRepository.findById(groupId)).thenReturn(Optional.of(group));
    when(kommandoGroupRepository.saveAndFlush(any(KommandoGroup.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    KommandoGroupDto dto =
        service.updateGroup(groupId, new UpdateKommandoGroupRequest("Renamed", 1, 3L));

    assertEquals("Renamed", dto.name());
    assertEquals(1, dto.sortIndex());
    verify(orgChartService).mirrorUpdateKommandoGroup(any(KommandoGroup.class));
    verify(auditService)
        .record(
            eq(AuditEventType.KOMMANDO_GROUP_UPDATED), eq(groupId), eq("Renamed"), eq(null), any());
  }

  @Test
  void updateGroup_staleVersion_throwsOptimisticLock() {
    UUID groupId = UUID.randomUUID();
    KommandoGroup group =
        KommandoGroup.builder().squadron(squadron).name("Alpha").sortIndex(0).build();
    group.setId(groupId);
    group.setVersion(5L);
    when(kommandoGroupRepository.findById(groupId)).thenReturn(Optional.of(group));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> service.updateGroup(groupId, new UpdateKommandoGroupRequest("X", 0, 1L)));
    verify(kommandoGroupRepository, never()).saveAndFlush(any());
  }

  @Test
  void deleteGroup_unreferenced_deletesAndAudits() {
    UUID groupId = UUID.randomUUID();
    KommandoGroup group =
        KommandoGroup.builder().squadron(squadron).name("Alpha").sortIndex(0).build();
    group.setId(groupId);
    when(kommandoGroupRepository.findById(groupId)).thenReturn(Optional.of(group));
    when(membershipRepository.existsByKommandoGroupId(groupId)).thenReturn(false);

    service.deleteGroup(groupId);

    verify(orgChartService).mirrorDeleteKommandoGroup(groupId);
    verify(kommandoGroupRepository).delete(group);
    verify(auditService)
        .record(
            eq(AuditEventType.KOMMANDO_GROUP_DELETED),
            eq(groupId),
            eq("Alpha"),
            eq(null),
            eq(null));
  }

  @Test
  void deleteGroup_referenced_throwsBadRequest() {
    UUID groupId = UUID.randomUUID();
    KommandoGroup group =
        KommandoGroup.builder().squadron(squadron).name("Alpha").sortIndex(0).build();
    group.setId(groupId);
    when(kommandoGroupRepository.findById(groupId)).thenReturn(Optional.of(group));
    when(membershipRepository.existsByKommandoGroupId(groupId)).thenReturn(true);

    assertThrows(BadRequestException.class, () -> service.deleteGroup(groupId));
    verify(kommandoGroupRepository, never()).delete(any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void deleteGroup_unknown_throwsNotFound() {
    UUID groupId = UUID.randomUUID();
    when(kommandoGroupRepository.findById(groupId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.deleteGroup(groupId));
  }
}
