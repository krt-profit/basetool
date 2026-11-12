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

package de.greluc.krt.profit.basetool.backend.scope.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopeMetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests of {@link ActiveOrgUnitChoices} (REQ-ORG-031): the stored choice reads back only while
 * the caller may select it, a change is stored and counted once by its target, and a refused, stale
 * or unchanged write stores and counts nothing.
 */
@ExtendWith(MockitoExtension.class)
class ActiveOrgUnitChoicesTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID HEL = UUID.randomUUID();
  private static final UUID ARGON = UUID.randomUUID();

  @Mock private MemberActiveOrgUnitRepository repository;
  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private AuthHelperService authHelper;

  /** Stands in for the bean's proxy; each attempt calls straight into the instance under test. */
  @Mock private ObjectProvider<ActiveOrgUnitChoices> self;

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private ActiveOrgUnitChoices choices;

  @BeforeEach
  void setUp() {
    ActiveOrgUnitChoiceMetrics metrics = new ActiveOrgUnitChoiceMetrics(registry);
    metrics.registerWriteCounters();
    choices = new ActiveOrgUnitChoices(repository, requestScopeResolver, authHelper, metrics, self);
    lenient().when(self.getObject()).thenReturn(choices);
  }

  private static MemberActiveOrgUnit row(UUID orgUnitId, long version) {
    MemberActiveOrgUnit row = new MemberActiveOrgUnit();
    row.setId(USER);
    row.setOrgUnitId(orgUnitId);
    row.setVersion(version);
    return row;
  }

  private void callerIsTheUser() {
    when(authHelper.currentUserId()).thenReturn(Optional.of(USER));
  }

  private void savesAtVersion(long version) {
    when(repository.saveAndFlush(any(MemberActiveOrgUnit.class)))
        .thenAnswer(invocation -> {
          MemberActiveOrgUnit saved = invocation.getArgument(0);
          saved.setVersion(version);
          return saved;
        });
  }

  private double writes(String target) {
    return registry
        .get(ScopeMetricNames.ACTIVE_ORG_UNIT_CHOICE_WRITES)
        .tag(ScopeMetricNames.TAG_TARGET, target)
        .counter()
        .count();
  }

  private void assertNothingCounted() {
    assertThat(writes(ScopeMetricNames.TARGET_UNIT)).isZero();
    assertThat(writes(ScopeMetricNames.TARGET_ALL)).isZero();
  }

  @Test
  void aMemberWithoutARowHasChosenAllOrgUnitsAtVersionZero() {
    callerIsTheUser();
    when(repository.findById(USER)).thenReturn(Optional.empty());

    assertThat(choices.currentChoice()).isEqualTo(new ActiveOrgUnitChoices.Snapshot(null, 0L));
  }

  @Test
  void aStoredUnitTheCallerMaySelectReadsBack() {
    callerIsTheUser();
    when(repository.findById(USER)).thenReturn(Optional.of(row(HEL, 2L)));
    when(requestScopeResolver.isSelectable(HEL)).thenReturn(true);

    assertThat(choices.currentChoice()).isEqualTo(new ActiveOrgUnitChoices.Snapshot(HEL, 2L));
  }

  @Test
  void aStoredUnitTheCallerMayNoLongerSelectReadsAsAllOrgUnits() {
    callerIsTheUser();
    when(repository.findById(USER)).thenReturn(Optional.of(row(ARGON, 4L)));
    when(requestScopeResolver.isSelectable(ARGON)).thenReturn(false);

    assertThat(choices.currentChoice()).isEqualTo(new ActiveOrgUnitChoices.Snapshot(null, 4L));
  }

  @Test
  void aFirstChoiceOfOneUnitIsStoredAndCountedAsAUnitChoice() {
    callerIsTheUser();
    when(requestScopeResolver.isSelectable(ARGON)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.empty());
    savesAtVersion(0L);

    ActiveOrgUnitChoices.Snapshot result = choices.choose(ARGON, null);

    assertThat(result).isEqualTo(new ActiveOrgUnitChoices.Snapshot(ARGON, 0L));
    assertThat(writes(ScopeMetricNames.TARGET_UNIT)).isEqualTo(1);
    assertThat(writes(ScopeMetricNames.TARGET_ALL)).isZero();
  }

  @Test
  void switchingBackToAllOrgUnitsIsStoredAndCountedAsAnAllChoice() {
    callerIsTheUser();
    when(repository.findById(USER)).thenReturn(Optional.of(row(HEL, 3L)));
    savesAtVersion(4L);

    ActiveOrgUnitChoices.Snapshot result = choices.choose(null, null);

    assertThat(result).isEqualTo(new ActiveOrgUnitChoices.Snapshot(null, 4L));
    assertThat(writes(ScopeMetricNames.TARGET_ALL)).isEqualTo(1);
    assertThat(writes(ScopeMetricNames.TARGET_UNIT)).isZero();
  }

  @Test
  void choosingTheStoredUnitAgainWritesAndCountsNothing() {
    callerIsTheUser();
    when(requestScopeResolver.isSelectable(HEL)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(row(HEL, 5L)));

    assertThat(choices.choose(HEL, null)).isEqualTo(new ActiveOrgUnitChoices.Snapshot(HEL, 5L));
    verify(repository, never()).saveAndFlush(any());
    assertNothingCounted();
  }

  @Test
  void choosingAllOrgUnitsWithoutARowWritesAndCountsNothing() {
    callerIsTheUser();
    when(repository.findById(USER)).thenReturn(Optional.empty());

    assertThat(choices.choose(null, null)).isEqualTo(new ActiveOrgUnitChoices.Snapshot(null, 0L));
    verify(repository, never()).saveAndFlush(any());
    assertNothingCounted();
  }

  @Test
  void aUnitTheCallerMayNotSelectIsRefusedAndNothingIsWrittenOrCounted() {
    callerIsTheUser();
    when(requestScopeResolver.isSelectable(ARGON)).thenReturn(false);

    assertThatThrownBy(() -> choices.choose(ARGON, null)).isInstanceOf(BadRequestException.class);
    verify(repository, never()).saveAndFlush(any());
    assertNothingCounted();
  }

  @Test
  void aStaleVersionIsRefusedAndNothingIsWrittenOrCounted() {
    callerIsTheUser();
    when(requestScopeResolver.isSelectable(ARGON)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(row(HEL, 3L)));

    assertThatThrownBy(() -> choices.choose(ARGON, 1L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(repository, never()).saveAndFlush(any());
    assertNothingCounted();
  }

  @Test
  void aSwitchThatLosesTheFirstInsertIsRetriedAndTheLatestChoiceWins() {
    callerIsTheUser();
    when(requestScopeResolver.isSelectable(ARGON)).thenReturn(true);
    when(repository.findById(USER))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(row(HEL, 0L)));
    when(repository.saveAndFlush(any(MemberActiveOrgUnit.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate member_active_org_unit"))
        .thenAnswer(invocation -> {
          MemberActiveOrgUnit saved = invocation.getArgument(0);
          saved.setVersion(1L);
          return saved;
        });

    assertThat(choices.choose(ARGON, null)).isEqualTo(new ActiveOrgUnitChoices.Snapshot(ARGON, 1L));
    assertThat(writes(ScopeMetricNames.TARGET_UNIT)).isEqualTo(1);
  }

  @Test
  void aRaceThatOutlastsEveryAttemptAnswersAConflictAndCountsNothing() {
    callerIsTheUser();
    when(requestScopeResolver.isSelectable(ARGON)).thenReturn(true);
    when(repository.findById(USER)).thenAnswer(invocation -> Optional.of(row(HEL, 2L)));
    when(repository.saveAndFlush(any(MemberActiveOrgUnit.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(MemberActiveOrgUnit.class, USER));

    assertThatThrownBy(() -> choices.choose(ARGON, null))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(repository, times(5)).saveAndFlush(any(MemberActiveOrgUnit.class));
    assertNothingCounted();
  }

  @Test
  void aCallerWithoutAnAccountIsNotFound() {
    when(authHelper.currentUserId()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> choices.choose(null, null)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> choices.currentChoice()).isInstanceOf(NotFoundException.class);
    verifyNoInteractions(repository);
    assertNothingCounted();
  }
}
