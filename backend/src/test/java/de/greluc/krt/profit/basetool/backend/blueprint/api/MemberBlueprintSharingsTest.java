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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Unit tests of {@link MemberBlueprintSharings}: absent rows, own versions, lock and audit. */
@ExtendWith(MockitoExtension.class)
class MemberBlueprintSharingsTest {

  private static final UUID USER = UUID.randomUUID();

  @Mock private MemberBlueprintSharingRepository repository;
  @Mock private UserRepository userRepository;
  @Mock private AuditRecorder auditRecorder;

  @InjectMocks private MemberBlueprintSharings sharings;

  private static MemberBlueprintSharing row(boolean share, long version) {
    MemberBlueprintSharing row = new MemberBlueprintSharing();
    row.setId(USER);
    row.setShareGlobally(share);
    row.setVersion(version);
    return row;
  }

  @Test
  void aMemberWithoutARowHasNotOptedInAtVersionZero() {
    when(repository.findById(USER)).thenReturn(Optional.empty());

    assertThat(sharings.snapshotOf(USER))
        .isEqualTo(new MemberBlueprintSharings.Snapshot(false, 0L));
  }

  @Test
  void theSharersComeFromTheRepository() {
    UUID other = UUID.randomUUID();
    when(repository.findIdsSharingGlobally()).thenReturn(Set.of(USER, other));

    assertThat(sharings.userIdsSharingGlobally()).containsExactlyInAnyOrder(USER, other);
  }

  @Test
  void switchingOnCreatesTheRowAndRecordsTheAuditEvent() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(MemberBlueprintSharing.class)))
        .thenAnswer(invocation -> {
          MemberBlueprintSharing saved = invocation.getArgument(0);
          saved.setVersion(0L);
          return saved;
        });

    MemberBlueprintSharings.Snapshot result = sharings.update(USER, true, 0L);

    assertThat(result).isEqualTo(new MemberBlueprintSharings.Snapshot(true, 0L));
    verify(auditRecorder)
        .record(eq(AuditEventType.BLUEPRINT_SHARING_CHANGED), isNull(), isNull(), eq(USER), any());
  }

  @Test
  void savingTheSameValueAgainRecordsNothing() {
    MemberBlueprintSharing stored = row(true, 1L);
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(stored));
    when(repository.saveAndFlush(stored)).thenReturn(stored);

    sharings.update(USER, true, 1L);

    verifyNoInteractions(auditRecorder);
  }

  @Test
  void aFirstWriteOfOffRecordsNothingBecauseAbsentMeansOff() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(MemberBlueprintSharing.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    sharings.update(USER, false, null);

    verifyNoInteractions(auditRecorder);
  }

  @Test
  void aStaleVersionIsRefusedAndNothingIsWritten() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(row(false, 3L)));

    assertThatThrownBy(() -> sharings.update(USER, true, 1L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(repository, never()).saveAndFlush(any());
    verifyNoInteractions(auditRecorder);
  }

  @Test
  void anUnknownMemberIsNotFound() {
    when(userRepository.existsById(USER)).thenReturn(false);

    assertThatThrownBy(() -> sharings.update(USER, true, 0L)).isInstanceOf(NotFoundException.class);
    verify(repository, never()).saveAndFlush(any());
  }
}
