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
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/**
 * A per-account, per-audience-tier approval ceiling (REQ-BANK-041): members of the tier may raise a
 * booking request up to {@link #limitAmount} aUEC without the responsible holder's approval.
 *
 * <p>A requester matching no tier needs the holder's approval for any amount. The audience mirrors
 * {@link BankAccountViewGrant}; limits are managed only through {@code OrgUnitBankAccessService} as
 * an idempotent upsert.
 */
@Entity
@Table(name = "bank_account_approval_limit")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class BankAccountApprovalLimit extends AbstractEntity<UUID> {

  /** Surrogate primary key, generated client-side by Hibernate ({@code GenerationType.UUID}). */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * The account this limit applies to. Lazy-fetched so listing limits for the settings UI does not
   * hydrate the account unless needed; immutable after creation (a limit is set/cleared, never
   * re-targeted).
   */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false, updatable = false)
  @ToString.Exclude
  private BankAccount account;

  /** Which kind of audience this limit addresses; determines which of the columns below is set. */
  @Enumerated(EnumType.STRING)
  @Column(name = "grantee_kind", nullable = false, length = 16)
  private BankAccountViewGranteeKind granteeKind;

  /**
   * The role this limit addresses: a {@link MembershipRole} name when {@link #granteeKind} is
   * {@code MEMBERSHIP_ROLE}, a global role code (e.g. {@code OFFICER}) when {@code GLOBAL_ROLE}.
   * {@code null} for {@code USER} / {@code ALL_MEMBERS}.
   */
  @Nullable
  @Column(name = "role_code", length = 64)
  private String roleCode;

  /**
   * The single user this limit addresses when {@link #granteeKind} is {@code USER}; {@code null}
   * otherwise. Stored as a raw id (not a relation) — the settings view resolves display names in
   * one batched lookup. Backed by a {@code grantee_user_id} FK with {@code ON DELETE CASCADE}, so a
   * deleted user's limits disappear with them.
   */
  @Nullable
  @Column(name = "grantee_user_id")
  private UUID granteeUserId;

  /**
   * The whole-aUEC ceiling (>= 0) up to which the addressed tier may request without owner
   * approval; a value of {@code 0} means every request of that tier needs approval.
   */
  @Column(name = "limit_amount", nullable = false, precision = 19, scale = 4)
  private BigDecimal limitAmount;
}
