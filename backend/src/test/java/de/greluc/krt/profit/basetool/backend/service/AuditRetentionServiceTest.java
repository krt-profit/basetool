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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.AuditDomain;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link AuditRetentionService} (REQ-AUDIT-006).
 *
 * <p>Covers that every activity domain and the bank trail are swept, a domain with nothing old
 * enough is skipped, and one failing domain does not abort the run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditRetentionServiceTest {

  private static final Instant CUTOFF = Instant.now().minus(730, ChronoUnit.DAYS);

  @Mock private AuditService auditService;
  @Mock private BankAuditService bankAuditService;
  @Mock private AuditEventRepository auditEventRepository;
  @Mock private BankAuditEventRepository bankAuditEventRepository;

  @InjectMocks private AuditRetentionService service;

  @Test
  void purgesEveryActivityDomainAndTheBankTrail() {
    when(auditEventRepository.existsByDomainAndOccurredAtBefore(any(), eq(CUTOFF)))
        .thenReturn(true);
    when(auditService.purgeBefore(any(), eq(CUTOFF))).thenReturn(2);
    when(bankAuditEventRepository.existsByOccurredAtBefore(CUTOFF)).thenReturn(true);
    when(bankAuditService.purgeBefore(CUTOFF)).thenReturn(5);

    int deleted = service.purgeOlderThan(CUTOFF);

    for (AuditDomain domain : AuditDomain.values()) {
      verify(auditService).purgeBefore(domain, CUTOFF);
    }
    verify(bankAuditService).purgeBefore(CUTOFF);
    assertThat(deleted).isEqualTo(AuditDomain.values().length * 2 + 5);
  }

  @Test
  void skipsDomainsThatHoldNothingOlderThanTheCutoff() {
    when(auditEventRepository.existsByDomainAndOccurredAtBefore(any(), eq(CUTOFF)))
        .thenReturn(false);
    when(auditEventRepository.existsByDomainAndOccurredAtBefore(AuditDomain.INVENTORY, CUTOFF))
        .thenReturn(true);
    when(auditService.purgeBefore(AuditDomain.INVENTORY, CUTOFF)).thenReturn(3);
    when(bankAuditEventRepository.existsByOccurredAtBefore(CUTOFF)).thenReturn(false);

    int deleted = service.purgeOlderThan(CUTOFF);

    verify(auditService).purgeBefore(AuditDomain.INVENTORY, CUTOFF);
    verify(auditService, never()).purgeBefore(AuditDomain.JOB_ORDER, CUTOFF);
    verify(bankAuditService, never()).purgeBefore(any());
    assertThat(deleted).isEqualTo(3);
  }

  @Test
  void continuesAfterADomainThatCannotBePurged() {
    when(auditEventRepository.existsByDomainAndOccurredAtBefore(any(), eq(CUTOFF)))
        .thenReturn(true);
    when(auditService.purgeBefore(any(), eq(CUTOFF))).thenReturn(1);
    when(auditService.purgeBefore(AuditDomain.INVENTORY, CUTOFF))
        .thenThrow(new RuntimeException("deadlock"));
    when(bankAuditEventRepository.existsByOccurredAtBefore(CUTOFF)).thenReturn(true);
    when(bankAuditService.purgeBefore(CUTOFF)).thenReturn(4);

    int deleted = service.purgeOlderThan(CUTOFF);

    for (AuditDomain domain : AuditDomain.values()) {
      verify(auditService).purgeBefore(domain, CUTOFF);
    }
    verify(bankAuditService).purgeBefore(CUTOFF);
    assertThat(deleted).isEqualTo(AuditDomain.values().length - 1 + 4);
  }

  @Test
  void continuesAfterABankTrailThatCannotBePurged() {
    when(auditEventRepository.existsByDomainAndOccurredAtBefore(any(), eq(CUTOFF)))
        .thenReturn(true);
    when(auditService.purgeBefore(any(), eq(CUTOFF))).thenReturn(1);
    when(bankAuditEventRepository.existsByOccurredAtBefore(CUTOFF)).thenReturn(true);
    when(bankAuditService.purgeBefore(CUTOFF)).thenThrow(new RuntimeException("db down"));

    int deleted = service.purgeOlderThan(CUTOFF);

    assertThat(deleted).isEqualTo(AuditDomain.values().length);
  }
}
