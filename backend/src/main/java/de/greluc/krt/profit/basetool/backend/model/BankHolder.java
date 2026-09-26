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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A player acting as physical custodian of bank money (REQ-BANK-003).
 *
 * <p>Every {@link BankPosting} names exactly one holder. The row links to at most one {@link User}
 * and snapshots the {@link #handle} so the ledger stays readable after user deletion. Holders
 * referenced by postings are never deleted; {@code active = false} blocks new postings instead.
 */
@Entity
@Table(name = "bank_holder")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class BankHolder extends AbstractEntity<UUID> {

  /** Surrogate primary key, generated client-side by Hibernate ({@code GenerationType.UUID}). */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * The linked basetool user; {@code null} after the user was deleted (the database sets the column
   * to NULL, the {@link #handle} snapshot keeps the row meaningful). Lazy-fetched so listing
   * holders does not hydrate user rows.
   */
  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  @ToString.Exclude
  private User user;

  /**
   * Denormalized player handle captured at registration time (the user's effective name). Shown
   * everywhere the holder appears — ledger rows, distributions, statements — and authoritative once
   * the linked user is gone.
   */
  @Column(nullable = false)
  private String handle;

  /**
   * {@code false} blocks new incoming postings naming this holder (registration mistakes, players
   * leaving custody duty); existing ledger history is never touched and money may still be moved
   * out of (or reconciled into) the stash. Deactivation requires no zero balance — the money record
   * stays where it physically is.
   */
  @Column(nullable = false)
  private boolean active = true;

  /**
   * {@code true} when this holder was auto-created from a bank role and is deactivated by the
   * reconcile once the user loses all bank roles (REQ-BANK-029); {@code false} for a manually
   * registered custodian, which the reconcile never touches.
   */
  @Column(name = "role_managed", nullable = false)
  private boolean roleManaged = false;

  /**
   * Returns the holder's display label (REQ-BANK-003): the linked user's live effective name, or
   * the {@link #handle} snapshot once the user was deleted. Must be called inside an open
   * persistence context so the lazy {@link #user} can initialise.
   *
   * @return the linked user's current effective name, or the {@link #handle} snapshot when the user
   *     is gone
   */
  public @NotNull String getDisplayName() {
    return user != null ? user.getEffectiveName() : handle;
  }
}
