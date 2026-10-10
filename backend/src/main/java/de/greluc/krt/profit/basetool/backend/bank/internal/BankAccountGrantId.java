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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Composite primary key of {@link BankAccountGrant}: the {@code (user_id, account_id)} pair. A user
 * holds at most one grant row per account — the row's existence is the view permission, the flags
 * are the booking capabilities (REQ-BANK-009). Mirrors the {@link OrgUnitMembershipId} embeddable
 * pattern.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class BankAccountGrantId implements Serializable {

  /** The granted user's id (JWT {@code sub}); synchronised from the {@code @MapsId} relation. */
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /** The granted {@link BankAccount}'s id; synchronised from the {@code @MapsId} relation. */
  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  /**
   * Value equality over both key halves, as required for a JPA {@code @Embeddable} id.
   *
   * @param o the object to compare against
   * @return {@code true} iff {@code o} is a {@code BankAccountGrantId} with equal user and account
   *     ids
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BankAccountGrantId that)) {
      return false;
    }
    return Objects.equals(userId, that.userId) && Objects.equals(accountId, that.accountId);
  }

  /**
   * Hash over both key halves, consistent with {@link #equals(Object)}.
   *
   * @return combined hash of user and account id
   */
  @Override
  public int hashCode() {
    return Objects.hash(userId, accountId);
  }
}
