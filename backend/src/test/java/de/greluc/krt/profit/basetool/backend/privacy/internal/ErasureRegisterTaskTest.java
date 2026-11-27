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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.ErasedAccountRegister;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakService;
import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Unit tests for {@link ErasureRegisterTask} (REQ-DATA-048). */
@ExtendWith(MockitoExtension.class)
class ErasureRegisterTaskTest {

  private static final Instant NOW = Instant.parse("2026-11-27T05:00:00Z");
  private static final Duration MAX_AGE = Duration.ofDays(365);

  @Mock private ErasedAccountRegister register;
  @Mock private ErasureReapplication reapplication;
  @Mock private KeycloakService keycloakService;
  @Mock private ClientDirectory clientDirectory;
  @Mock private AuditRecorder auditRecorder;
  @Mock private TransactionTemplate transactionTemplate;
  @Mock private TaskMetrics taskMetrics;

  private ErasureRegisterTask task;

  @BeforeEach
  void setUp() {
    task =
        new ErasureRegisterTask(
            register,
            reapplication,
            keycloakService,
            clientDirectory,
            new ErasureRegisterProperties(true, MAX_AGE, Duration.ofHours(24)),
            auditRecorder,
            transactionTemplate,
            taskMetrics);
  }

  /** Runs the transaction template's callback inline, as a real template would. */
  private void runTransactionsInline() {
    doAnswer(
            invocation -> {
              Consumer<TransactionStatus> callback = invocation.getArgument(0);
              callback.accept(null);
              return null;
            })
        .when(transactionTemplate)
        .executeWithoutResult(any());
  }

  @Test
  void deletesAReturnedRowAndASurvivingKeycloakUser_andAuditsTheCounts() {
    UUID restored = UUID.randomUUID();
    UUID keycloakOnly = UUID.randomUUID();
    when(register.expireBefore(NOW.minus(MAX_AGE))).thenReturn(1);
    when(register.erasedAccounts()).thenReturn(List.of(restored, keycloakOnly));
    when(reapplication.deleteAccountRow(restored)).thenReturn(true);
    when(reapplication.deleteAccountRow(keycloakOnly)).thenReturn(false);
    when(keycloakService.usernameOf(restored)).thenReturn(Optional.of("alice"));
    when(keycloakService.usernameOf(keycloakOnly)).thenReturn(Optional.of("bob"));
    runTransactionsInline();

    assertThat(task.sweep(NOW)).isEqualTo(4);

    verify(keycloakService).deleteUser(restored);
    verify(keycloakService).deleteUser(keycloakOnly);
    ArgumentCaptor<AuditDetails> details = ArgumentCaptor.forClass(AuditDetails.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.ERASURE_REGISTER_APPLIED),
            isNull(),
            isNull(),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString())
        .startsWith("accountRows=1 keycloakUsers=2 expired=1 cutoff=")
        .doesNotContain(restored.toString());
  }

  @Test
  void neverDeletesTheServiceAccountOfAConfiguredGateway() {
    UUID gateway = UUID.randomUUID();
    when(register.erasedAccounts()).thenReturn(List.of(gateway));
    when(keycloakService.usernameOf(gateway))
        .thenReturn(Optional.of("service-account-basetool-ingest-gateway"));
    when(clientDirectory.isGatewayServiceAccount("service-account-basetool-ingest-gateway"))
        .thenReturn(true);

    assertThat(task.sweep(NOW)).isZero();

    verify(keycloakService, never()).deleteUser(any());
  }

  @Test
  void writesNoAuditRow_whenNothingChanged() {
    UUID gone = UUID.randomUUID();
    when(register.erasedAccounts()).thenReturn(List.of(gone));
    when(keycloakService.usernameOf(gone)).thenReturn(Optional.empty());

    assertThat(task.sweep(NOW)).isZero();

    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void triesEveryIdAndThenFails_whenOneDeletionCannotBeReapplied() {
    UUID failing = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    when(register.erasedAccounts()).thenReturn(List.of(failing, next));
    when(reapplication.deleteAccountRow(failing)).thenThrow(new IllegalStateException("boom"));
    when(reapplication.deleteAccountRow(next)).thenReturn(true);
    when(keycloakService.usernameOf(next)).thenReturn(Optional.empty());
    runTransactionsInline();

    assertThatThrownBy(() -> task.sweep(NOW))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("1 registered deletion");

    verify(reapplication).deleteAccountRow(next);
    verify(auditRecorder)
        .record(eq(AuditEventType.ERASURE_REGISTER_APPLIED), any(), any(), any(), any());
  }
}
