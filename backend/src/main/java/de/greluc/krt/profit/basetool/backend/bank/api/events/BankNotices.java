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

package de.greluc.krt.profit.basetool.backend.bank.api.events;

import de.greluc.krt.profit.basetool.backend.bank.api.BankAmounts;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The bank notices beyond the booking-request ones (REQ-BANK-057…060), built from scalars so they
 * can be delivered after the commit.
 */
public final class BankNotices {

  /** Loose entity-type tag of the notices about one account grant; the entity id is the account. */
  public static final String GRANT_ENTITY_TYPE = "BANK_ACCOUNT_GRANT";

  /** Loose entity-type tag of the notices about one booked transaction. */
  public static final String TRANSACTION_ENTITY_TYPE = "BANK_TRANSACTION";

  /** Loose entity-type tag of the notices about one bank holder. */
  public static final String HOLDER_ENTITY_TYPE = "BANK_HOLDER";

  private static final Set<NotificationType> GRANT_NOTICES =
      Set.of(NotificationType.BANK_GRANT_CHANGED, NotificationType.BANK_GRANT_REVOKED);

  private BankNotices() {}

  private static String yesNo(boolean value) {
    return value ? "YES" : "NO";
  }

  /**
   * An over-limit booking request was approved and is ready to be confirmed.
   *
   * @param requestId the request
   * @param accountId the request's account, the {@code ACCOUNT_GRANT} selector input
   * @param requesterId the member who raised the request
   * @param type deposit, withdrawal or transfer
   * @param amount the requested whole-aUEC amount
   * @param accountNo the account's number
   * @param approver the approving member's name
   * @param actorSub the approving member, kept out of the recipients
   * @return the event
   */
  @NotNull
  public static NoticeEvent approved(
      @NotNull UUID requestId,
      @NotNull UUID accountId,
      @Nullable UUID requesterId,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount,
      @NotNull String accountNo,
      @NotNull String approver,
      @Nullable UUID actorSub) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("type", type.name());
    params.put("amount", BankAmounts.plain(amount));
    params.put("accountNo", accountNo);
    params.put("approver", approver);
    return NoticeEvent.builder()
        .eventType(NotificationEventType.BANK_BOOKING_REQUEST_APPROVED)
        .actorSub(actorSub)
        .entityType(BankBookingRequestEvent.ENTITY_TYPE)
        .entityId(requestId)
        .contextAccountId(accountId)
        .contextRecipientUserId(requesterId)
        .renderParams(params)
        .resolvesNotificationTypes(Set.of(NotificationType.BANK_BOOKING_REQUEST_APPROVED))
        .build();
  }

  /**
   * The approval of a booking request was revoked; its ready-to-confirm notices go.
   *
   * @param requestId the request
   * @return the event
   */
  @NotNull
  public static NoticeEvent approvalRevoked(@NotNull UUID requestId) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.BANK_BOOKING_REQUEST_APPROVAL_REVOKED)
        .entityType(BankBookingRequestEvent.ENTITY_TYPE)
        .entityId(requestId)
        .resolvesNotificationTypes(Set.of(NotificationType.BANK_BOOKING_REQUEST_APPROVED))
        .build();
  }

  /**
   * A bank employee was granted access to an account, or the grant's rights changed; the grantee's
   * earlier grant notices for the account go.
   *
   * @param accountId the account
   * @param accountNo the account's number
   * @param granteeId the grantee
   * @param created whether the grant is new
   * @param canDeposit whether the grantee may deposit
   * @param canWithdraw whether the grantee may withdraw
   * @param canTransfer whether the grantee may transfer
   * @param actorSub the member who changed the grant, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent grantChanged(
      @NotNull UUID accountId,
      @NotNull String accountNo,
      @NotNull UUID granteeId,
      boolean created,
      boolean canDeposit,
      boolean canWithdraw,
      boolean canTransfer,
      @Nullable UUID actorSub) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("accountNo", accountNo);
    params.put("grantChangeCode", created ? "CREATED" : "UPDATED");
    params.put("canDepositCode", yesNo(canDeposit));
    params.put("canWithdrawCode", yesNo(canWithdraw));
    params.put("canTransferCode", yesNo(canTransfer));
    return grantEvent(
        NotificationEventType.BANK_GRANT_CHANGED, accountId, granteeId, actorSub, params);
  }

  /**
   * A bank employee's access to an account was withdrawn; the grantee's earlier grant notices for
   * the account go.
   *
   * @param accountId the account
   * @param accountNo the account's number
   * @param granteeId the former grantee
   * @param actor who withdrew it
   * @return the event
   */
  @NotNull
  public static NoticeEvent grantRevoked(
      @NotNull UUID accountId,
      @NotNull String accountNo,
      @NotNull UUID granteeId,
      @NotNull ActorRef actor) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("accountNo", accountNo);
    params.put("actor", actor.name());
    return grantEvent(
        NotificationEventType.BANK_GRANT_REVOKED, accountId, granteeId, actor.id(), params);
  }

  private static NoticeEvent grantEvent(
      NotificationEventType type,
      UUID accountId,
      UUID granteeId,
      @Nullable UUID actorSub,
      Map<String, String> params) {
    return NoticeEvent.builder()
        .eventType(type)
        .actorSub(actorSub)
        .entityType(GRANT_ENTITY_TYPE)
        .entityId(accountId)
        .contextRecipientUserId(granteeId)
        .renderParams(params)
        .supersedeRecipients(Set.of(granteeId))
        .resolvesNotificationTypesForRecipients(GRANT_NOTICES)
        .build();
  }

  /**
   * Bank staff booked a withdrawal that pays out to a member.
   *
   * @param transactionId the booked transaction
   * @param recipientId the member paid out to
   * @param amount the amount
   * @param fee the transfer fee
   * @param accountNo the debited account's number
   * @param actor the booking staff member
   * @return the event
   */
  @NotNull
  public static NoticeEvent payoutBooked(
      @NotNull UUID transactionId,
      @NotNull UUID recipientId,
      @NotNull BigDecimal amount,
      @NotNull BigDecimal fee,
      @NotNull String accountNo,
      @NotNull ActorRef actor) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("amount", BankAmounts.plain(amount));
    params.put("fee", BankAmounts.plain(fee));
    params.put("accountNo", accountNo);
    params.put("actor", actor.name());
    return NoticeEvent.builder()
        .eventType(NotificationEventType.BANK_PAYOUT_BOOKED)
        .actorSub(actor.id())
        .entityType(TRANSACTION_ENTITY_TYPE)
        .entityId(transactionId)
        .contextRecipientUserId(recipientId)
        .renderParams(params)
        .build();
  }

  /**
   * Bank staff moved aUEC to a holder, whose member must take the money over.
   *
   * @param transactionId the booked transaction
   * @param recipientId the receiving holder's member
   * @param amount the amount
   * @param actor the booking staff member
   * @return the event
   */
  @NotNull
  public static NoticeEvent holderTransferBooked(
      @NotNull UUID transactionId,
      @NotNull UUID recipientId,
      @NotNull BigDecimal amount,
      @NotNull ActorRef actor) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("amount", BankAmounts.plain(amount));
    params.put("actor", actor.name());
    return NoticeEvent.builder()
        .eventType(NotificationEventType.BANK_HOLDER_TRANSFER_BOOKED)
        .actorSub(actor.id())
        .entityType(TRANSACTION_ENTITY_TYPE)
        .entityId(transactionId)
        .contextRecipientUserId(recipientId)
        .renderParams(params)
        .build();
  }

  /**
   * Bank staff debited an account directly or reversed a booking on it.
   *
   * @param transactionId the booked transaction
   * @param accountId the account, the {@code ACCOUNT_RESPONSIBLE} selector input
   * @param accountNo the account's number
   * @param debitCode {@code WITHDRAWAL}, {@code TRANSFER} or {@code REVERSAL}
   * @param amount the amount
   * @param fee the transfer fee
   * @param actor the booking staff member
   * @return the event
   */
  @NotNull
  public static NoticeEvent accountDebited(
      @NotNull UUID transactionId,
      @NotNull UUID accountId,
      @NotNull String accountNo,
      @NotNull String debitCode,
      @NotNull BigDecimal amount,
      @NotNull BigDecimal fee,
      @NotNull ActorRef actor) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("accountNo", accountNo);
    params.put("debitCode", debitCode);
    params.put("amount", BankAmounts.plain(amount));
    params.put("fee", BankAmounts.plain(fee));
    params.put("actor", actor.name());
    return NoticeEvent.builder()
        .eventType(NotificationEventType.BANK_ACCOUNT_DEBITED)
        .actorSub(actor.id())
        .entityType(TRANSACTION_ENTITY_TYPE)
        .entityId(transactionId)
        .contextAccountId(accountId)
        .renderParams(params)
        .build();
  }

  /**
   * A holder was deactivated but still holds aUEC.
   *
   * @param holderId the holder
   * @param handle the holder's handle
   * @param balance the holder's remaining balance
   * @return the event
   */
  @NotNull
  public static NoticeEvent holderDeactivatedWithBalance(
      @NotNull UUID holderId, @NotNull String handle, @NotNull BigDecimal balance) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("holder", handle);
    params.put("balance", BankAmounts.plain(balance));
    return NoticeEvent.builder()
        .eventType(NotificationEventType.BANK_HOLDER_DEACTIVATED_WITH_BALANCE)
        .entityType(HOLDER_ENTITY_TYPE)
        .entityId(holderId)
        .renderParams(params)
        .resolvesNotificationTypes(Set.of(NotificationType.BANK_HOLDER_DEACTIVATED_WITH_BALANCE))
        .build();
  }

  /**
   * A deactivated holder was reactivated or their balance reached zero; their notice goes.
   *
   * @param holderId the holder
   * @return the event
   */
  @NotNull
  public static NoticeEvent holderNoticeCleared(@NotNull UUID holderId) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.BANK_HOLDER_NOTICE_CLEARED)
        .entityType(HOLDER_ENTITY_TYPE)
        .entityId(holderId)
        .resolvesNotificationTypes(Set.of(NotificationType.BANK_HOLDER_DEACTIVATED_WITH_BALANCE))
        .build();
  }
}
