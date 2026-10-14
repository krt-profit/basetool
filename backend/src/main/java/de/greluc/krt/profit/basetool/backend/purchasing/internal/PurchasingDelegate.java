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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/**
 * The appointment of a member as Handelsbeauftragter (REQ-PUR-003): active while it is neither
 * revoked nor past its end date. The member id is nulled when the account is erased; the row stays
 * for the books.
 */
@Entity
@Table(name = "purchasing_delegate")
@Getter
@Setter
@NoArgsConstructor
public class PurchasingDelegate extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Nullable
  @Column(name = "user_id")
  private UUID userId;

  @Nullable
  @Column(name = "appointed_by")
  private UUID appointedBy;

  @Column(name = "appointed_at", nullable = false)
  private Instant appointedAt = Instant.now();

  /** The end of the appointment, or {@code null} for none. */
  @Nullable
  @Column(name = "valid_until")
  private Instant validUntil;

  /** The delegate's personal monthly limit in whole aUEC, or {@code null} for the default. */
  @Nullable
  @Column(name = "monthly_limit")
  private Long monthlyLimit;

  @Nullable
  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Nullable
  @Column(name = "revoked_by")
  private UUID revokedBy;

  /**
   * Whether the appointment confers the capability at a moment.
   *
   * @param now the moment to judge
   * @return {@code true} when it is not revoked and not past its end date
   */
  public boolean isActiveAt(Instant now) {
    return revokedAt == null && (validUntil == null || validUntil.isAfter(now));
  }
}
