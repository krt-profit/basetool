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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** Keeps a deliberate requester designation addressable after it ceases to accept handovers. */
@Entity
@Table(name = "job_order_money_recipient")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
class JobOrderMoneyRecipient {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Nullable
  private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID orderId;

  @Nullable private UUID userId;

  @Column(updatable = false)
  @Nullable
  private UUID holderId;

  @Column(length = 200)
  @Nullable
  private String accountlessName;

  @Column(nullable = false)
  private boolean active = true;

  JobOrderMoneyRecipient(UUID orderId, JobOrderMoneySelection selection) {
    this.orderId = orderId;
    switch (selection.kind()) {
      case EXISTING -> holderId = selection.reference();
      case LINKED -> userId = selection.reference();
      case ACCOUNTLESS -> accountlessName = selection.name();
      default -> throw new IllegalArgumentException("A requester designation requires a party.");
    }
  }

  void remove() {
    active = false;
  }
}
