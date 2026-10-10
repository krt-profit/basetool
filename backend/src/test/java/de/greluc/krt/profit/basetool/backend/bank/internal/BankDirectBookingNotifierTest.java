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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The direct-booking notices (REQ-BANK-059): a payout reaches the member paid out to, a holder
 * transfer the receiving holder's member, every debit or reversal the account's responsible
 * holders, and nothing else.
 */
@ExtendWith(MockitoExtension.class)
class BankDirectBookingNotifierTest {

  private static final UUID TX = UUID.randomUUID();
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();
  private static final UUID PAYEE = UUID.randomUUID();

  @Mock private BankTransactionRepository transactionRepository;
  @Mock private BankAccountRepository accountRepository;
  @Mock private BankHolderRepository holderRepository;
  @Mock private BankPostingRepository postingRepository;
  @Mock private BankActorResolver actors;
  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private BankDirectBookingNotifier notifier;

  private final BankTransactionDto booked =
      new BankTransactionDto(TX, BankTransactionType.WITHDRAWAL, null, Instant.now());

  @BeforeEach
  void stubCommon() {
    org.mockito.Mockito.lenient().when(actors.current()).thenReturn(new ActorRef(ACTOR, "Ada"));
    BankAccount account = new BankAccount();
    account.setId(ACCOUNT);
    account.setAccountNo("KB-0003");
    org.mockito.Mockito.lenient()
        .when(accountRepository.findById(ACCOUNT))
        .thenReturn(Optional.of(account));
    BankTransaction tx = BankTransaction.builder().id(TX).transferFee(new BigDecimal("40")).build();
    org.mockito.Mockito.lenient()
        .when(transactionRepository.findById(TX))
        .thenReturn(Optional.of(tx));
  }

  private BankWithdrawalRequest withdrawal(UUID counterpartyUserId) {
    return new BankWithdrawalRequest(
        ACCOUNT,
        UUID.randomUUID(),
        new BigDecimal("1000"),
        null,
        null,
        null,
        counterpartyUserId,
        null,
        false,
        null);
  }

  private List<NoticeEvent> published(int expected) {
    ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(expected)).publishEvent(captured.capture());
    return captured.getAllValues().stream().map(NoticeEvent.class::cast).toList();
  }

  @Test
  void aWithdrawalPayingOutToAMemberTellsThemAndTheAccountsResponsibleHolders() {
    notifier.withdrawalBooked(withdrawal(PAYEE), booked);

    List<NoticeEvent> sent = published(2);
    assertThat(sent.get(0).eventType()).isEqualTo(NotificationEventType.BANK_PAYOUT_BOOKED);
    assertThat(sent.get(0).contextRecipientUserId()).isEqualTo(PAYEE);
    assertThat(sent.get(0).actorSub()).isEqualTo(ACTOR);
    assertThat(sent.get(0).renderParams())
        .containsEntry("amount", "1000")
        .containsEntry("fee", "40")
        .containsEntry("accountNo", "KB-0003")
        .containsEntry("actor", "Ada");
    assertThat(sent.get(1).eventType()).isEqualTo(NotificationEventType.BANK_ACCOUNT_DEBITED);
    assertThat(sent.get(1).contextAccountId()).isEqualTo(ACCOUNT);
    assertThat(sent.get(1).renderParams()).containsEntry("debitCode", "WITHDRAWAL");
  }

  @Test
  void aWithdrawalWithoutAMemberCounterpartyOnlyTellsTheResponsibleHolders() {
    notifier.withdrawalBooked(withdrawal(null), booked);

    List<NoticeEvent> sent = published(1);
    assertThat(sent.getFirst().eventType()).isEqualTo(NotificationEventType.BANK_ACCOUNT_DEBITED);
  }

  @Test
  void aTransferTellsTheSourceAccountsResponsibleHolders() {
    BankTransferRequest request =
        new BankTransferRequest(
            ACCOUNT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new BigDecimal("500"),
            null,
            null,
            null,
            false);

    notifier.transferBooked(request, booked);

    List<NoticeEvent> sent = published(1);
    assertThat(sent.getFirst().contextAccountId()).isEqualTo(ACCOUNT);
    assertThat(sent.getFirst().renderParams())
        .containsEntry("debitCode", "TRANSFER")
        .containsEntry("amount", "500")
        .containsEntry("fee", "40");
  }

  @Test
  void aReversalTellsTheResponsibleHoldersOfEveryAccountItMoved() {
    UUID other = UUID.randomUUID();
    when(postingRepository.findLegsByTransactionIds(List.of(TX)))
        .thenReturn(
            List.of(
                new BankCounterLeg(
                    TX, UUID.randomUUID(), ACCOUNT, "KB-0003", "Kasse", new BigDecimal("-700")),
                new BankCounterLeg(
                    TX, UUID.randomUUID(), other, "KB-0004", "Rücklage", new BigDecimal("700"))));

    notifier.reversalBooked(booked);

    List<NoticeEvent> sent = published(2);
    assertThat(sent.get(0).contextAccountId()).isEqualTo(ACCOUNT);
    assertThat(sent.get(0).renderParams())
        .containsEntry("debitCode", "REVERSAL")
        .containsEntry("amount", "700");
    assertThat(sent.get(1).contextAccountId()).isEqualTo(other);
  }

  @Test
  void aHolderTransferTellsTheReceivingHoldersMember() {
    UUID destination = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    User user = new User();
    user.setId(member);
    BankHolder holder = new BankHolder();
    holder.setUser(user);
    when(holderRepository.findById(destination)).thenReturn(Optional.of(holder));

    notifier.holderTransferBooked(
        new BankHolderTransferRequest(UUID.randomUUID(), destination, new BigDecimal("250"), null),
        booked);

    List<NoticeEvent> sent = published(1);
    assertThat(sent.getFirst().eventType())
        .isEqualTo(NotificationEventType.BANK_HOLDER_TRANSFER_BOOKED);
    assertThat(sent.getFirst().contextRecipientUserId()).isEqualTo(member);
    assertThat(sent.getFirst().renderParams())
        .containsEntry("amount", "250")
        .containsEntry("actor", "Ada");
  }

  @Test
  void aHolderTransferToAHolderWithoutAMemberTellsNobody() {
    UUID destination = UUID.randomUUID();
    when(holderRepository.findById(destination)).thenReturn(Optional.of(new BankHolder()));

    notifier.holderTransferBooked(
        new BankHolderTransferRequest(UUID.randomUUID(), destination, new BigDecimal("250"), null),
        booked);

    verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
  }
}
