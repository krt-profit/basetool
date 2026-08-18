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
 * A per-account, per-visibility-tier approval ceiling (REQ-BANK-041), persisted in the {@code
 * bank_account_approval_limit} table created by Flyway V193.
 *
 * <p>The row says: members of the named tier may raise a booking request against this account up to
 * {@link #limitAmount} whole aUEC <em>without</em> the account's responsible holder having to grant
 * an explicit approval; a request above it is flagged {@code requiresOwnerApproval} on creation.
 *
 * <p><strong>A <em>missing</em> row means the opposite of what it originally did.</strong> The
 * feature shipped with "no matching row = unlimited" (no approval ever required, preserving the
 * pre-feature behaviour); that default was later <em>inverted</em> by owner decision, so a
 * requester matching no tier now needs the responsible holder's approval for <em>any</em> amount
 * (REQ-BANK-041, {@code requires_owner_approval = (applicable_limit == null) || amount >
 * applicable_limit}). The V193 table/column comments still carry the retired wording — they are a
 * shipped migration and cannot be edited; {@code OrgUnitBankAccessService#resolveApplicableLimit}
 * is the authority.
 *
 * <p>The audience dimension mirrors {@link BankAccountViewGrant} one-for-one (V193 {@code
 * chk_bank_appr_limit_payload} enforces the column combination): a {@link MembershipRole} on the
 * owning unit ({@code MEMBERSHIP_ROLE}), a global role code ({@code GLOBAL_ROLE}, for {@link
 * BankAccountType#SPECIAL} accounts), a single user ({@code USER}), or all members ({@code
 * ALL_MEMBERS}). The limit is configured by the account's responsible holder, bank management or an
 * admin — never by a plain bank employee — exclusively through the org-unit-aware {@code
 * OrgUnitBankAccessService} seam. Setting a tier's limit is an idempotent upsert (partial unique
 * indexes prevent duplicate rows), so the row carries no client-echoed version of its own — the
 * inherited {@code @Version} only guards a rare concurrent write to the same row.
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
