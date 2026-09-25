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
 * A Kartell bank account (REQ-BANK-001): one row per org-unit, area, cartel, cartel-bank or special
 * account.
 *
 * <p>Stores no balance; balances are sums over {@link BankPosting} computed on read (ADR-0010).
 * Visibility is decided by the bank roles and {@link BankAccountGrant} rows, never by org-unit
 * scope; {@link #orgUnit} is only the owner label.
 */
@Entity
@Table(name = "bank_account")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class BankAccount extends AbstractEntity<UUID> {

  /** Surrogate primary key, generated client-side by Hibernate ({@code GenerationType.UUID}). */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * Human-readable, server-generated account number in the {@code KB-<zero-padded sequence>} format
   * (e.g. {@code KB-0042}). Backed by the {@code bank_account_no_seq} sequence (V150), so numbers
   * are unique and never reused even across account deletions (which do not exist — accounts are
   * never hard-deleted, REQ-BANK-002).
   */
  @Column(name = "account_no", nullable = false, length = 16, updatable = false)
  private String accountNo;

  /** Display name chosen by bank management at creation time; changeable via rename. */
  @Column(nullable = false)
  private String name;

  /** The organizational layer this account belongs to; immutable after creation. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16, updatable = false)
  private BankAccountType type;

  /** Lifecycle state; {@code CLOSED} accounts reject postings but keep their history readable. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private BankAccountStatus status = BankAccountStatus.ACTIVE;

  /**
   * Owning org unit: the Staffel or Spezialkommando of an {@link BankAccountType#ORG_UNIT} account,
   * the Bereich of an {@link BankAccountType#AREA} account, or the Organisationsleitung of the
   * {@link BankAccountType#CARTEL} account. {@code null} for an {@link #areaName}-based AREA
   * account and for {@code CARTEL_BANK} / {@code SPECIAL}; at most one account per org unit.
   */
  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "org_unit_id", updatable = false)
  @ToString.Exclude
  private OrgUnit orgUnit;

  /**
   * Free-form Bereich name of an {@link BankAccountType#AREA} account that has no {@link #orgUnit}
   * reference; {@code null} for every other account. New AREA accounts use {@link #orgUnit}.
   */
  @Nullable
  @Column(name = "area_name", updatable = false)
  private String areaName;

  /**
   * Optional balance goal ("Kontostandsziel", REQ-BANK-036) shown with progress to everyone who may
   * view the balance; {@code null} means no target. Editing it bumps this row's {@code @Version}.
   */
  @Nullable
  @Column(name = "balance_target", precision = 19, scale = 4)
  private BigDecimal balanceTarget;

  /**
   * Bank-employee approval ceiling {@code T1} of the {@link BankAccountType#CARTEL} account
   * (REQ-BANK-047): up to this amount an employee may approve a debit leaving the account on their
   * own. {@code null} means none configured and is treated as {@code 0}; always {@code null} for
   * other account types.
   */
  @Nullable
  @Column(name = "employee_approval_ceiling", precision = 19, scale = 4)
  private BigDecimal employeeApprovalCeiling;

  /**
   * Bankleitung approval ceiling {@code T2} of the {@link BankAccountType#CARTEL} account
   * (REQ-BANK-047, ADR-0109); above it the Organisationsleitung must approve. Must be at least
   * {@link #employeeApprovalCeiling} when both are set; {@code null} means no upper band.
   */
  @Nullable
  @Column(name = "area_lead_approval_ceiling", precision = 19, scale = 4)
  private BigDecimal areaLeadApprovalCeiling;
}
