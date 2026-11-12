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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.OperationLinkability;
import de.greluc.krt.profit.basetool.backend.mission.api.OperationSummaryProvider;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Verifies that a mission is linked only to an operation the caller may link to (REQ-MISSION-034):
 * create, sub-mission create and a changed core link ask the operation module and refuse before
 * anything is written; an unchanged link is not re-checked and clearing one is always allowed.
 */
@ExtendWith(MockitoExtension.class)
class MissionServiceOperationLinkTest {

  private static final long CORE_VERSION = 4L;

  @Mock private MissionRepository missionRepository;
  @Mock private OperationSummaryProvider operationSummaryProvider;
  @Mock private UserService userService;
  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private AuditRecorder auditRecorder;
  @Mock private MissionNotificationPublisher notificationPublisher;

  private MissionService missionService;

  @BeforeEach
  void setUp() {
    missionService =
        new MissionService(
            missionRepository,
            null,
            null,
            null,
            null,
            null,
            operationSummaryProvider,
            null,
            userService,
            requestScopeResolver,
            null,
            null,
            auditRecorder,
            null,
            notificationPublisher,
            null,
            null,
            null);
  }

  @Test
  void create_linksAnOperationTheCallerMayLinkTo() {
    UUID operationId = UUID.randomUUID();
    when(operationSummaryProvider.linkability(operationId))
        .thenReturn(OperationLinkability.LINKABLE);
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArgument(0));

    Mission saved = missionService.createMission(request(operationId));

    assertThat(saved.getOperationId()).isEqualTo(operationId);
  }

  @Test
  void create_refusesAnOperationTheCallerMayNotLinkTo_andWritesNothing() {
    UUID operationId = UUID.randomUUID();
    when(operationSummaryProvider.linkability(operationId))
        .thenReturn(OperationLinkability.NOT_LINKABLE);

    assertThatThrownBy(() -> missionService.createMission(request(operationId)))
        .isInstanceOf(AccessDeniedException.class);
    verify(missionRepository, never()).save(any(Mission.class));
    verifyNoInteractions(auditRecorder);
  }

  @Test
  void create_refusesAnUnknownOperation() {
    UUID operationId = UUID.randomUUID();
    when(operationSummaryProvider.linkability(operationId))
        .thenReturn(OperationLinkability.NO_SUCH_OPERATION);

    assertThatThrownBy(() -> missionService.createMission(request(operationId)))
        .isInstanceOf(NotFoundException.class);
    verify(missionRepository, never()).save(any(Mission.class));
  }

  @Test
  void create_withoutAnOperationAsksNothing() {
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArgument(0));

    Mission saved = missionService.createMission(request(null));

    assertThat(saved.getOperationId()).isNull();
    verifyNoInteractions(operationSummaryProvider);
  }

  @Test
  void subMission_refusesAnOperationTheCallerMayNotLinkTo_andWritesNothing() {
    Mission parent = mission(null);
    UUID operationId = UUID.randomUUID();
    when(missionRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
    when(operationSummaryProvider.linkability(operationId))
        .thenReturn(OperationLinkability.NOT_LINKABLE);

    assertThatThrownBy(() -> missionService.addSubMission(parent.getId(), request(operationId)))
        .isInstanceOf(AccessDeniedException.class);
    verify(missionRepository, never()).save(any(Mission.class));
    verifyNoInteractions(auditRecorder);
  }

  @Test
  void corePatch_refusesAChangedLinkTheCallerMayNotSet_beforeTheCounterMoves() {
    Mission existing = mission(null);
    UUID operationId = UUID.randomUUID();
    when(missionRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
    when(operationSummaryProvider.linkability(operationId))
        .thenReturn(OperationLinkability.NOT_LINKABLE);

    assertThatThrownBy(() -> patchCore(existing, operationId))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(existing.getOperationId()).isNull();
    verify(missionRepository, never()).bumpCoreVersionIfMatches(any(), anyLong());
    verify(missionRepository, never()).save(any(Mission.class));
  }

  @Test
  void corePatch_movesTheLinkToAnOperationTheCallerMayLinkTo() {
    Mission existing = mission(UUID.randomUUID());
    UUID target = UUID.randomUUID();
    stubCorePatch(existing);
    when(operationSummaryProvider.linkability(target)).thenReturn(OperationLinkability.LINKABLE);

    assertThat(patchCore(existing, target).getOperationId()).isEqualTo(target);
  }

  @Test
  void corePatch_keepsAnUnchangedLinkWithoutCheckingIt() {
    UUID foreignOperation = UUID.randomUUID();
    Mission existing = mission(foreignOperation);
    stubCorePatch(existing);

    assertThat(patchCore(existing, foreignOperation).getOperationId()).isEqualTo(foreignOperation);
    verifyNoInteractions(operationSummaryProvider);
  }

  @Test
  void corePatch_clearsALinkWithoutCheckingIt() {
    Mission existing = mission(UUID.randomUUID());
    stubCorePatch(existing);

    assertThat(patchCore(existing, null).getOperationId()).isNull();
    verifyNoInteractions(operationSummaryProvider);
  }

  private void stubCorePatch(Mission existing) {
    when(missionRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
    when(missionRepository.bumpCoreVersionIfMatches(existing.getId(), CORE_VERSION)).thenReturn(1);
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArgument(0));
  }

  private Mission patchCore(Mission existing, @Nullable UUID operationId) {
    return missionService.updateCoreSection(
        existing.getId(), "Einsatz", null, null, "PLANNED", operationId, null, CORE_VERSION);
  }

  private static Mission mission(@Nullable UUID operationId) {
    Mission mission = new Mission();
    mission.setId(UUID.randomUUID());
    mission.setName("Einsatz");
    mission.setStatus("PLANNED");
    mission.setCoreVersion(CORE_VERSION);
    mission.setOperationId(operationId);
    return mission;
  }

  private static CreateMissionRequest request(@Nullable UUID operationId) {
    return new CreateMissionRequest(
        "Einsatz",
        null,
        null,
        "PLANNED",
        null,
        null,
        null,
        false,
        operationId,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
