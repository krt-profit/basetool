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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the SCU storage-precision invariant of {@link InventoryItem}: the
 * {@code @PrePersist}/{@code @PreUpdate} hook rounds every persisted amount to three decimals.
 */
class InventoryItemTest {

  @Test
  void lifecycleCallbackRoundsTheAmountField() {
    InventoryItem item = new InventoryItem();
    item.setAmount(37.160000000000004);

    item.roundAmountToScuScale();

    assertEquals(37.16, item.getAmount());
  }
}
