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
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jetbrains.annotations.Nullable;

/**
 * Per-employee, per-account bank capability grant (REQ-BANK-009, ADR-0011), keyed by {@link
 * BankAccountGrantId}.
 *
 * <p>The row's existence grants view access; its three flags gate deposits, withdrawals and
 * transfers. Evaluated by {@code BankSecurityService} with the bank roles only, never with org-unit
 * memberships (REQ-BANK-008).
 */
@Entity
@Table(name = "bank_account_grant")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class BankAccountGrant {

  /**
   * Composite primary key combining the grantee's user id and the granted account's id. The
   * {@code @MapsId} relations on {@link #user} and {@link #account} synchronise both halves, so
   * service code only sets the two entity references plus an empty embeddable.
   */
  @EmbeddedId private BankAccountGrantId id;

  /**
   * The grantee. {@code @MapsId("userId")} ties the {@code user_id} column to the key half;
   * lazy-fetched so listing grants does not hydrate every user row unless the DTO mapping needs it
   * (the grants UI does — it uses an entity graph on the repository query).
   */
  @MapsId("userId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @ToString.Exclude
  private User user;

  /**
   * The granted account. {@code @MapsId("accountId")} ties the {@code account_id} column to the key
   * half; lazy-fetched for the same reason as {@link #user}.
   */
  @MapsId("accountId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false)
  @ToString.Exclude
  private BankAccount account;

  /** {@code true} permits booking deposits onto the granted account. */
  @Column(name = "can_deposit", nullable = false)
  private boolean canDeposit = false;

  /** {@code true} permits booking withdrawals from the granted account. */
  @Column(name = "can_withdraw", nullable = false)
  private boolean canWithdraw = false;

  /**
   * {@code true} permits transfers out of the granted account — both account-to-account transfers
   * (the flag is checked on the <em>source</em> account) and intra-account holder rebookings
   * (REQ-BANK-011).
   */
  @Column(name = "can_transfer", nullable = false)
  private boolean canTransfer = false;

  /**
   * The manager or admin who created the grant; informational. {@code null} after the granting user
   * was deleted ({@code ON DELETE SET NULL}) — the authoritative trail is the audit log.
   */
  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "granted_by")
  @ToString.Exclude
  private User grantedBy;

  /**
   * Optimistic-lock counter so two managers concurrently editing the same grant row surface a 409
   * Conflict instead of silently losing a flag change. Mirrors {@link
   * OrgUnitMembership#getVersion()}.
   */
  @Version private Long version;

  /**
   * Insert-time audit timestamp populated by Hibernate's {@code @CreationTimestamp}; reproduced
   * here because this entity cannot extend {@link AbstractEntity} (composite key).
   */
  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private Instant createdAt;

  /**
   * Last-update audit timestamp populated by Hibernate's {@code @UpdateTimestamp}; reproduced here
   * because this entity cannot extend {@link AbstractEntity} (composite key).
   */
  @UpdateTimestamp
  @Column(name = "updated_at")
  private Instant updatedAt;
}
