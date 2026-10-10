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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The deactivated-holder notice goes when a holder's balance reaches zero on a posting
 * (REQ-BANK-060), and only then.
 */
@ExtendWith(MockitoExtension.class)
class BankPostingWriterNoticeTest {

  @Mock private BankAccountRepository accountRepository;
  @Mock private BankHolderRepository holderRepository;
  @Mock private BankTransactionRepository transactionRepository;
  @Mock private BankPostingRepository postingRepository;
  @Mock private BankHolderPostingRepository holderPostingRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private ApplicationEventPublisher eventPublisher;

  private BankPostingWriter writer;
  private BankHolder holder;
  private final BankTransaction tx = BankTransaction.builder().id(UUID.randomUUID()).build();

  @BeforeEach
  void setUp() {
    writer =
        new BankPostingWriter(
            accountRepository,
            holderRepository,
            transactionRepository,
            postingRepository,
            holderPostingRepository,
            authHelperService,
            eventPublisher);
    holder = new BankHolder();
    holder.setId(UUID.randomUUID());
  }

  @Test
  void aPostingThatEmptiesADeactivatedHolderClearsTheirNotice() {
    holder.setActive(false);
    when(holderPostingRepository.holderTotal(holder.getId())).thenReturn(BigDecimal.ZERO);

    writer.persistHolderPosting(tx, holder, new BigDecimal("-1500"), Instant.now());

    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(published.capture());
    NoticeEvent notice = (NoticeEvent) published.getValue();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.BANK_HOLDER_NOTICE_CLEARED);
    assertThat(notice.entityId()).isEqualTo(holder.getId());
  }

  @Test
  void aPostingThatLeavesADeactivatedHolderWithMoneyKeepsTheNotice() {
    holder.setActive(false);
    when(holderPostingRepository.holderTotal(holder.getId())).thenReturn(new BigDecimal("200"));

    writer.persistHolderPosting(tx, holder, new BigDecimal("-1300"), Instant.now());

    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void aPostingOnAnActiveHolderChecksNothing() {
    holder.setActive(true);

    writer.persistHolderPosting(tx, holder, new BigDecimal("-1300"), Instant.now());

    verify(holderPostingRepository, never()).holderTotal(any());
    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }
}
