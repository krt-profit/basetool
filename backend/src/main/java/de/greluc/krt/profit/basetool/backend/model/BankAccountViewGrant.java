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
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/**
 * Holder-configured read access to one {@link BankAccount} (REQ-BANK-035/-038): the row's existence
 * lets its audience view the balance and the read-only detail.
 *
 * <p>Separate from the bank-staff {@link BankAccountGrant} and evaluated by {@code
 * OrgUnitBankAccessService}; the audience is polymorphic on {@link #granteeKind}. Toggling is an
 * idempotent insert/delete.
 */
@Entity
@Table(name = "bank_account_view_grant")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class BankAccountViewGrant extends AbstractEntity<UUID> {

  /** Surrogate primary key, generated client-side by Hibernate ({@code GenerationType.UUID}). */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * The account this grant opens view access to. Lazy-fetched so listing grants for the settings UI
   * does not hydrate the account unless needed; immutable after creation (a grant is added/removed,
   * never re-targeted).
   */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false, updatable = false)
  @ToString.Exclude
  private BankAccount account;

  /** Which kind of audience this grant addresses; determines which of the columns below is set. */
  @Enumerated(EnumType.STRING)
  @Column(name = "grantee_kind", nullable = false, length = 16)
  private BankAccountViewGranteeKind granteeKind;

  /**
   * The role this grant addresses: a {@link MembershipRole} name when {@link #granteeKind} is
   * {@code MEMBERSHIP_ROLE}, a global role code (e.g. {@code OFFICER}) when {@code GLOBAL_ROLE}.
   * {@code null} for {@code USER} / {@code ALL_MEMBERS}.
   */
  @Nullable
  @Column(name = "role_code", length = 64)
  private String roleCode;

  /**
   * The single user this grant addresses when {@link #granteeKind} is {@code USER}; {@code null}
   * otherwise. Stored as a raw id (not a relation) — the settings view resolves display names in
   * one batched lookup. Backed by a {@code grantee_user_id} FK with {@code ON DELETE CASCADE}, so a
   * deleted user's grants disappear with them.
   */
  @Nullable
  @Column(name = "grantee_user_id")
  private UUID granteeUserId;
}
