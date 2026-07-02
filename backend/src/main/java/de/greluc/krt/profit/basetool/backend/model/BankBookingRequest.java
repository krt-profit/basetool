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
 * A confirm-before-post deposit / withdrawal / transfer request raised by any caller who may view a
 * bank account (epic #666 F2, REQ-BANK-022/-023/-039/-040/-041), persisted in the {@code
 * bank_booking_request} table (Flyway V159, extended by V193).
 *
 * <p>This is the deliberate counterpart to the append-only ledger: unlike {@link BankTransaction} /
 * {@link BankPosting}, a booking request is a <strong>mutable</strong> off-ledger aggregate (it
 * carries the optimistic-locking {@code @Version} from {@link AbstractEntity}) and moves no money
 * while {@code PENDING} (ADR-0021). Only when a bank employee confirms it does the request name the
 * {@link #holder} (deposit → who received the money; withdrawal → who paid it out; transfer → the
 * source holder), book a real {@link BankTransaction} through {@link BankAccount}'s ledger, link
 * that transaction in {@link #resultingTransaction}, and flip to {@code CONFIRMED}. A {@code
 * REJECTED} / {@code CANCELLED} / {@code PENDING} request never carries a holder or resulting
 * transaction — V159's {@code chk_bank_booking_request_confirmed_refs} constraint pins that
 * invariant.
 *
 * <p>For a {@code TRANSFER} request the requester also names the {@link #targetAccount} (any active
 * account, REQ-BANK-040); the destination holder is recorded only on the resulting ledger
 * transaction at confirmation.
 *
 * <p><strong>Two-step owner approval (REQ-BANK-041).</strong> {@link #requiresOwnerApproval} and
 * {@link #applicableLimit} are <em>snapshotted at creation</em> by the org-unit-aware seam (it
 * resolves the requester's applicable approval limit); the org-unit-blind confirm path only reads
 * the boolean to decide whether the bank employee's confirmation checkbox is mandatory. {@link
 * #ownerApprovalGranted} (with {@link #ownerApprovalGrantedBy} / handle / instant) records the
 * account's responsible holder granting that approval in-app from the "Fremde Anträge" tab, which
 * pre-fills the bank employee's checkbox.
 *
 * <p>The requester, decider and approving holder are stored as plain {@code app_user} ids ({@code
 * ON DELETE SET NULL}) plus a denormalized effective-name snapshot, mirroring {@link
 * BankAuditEvent} so the row — and the bank-staff queue rendered from it — survives a user
 * deletion.
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
  @JoinColumn(name = "target_account_id", updatable = false)
  @ToString.Exclude
  private BankAccount targetAccount;

  /** Whether the request is a deposit, a withdrawal or a transfer; immutable. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16, updatable = false)
  private BankBookingRequestType type;

  /** The requested whole-aUEC amount, strictly positive; immutable. */
  @Column(nullable = false, precision = 19, scale = 4, updatable = false)
  private BigDecimal amount;

  /**
   * Optional free-text note supplied by the requester; carried onto the booking on confirmation.
   */
  @Nullable
  @Column(length = 500, updatable = false)
  private String note;

  /**
   * Optional free-text justification (Begr&uuml;ndung) supplied by the requester, carried onto the
   * booking on confirmation (REQ-BANK-045). Captured only for a {@code WITHDRAWAL} / {@code
   * TRANSFER} and required when the source account type {@linkplain
   * BankAccountType#requiresDebitJustification() mandates a reason}; {@code null} otherwise.
   */
  @Nullable
  @Column(length = 500, updatable = false)
  private String justification;

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
   * Snapshot at creation (REQ-BANK-041/-046): which class of approver must approve this request
   * before a bank employee may confirm it; {@code null} unless {@link #requiresOwnerApproval} is
   * set. For every request-capable account except the KRT account this is {@link
   * BankRequestApprover#RESPONSIBLE_HOLDER}; for a KRT (CARTEL) withdrawal/transfer it is the
   * amount-band approver ({@link BankRequestApprover#AREA_LEAD_PROFIT} / {@link
   * BankRequestApprover#ORGANISATIONSLEITUNG}). The org-unit-aware seam resolves the band and, on
   * the "Fremde Anträge" surface, who may act on it; the org-unit-blind confirm path never reads
   * this. Immutable.
   */
  @Nullable
  @Enumerated(EnumType.STRING)
  @Column(name = "required_approver", length = 32, updatable = false)
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
