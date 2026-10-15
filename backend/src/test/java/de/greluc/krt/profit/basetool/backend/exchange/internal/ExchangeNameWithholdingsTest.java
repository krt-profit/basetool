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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests of {@link ExchangeNameWithholdings}: the off default, own versions, lock and audit.
 */
@ExtendWith(MockitoExtension.class)
class ExchangeNameWithholdingsTest {

  private static final UUID USER = UUID.randomUUID();

  @Mock private ExchangeNameWithholdingRepository repository;
  @Mock private UserRepository userRepository;
  @Mock private AuditRecorder auditRecorder;

  @InjectMocks private ExchangeNameWithholdings withholdings;

  private static ExchangeNameWithholding row(boolean withhold, long version) {
    ExchangeNameWithholding row = new ExchangeNameWithholding();
    row.setId(USER);
    row.setWithholdName(withhold);
    row.setVersion(version);
    return row;
  }

  @Test
  void aMemberWithoutARowSharesTheirNameAtVersionZero() {
    when(repository.findById(USER)).thenReturn(Optional.empty());

    assertThat(withholdings.snapshotOf(USER))
        .isEqualTo(new ExchangeNameWithholdings.Snapshot(false, 0L));
  }

  @Test
  void aStoredRowIsReadWithItsVersion() {
    when(repository.findById(USER)).thenReturn(Optional.of(row(true, 4L)));

    assertThat(withholdings.snapshotOf(USER))
        .isEqualTo(new ExchangeNameWithholdings.Snapshot(true, 4L));
  }

  @Test
  void switchingOnCreatesTheRowAndRecordsTheAuditEvent() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(ExchangeNameWithholding.class)))
        .thenAnswer(
            invocation -> {
              ExchangeNameWithholding saved = invocation.getArgument(0);
              saved.setVersion(0L);
              return saved;
            });

    ExchangeNameWithholdings.Snapshot result = withholdings.update(USER, true, 0L);

    assertThat(result).isEqualTo(new ExchangeNameWithholdings.Snapshot(true, 0L));
    verify(auditRecorder)
        .record(
            eq(AuditEventType.EXCHANGE_NAME_WITHHOLDING_CHANGED),
            isNull(),
            isNull(),
            eq(USER),
            any());
  }

  @Test
  void switchingOffAgainRecordsTheAuditEvent() {
    ExchangeNameWithholding stored = row(true, 2L);
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(stored));
    when(repository.saveAndFlush(stored)).thenReturn(stored);

    ExchangeNameWithholdings.Snapshot result = withholdings.update(USER, false, 2L);

    assertThat(result.withholdName()).isFalse();
    verify(auditRecorder)
        .record(
            eq(AuditEventType.EXCHANGE_NAME_WITHHOLDING_CHANGED),
            isNull(),
            isNull(),
            eq(USER),
            any());
  }

  @Test
  void savingTheSameValueAgainRecordsNothing() {
    ExchangeNameWithholding stored = row(true, 1L);
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(stored));
    when(repository.saveAndFlush(stored)).thenReturn(stored);

    withholdings.update(USER, true, 1L);

    verifyNoInteractions(auditRecorder);
  }

  @Test
  void aFirstWriteOfOffRecordsNothingBecauseAbsentMeansOff() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(ExchangeNameWithholding.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    withholdings.update(USER, false, null);

    verifyNoInteractions(auditRecorder);
  }

  @Test
  void aStaleVersionIsRefusedAndNothingIsWritten() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(row(false, 3L)));

    assertThatThrownBy(() -> withholdings.update(USER, true, 1L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(repository, never()).saveAndFlush(any());
    verifyNoInteractions(auditRecorder);
  }

  @Test
  void anUnknownMemberIsNotFound() {
    when(userRepository.existsById(USER)).thenReturn(false);

    assertThatThrownBy(() -> withholdings.update(USER, true, 0L))
        .isInstanceOf(NotFoundException.class);
    verify(repository, never()).saveAndFlush(any());
  }
}
