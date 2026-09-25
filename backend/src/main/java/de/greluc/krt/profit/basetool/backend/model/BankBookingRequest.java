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

package de.greluc.krt.profit.basetool.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/**
 * A confirm-before-post deposit, withdrawal or transfer request against a bank account
 * (REQ-BANK-022, REQ-BANK-041).
 *
 * <p>A mutable, off-ledger aggregate: it moves no money while {@code PENDING}. Only on confirmation
 * does it name the {@link #holder}, book a real {@link BankTransaction} and link it in {@link
 * #resultingTransaction}; any other status carries neither. {@link #account} and {@link #type} are
 * immutable; the requester may correct the remaining request fields while it is pending and not yet
 * owner-approved. Requester, decider and approver are loose {@code app_user} ids plus a name
 * snapshot, so the row survives user deletion.
 */
@Entity
@Table(name = "bank_booking_request")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class BankBookingRequest extends AbstractEntity<UUID> {

  /** Surrogate primary key, generated client-side by Hibernate ({@code GenerationType.UUID}). */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * The (source) account the request targets; immutable. An {@code ORG_UNIT}, {@code AREA} or
   * {@code CARTEL} account (REQ-BANK-039/-040).
   */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false, updatable = false)
  @ToString.Exclude
  private BankAccount account;

  /**
   * The destination account for a {@code TRANSFER} request (REQ-BANK-040); {@code null} for {@code
   * DEPOSIT} / {@code WITHDRAWAL}. Immutable; any active account may be chosen.
   */
  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "target_account_id")
  @ToString.Exclude
  private BankAccount targetAccount;

  /** Whether the request is a deposit, a withdrawal or a transfer; immutable. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16, updatable = false)
  private BankBookingRequestType type;

  /**
   * The requested whole-aUEC amount, strictly positive. Mutable while the request is {@code
   * PENDING} and unapproved (REQ-BANK-056) — an edit re-derives {@link #requiresOwnerApproval} from
   * it, so raising the amount past the requester's limit re-arms the approval gate rather than
   * slipping through on the original snapshot.
   */
  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  /**
   * Optional free-text note supplied by the requester; carried onto the booking on confirmation.
   */
  @Nullable
  @Column(length = 500)
  private String note;

  /**
   * Optional free-text justification (Begr&uuml;ndung) supplied by the requester, carried onto the
   * booking on confirmation (REQ-BANK-045). Captured only for a {@code WITHDRAWAL} / {@code
   * TRANSFER} and required when the source account type {@linkplain
   * BankAccountType#requiresDebitJustification() mandates a reason}; {@code null} otherwise.
   */
  @Nullable
  @Column(length = 500)
  private String justification;

  /**
   * The confirming bank employee's own note (REQ-BANK-054), written at confirmation and copied onto
   * {@link BankTransaction#getStaffNote()} of the resulting booking.
   *
   * <p>{@code null} while pending, on a rejected or cancelled request, and when none was recorded.
   */
  @Nullable
  @Column(length = 500)
  private String staffNote;

  /**
   * The payout recipient (Empf&auml;nger) named on a {@code WITHDRAWAL} request (REQ-BANK-055), as
   * opposed to the {@link #holder} who hands the money over.
   *
   * <p>At confirmation it overrides the requester-derived counterparty; {@code null} means the
   * requester and is always the case for deposits and transfers. Loose reference ({@code ON DELETE
   * SET NULL}); {@link #counterpartyHandle} keeps the row attributable.
   */
  @Nullable
  @Column(name = "counterparty_user_id")
  private UUID counterpartyUserId;

  /**
   * Deletion-proof handle snapshot of {@link #counterpartyUserId}, taken when the request is
   * raised. {@code null} exactly when {@link #counterpartyUserId} is {@code null}.
   */
  @Nullable
  @Column(name = "counterparty_handle", length = 255)
  private String counterpartyHandle;

  /**
   * The org unit of the named Empf&auml;nger, chosen at request time from <em>that user's</em>
   * direct memberships across all four kinds and validated server-side against them (a foreign unit
   * is a 400, mirroring the bank employee's path). {@code null} when no counterparty is named or
   * the requester left the unit blank.
   */
  @Nullable
  @Column(name = "counterparty_org_unit_id")
  private UUID counterpartyOrgUnitId;

  /**
   * Deletion-proof name snapshot of {@link #counterpartyOrgUnitId}, so the request list and the
   * booking it produces label the unit without a live polymorphic org-unit load. {@code null}
   * exactly when {@link #counterpartyOrgUnitId} is {@code null} (V232 CHECK).
   */
  @Nullable
  @Column(name = "counterparty_org_unit_name", length = 255)
  private String counterpartyOrgUnitName;

  /**
   * Snapshot at creation (REQ-BANK-043): whether this {@code DEPOSIT} request distributes {@link
   * #splitPercent} of the gross evenly across all active squadron accounts on confirmation (the
   * named account is credited the remainder). DEPOSIT-only and immutable; always {@code false} for
   * a withdrawal/transfer.
   */
  @Column(name = "split_enabled", nullable = false, updatable = false)
  private boolean splitEnabled = false;

  /**
   * The whole-percent (1–100) of the gross distributed across squadron accounts on confirmation
   * (REQ-BANK-043); {@code null} unless {@link #splitEnabled}. Snapshotted at creation — the
   * concrete per-account legs are resolved against the squadron-account set active at confirmation.
   * Immutable.
   */
  @Nullable
  @Column(name = "split_percent", precision = 5, scale = 2, updatable = false)
  private BigDecimal splitPercent;

  /** Lifecycle state; starts {@code PENDING} and reaches exactly one terminal state. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private BankBookingRequestStatus status = BankBookingRequestStatus.PENDING;

  /**
   * The requesting officer/lead's {@code app_user} id (JWT {@code sub}); plain UUID with an {@code
   * ON DELETE SET NULL} FK so the request outlives the requester. Drives per-user isolation of the
   * "my requests" list and the cancel-own-request check (REQ-BANK-022).
   */
  @Nullable
  @Column(name = "requested_by", updatable = false)
  private UUID requestedBy;

  /** Denormalized effective-name snapshot of the requester; authoritative after user deletion. */
  @Column(name = "requester_handle", nullable = false, updatable = false)
  private String requesterHandle;

  /**
   * The holder recorded by the bank employee at confirmation (deposit → the player who received the
   * money; withdrawal → the player who paid it out); {@code null} until {@code CONFIRMED}.
   */
  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "holder_id")
  @ToString.Exclude
  private BankHolder holder;

  /** The ledger transaction booked on confirmation; {@code null} until {@code CONFIRMED}. */
  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "resulting_transaction_id")
  @ToString.Exclude
  private BankTransaction resultingTransaction;

  /**
   * The deciding bank employee's {@code app_user} id; {@code null} for pending/cancelled requests.
   */
  @Nullable
  @Column(name = "decided_by")
  private UUID decidedBy;

  /**
   * Denormalized effective-name snapshot of the deciding bank employee; {@code null} until decided.
   */
  @Nullable
  @Column(name = "decider_handle")
  private String deciderHandle;

  /**
   * When the request reached its terminal state (confirmed/rejected/cancelled); {@code null} while
   * pending.
   */
  @Nullable
  @Column(name = "decided_at")
  private Instant decidedAt;

  /** The bank employee's reason when the request was {@code REJECTED}; {@code null} otherwise. */
  @Nullable
  @Column(name = "reject_reason", length = 500)
  private String rejectReason;

  /**
   * Snapshot at creation (REQ-BANK-041): {@code true} iff the requested amount exceeded the
   * requester's applicable approval limit, so the bank employee's confirmation requires the
   * explicit "approval by the responsible holder obtained" checkbox. Immutable.
   */
  @Column(name = "requires_owner_approval", nullable = false)
  private boolean requiresOwnerApproval = false;

  /**
   * The requester's resolved approval limit at creation (REQ-BANK-041); {@code null} = unlimited
   * (no approval needed). Kept for display/audit. Immutable.
   */
  @Nullable
  @Column(name = "applicable_limit", precision = 19, scale = 4)
  private BigDecimal applicableLimit;

  /**
   * The class of approver that must approve this request before a bank employee may confirm it,
   * snapshotted at creation (REQ-BANK-046). {@code null} unless {@link #requiresOwnerApproval} is
   * set; immutable.
   */
  @Nullable
  @Enumerated(EnumType.STRING)
  @Column(name = "required_approver", length = 32)
  private BankRequestApprover requiredApprover;

  /**
   * {@code true} once the account's responsible holder granted approval in-app from the "Fremde
   * Anträge" tab (REQ-BANK-041); pre-fills the bank employee's confirmation checkbox. Meaningful
   * only when {@link #requiresOwnerApproval} is set.
   */
  @Column(name = "owner_approval_granted", nullable = false)
  private boolean ownerApprovalGranted = false;

  /**
   * The responsible holder's {@code app_user} id who granted in-app approval; {@code null} until
   * granted. Loose reference ({@code ON DELETE SET NULL}).
   */
  @Nullable
  @Column(name = "owner_approval_granted_by")
  private UUID ownerApprovalGrantedBy;

  /**
   * Denormalized effective-name snapshot of the responsible holder who granted approval; {@code
   * null} until granted.
   */
  @Nullable
  @Column(name = "owner_approval_granted_by_handle")
  private String ownerApprovalGrantedByHandle;

  /** When the responsible holder granted in-app approval; {@code null} until granted. */
  @Nullable
  @Column(name = "owner_approval_granted_at")
  private Instant ownerApprovalGrantedAt;
}
