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

package de.greluc.krt.profit.basetool.backend.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.platform.api.NameRetentionParticipant;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** The orchestration of the name retention (REQ-DATA-047). */
class NameRetentionTaskTest {

  private static final Instant NOW = Instant.parse("2029-06-01T00:00:00Z");
  private static final Duration MAX_AGE = Duration.ofDays(1095);
  private static final Instant CUTOFF = NOW.minus(MAX_AGE);

  private final NameRetentionParticipant reasons = participant("approval_reasons");
  private final NameRetentionParticipant names = participant("external_names");
  private final AuditRecorder auditRecorder = mock(AuditRecorder.class);
  private final TransactionTemplate transactionTemplate = runningTemplate();

  @Test
  void asksEveryAreaWithTheSameCutoffAndAuditsTheCounts() {
    when(reasons.removeNamesBefore(CUTOFF)).thenReturn(2);
    when(names.removeNamesBefore(CUTOFF)).thenReturn(3);

    assertThat(task().sweep(NOW)).isEqualTo(5);

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.NAME_RETENTION_APPLIED),
            isNull(),
            isNull(),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString())
        .contains("approval_reasons=2", "external_names=3", "cutoff=" + CUTOFF);
  }

  @Test
  void anIdleRunLeavesNoAuditRow() {
    assertThat(task().sweep(NOW)).isZero();

    verifyNoInteractions(auditRecorder);
  }

  @Test
  void aFailingAreaFailsTheRunAfterTheOthersRan() {
    when(reasons.removeNamesBefore(CUTOFF)).thenThrow(new IllegalStateException("boom"));
    when(names.removeNamesBefore(CUTOFF)).thenReturn(1);

    assertThatThrownBy(() -> task().sweep(NOW))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approval_reasons");

    verify(names).removeNamesBefore(CUTOFF);
    verify(auditRecorder)
        .record(eq(AuditEventType.NAME_RETENTION_APPLIED), any(), any(), any(), any());
  }

  private NameRetentionTask task() {
    return new NameRetentionTask(
        List.of(reasons, names),
        new NameRetentionProperties(true, MAX_AGE, Duration.ofHours(24)),
        auditRecorder,
        transactionTemplate,
        mock(TaskMetrics.class));
  }

  private static NameRetentionParticipant participant(String area) {
    NameRetentionParticipant participant = mock(NameRetentionParticipant.class);
    when(participant.area()).thenReturn(area);
    return participant;
  }

  @SuppressWarnings("unchecked")
  private static TransactionTemplate runningTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    doAnswer(
            invocation -> {
              invocation.<Consumer<TransactionStatus>>getArgument(0).accept(null);
              return null;
            })
        .when(template)
        .executeWithoutResult(any(Consumer.class));
    return template;
  }
}
