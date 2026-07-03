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
 * A Kartell bank account (epic #556, REQ-BANK-001) — one row per org-unit/area/cartel/cartel-bank/
 * special account, persisted in the {@code bank_account} table created by Flyway V150.
 *
 * <p>The account deliberately stores no balance: balances are SQL sums over {@link BankPosting}
 * computed on read (ADR-0010), so this row only changes on rename and lifecycle transitions and the
 * optimistic-locking {@code @Version} from {@link AbstractEntity} never churns on bookings. The
 * owner reference must match the {@link #type} — V150's {@code chk_bank_account_owner_ref} enforces
 * the combination at the database level, {@code BankAccountService} validates it before insert for
 * a clean 400/409 instead of a constraint error.
 *
 * <p>Bank accounts are NOT org-unit-scoped aggregates: visibility is decided exclusively by the
 * bank roles and {@link BankAccountGrant} rows (REQ-BANK-008/-010), never by {@code
 * OwnerScopeService}. The {@link #orgUnit} reference is the *owner label* of an {@code ORG_UNIT}
 * account, not a tenancy scope.
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
   * Owning org unit reference. For {@link BankAccountType#ORG_UNIT} accounts this is the Staffel or
   * Spezialkommando; since epic #692 (REQ-ORG-019, V168) it also carries the Bereich for {@link
   * BankAccountType#AREA} accounts and the Organisationsleitung for the {@link
   * BankAccountType#CARTEL} account — Bereiche/OL are first-class {@link OrgUnit} rows now, so the
   * AREA/CARTEL owner is the same FK rather than the legacy {@link #areaName}. {@code null} for a
   * legacy {@code areaName}-based AREA account and for {@code CARTEL_BANK} / {@code SPECIAL}. At
   * most one account per org unit (V150 {@code uq_bank_account_org_unit} partial unique index) — so
   * one AREA per Bereich and one CARTEL per OL. Lazy-fetched — listing accounts must not hydrate
   * org-unit rows unless mapped.
   */
  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "org_unit_id", updatable = false)
  @ToString.Exclude
  private OrgUnit orgUnit;

  /**
   * Legacy free-form Bereich name for {@link BankAccountType#AREA} accounts created before the
   * Bereich FK (epic #692). {@code null} for FK-linked AREA accounts (whose Bereich is carried by
   * {@link #orgUnit}) and for every non-AREA type. New AREA accounts are created with the FK, not
   * this field.
   */
  @Nullable
  @Column(name = "area_name", updatable = false)
  private String areaName;

  /**
   * Optional aspirational balance goal ("Kontostandsziel", REQ-BANK-036, V189) — a target up to
   * which the account should be filled, shown with progress to everyone who may view the balance.
   * {@code null} means no target is set. Settable by the account's derived responsible holder and
   * by bank staff with access; persisted as {@code NUMERIC(19,4)} per ADR-0002. Editing it bumps
   * this row's {@code @Version} (shared with rename/close, both infrequent), so a concurrent edit
   * surfaces a 409.
   */
  @Nullable
  @Column(name = "balance_target", precision = 19, scale = 4)
  private BigDecimal balanceTarget;

  /**
   * The KRT-account bank-employee approval ceiling {@code T1} (REQ-BANK-047, V203) — the amount up
   * to which a bank employee may approve a withdrawal / transfer <em>leaving</em> the {@link
   * BankAccountType#CARTEL} account on their own (self-approve, or book directly). Above it the
   * request must be approved by the Bereichsleiter Profit; {@code null} means no ceiling is
   * configured yet (treated as {@code 0} — an employee may self-approve nothing). Only meaningful
   * for the CARTEL account — V203's {@code chk_bank_account_cartel_tiers} CHECK pins it {@code
   * null} for every other type. Managed exclusively by bank management in the Verwaltung tab;
   * shares this row's {@code @Version} with rename/close/target (all infrequent). Persisted as
   * {@code NUMERIC(19,4)} per ADR-0002.
   */
  @Nullable
  @Column(name = "employee_approval_ceiling", precision = 19, scale = 4)
  private BigDecimal employeeApprovalCeiling;

  /**
   * The KRT-account area-lead approval ceiling {@code T2} (REQ-BANK-047, V203) — the amount up to
   * which the Bereichsleiter Profit approves a withdrawal / transfer leaving the {@link
   * BankAccountType#CARTEL} account; above it the Organisationsleitung must approve. Must be {@code
   * >= }{@link #employeeApprovalCeiling} when both are set (V203 CHECK); {@code null} means no
   * upper band (treated as {@code +∞} — the Bereichsleiter Profit covers everything above {@code
   * T1}, the OL band stays empty). CARTEL-only, bank-management-managed; shares this row's
   * {@code @Version}.
   */
  @Nullable
  @Column(name = "area_lead_approval_ceiling", precision = 19, scale = 4)
  private BigDecimal areaLeadApprovalCeiling;
}
