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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionSectionVersions.MissionSection;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;

/** The mission edit recipe, tested once for every timeline, roster and structure mutation. */
@ExtendWith(MockitoExtension.class)
class MissionEditorTest {

  @Mock private MissionRepository missionRepository;
  @Mock private MissionAccessPolicy missionAccessPolicy;
  @Mock private AuthHelperService authHelperService;
  @Mock private AuditRecorder auditRecorder;

  private MissionEditor editor;
  private Mission mission;
  private UUID missionId;
  private AtomicInteger mutations;
  private Authentication caller;

  @BeforeEach
  void setUp() {
    editor =
        new MissionEditor(missionRepository, missionAccessPolicy, authHelperService, auditRecorder);
    missionId = UUID.randomUUID();
    mission = new Mission();
    mission.setId(missionId);
    mission.setName("Op Nightfall");
    mission.setStepsVersion(3L);
    mutations = new AtomicInteger();
    caller = mock(Authentication.class);
    lenient().when(caller.isAuthenticated()).thenReturn(true);
    lenient().when(authHelperService.rawAuthentication()).thenReturn(caller);
  }

  private static CharSequence sameDetails(AuditDetails expected) {
    return argThat(d -> d != null && d.toString().equals(expected.toString()));
  }

  private Function<Mission, MissionEdit> stepAdded() {
    return m -> {
      mutations.incrementAndGet();
      return MissionEdit.audited(AuditEventType.MISSION_STEP_ADDED, AuditDetails.of("count", 1));
    };
  }

  @Test
  void aSectionEditRunsLoadGateCheckMutationSaveAndAuditInThatOrder() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(missionAccessPolicy.canEdit(mission, MissionGate.MANAGE, caller)).thenReturn(true);
    when(missionRepository.bumpStepsVersionIfMatches(missionId, 3L)).thenReturn(1);

    Mission result =
        editor.editSection(missionId, MissionGate.MANAGE, MissionSection.STEPS, 3L, stepAdded());

    assertThat(result).isSameAs(mission);
    assertThat(mutations).hasValue(1);
    assertThat(mission.getStepsVersion()).isEqualTo(4L);
    InOrder order = inOrder(missionAccessPolicy, missionRepository, auditRecorder);
    order.verify(missionAccessPolicy).canEdit(mission, MissionGate.MANAGE, caller);
    order.verify(missionRepository).bumpStepsVersionIfMatches(missionId, 3L);
    order.verify(missionRepository).save(mission);
    order
        .verify(auditRecorder)
        .record(
            eq(AuditEventType.MISSION_STEP_ADDED),
            eq(missionId),
            eq("Op Nightfall"),
            isNull(),
            sameDetails(AuditDetails.of("count", 1)));
    order.verifyNoMoreInteractions();
  }

  @Test
  void aStaleSectionVersionIsAConflictWithoutMutationSaveOrAudit() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(missionAccessPolicy.canEdit(mission, MissionGate.MANAGE, caller)).thenReturn(true);
    when(missionRepository.bumpStepsVersionIfMatches(missionId, 2L)).thenReturn(0);

    assertThatThrownBy(
            () ->
                editor.editSection(
                    missionId, MissionGate.MANAGE, MissionSection.STEPS, 2L, stepAdded()))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(mutations).hasValue(0);
    assertThat(mission.getStepsVersion()).isEqualTo(3L);
    verify(missionRepository, never()).save(any());
    verifyNoInteractions(auditRecorder);
  }

  @Test
  void aDeniedCallerIsRefusedBeforeTheVersionIsTouched() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(missionAccessPolicy.canEdit(mission, MissionGate.MANAGE, caller)).thenReturn(false);

    assertThatThrownBy(
            () ->
                editor.editSection(
                    missionId, MissionGate.MANAGE, MissionSection.STEPS, 3L, stepAdded()))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(mutations).hasValue(0);
    verify(missionRepository, never()).bumpStepsVersionIfMatches(any(), anyLong());
    verify(missionRepository, never()).save(any());
    verifyNoInteractions(auditRecorder);
  }

  @Test
  void aCallerWithoutAnHttpRequestMeetsTheSameGate() {
    MissionAccessPolicy realPolicy =
        new MissionAccessPolicy(
            mock(OwnerScopeService.class),
            authHelperService,
            missionRepository,
            mock(UserService.class),
            mock(RoleHierarchy.class),
            mock(MissionParticipantRepository.class),
            mock(MissionFinanceEntryRepository.class));
    MissionEditor withRealPolicy =
        new MissionEditor(missionRepository, realPolicy, authHelperService, auditRecorder);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(authHelperService.rawAuthentication()).thenReturn(null);

    assertThatThrownBy(() -> withRealPolicy.edit(missionId, MissionGate.MANAGE, stepAdded()))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(mutations).hasValue(0);
    verifyNoInteractions(auditRecorder);
  }

  @Test
  void aReadGateEditWithoutAuthenticationIsRefusedEvenOnAPublicMission() {
    mission.setIsInternal(false);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(authHelperService.rawAuthentication()).thenReturn(null);

    assertThatThrownBy(() -> editor.edit(missionId, MissionGate.SEE, stepAdded()))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(mutations).hasValue(0);
    verifyNoInteractions(missionAccessPolicy, auditRecorder);
  }

  @Test
  void anUnknownMissionIsNotFoundBeforeTheGateIsAsked() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> editor.edit(missionId, MissionGate.MANAGE, stepAdded()))
        .isInstanceOf(NotFoundException.class);

    verifyNoInteractions(missionAccessPolicy, auditRecorder);
  }

  @Test
  void aRowEditNeitherBumpsASectionNorSavesTheMission() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(missionAccessPolicy.canEdit(mission, MissionGate.SEE, caller)).thenReturn(true);

    editor.edit(missionId, MissionGate.SEE, stepAdded());

    assertThat(mutations).hasValue(1);
    assertThat(mission.getStepsVersion()).isEqualTo(3L);
    verify(missionRepository, never()).save(any());
    verify(auditRecorder)
        .record(
            eq(AuditEventType.MISSION_STEP_ADDED),
            eq(missionId),
            eq("Op Nightfall"),
            isNull(),
            sameDetails(AuditDetails.of("count", 1)));
  }

  @Test
  void anUnchangedEditRecordsNoAuditEvent() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(missionAccessPolicy.canEdit(mission, MissionGate.participant(missionId), caller))
        .thenReturn(true);

    editor.edit(missionId, MissionGate.participant(missionId), m -> MissionEdit.unchanged());

    verifyNoInteractions(auditRecorder);
  }
}
