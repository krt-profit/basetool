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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BankAccountOrder}: the canonical A→Z, case-insensitive ordering applied to
 * every bank account picker and overview (REQ-BANK-016). Pins the three guarantees the callers rely
 * on — case-insensitive order, null-safe handling of a missing name, and that the caller's input
 * list is never mutated in place.
 */
class BankAccountOrderTest {

  @Test
  void byName_ordersCaseInsensitivelyAndAlphabetically() {
    List<String> input = List.of("banana", "Apple", "cherry", "apple");

    List<String> ordered = BankAccountOrder.byName(input, Function.identity());

    assertEquals(List.of("Apple", "apple", "banana", "cherry"), ordered);
  }

  @Test
  void byName_treatsNullNameAsEmptyAndSortsItFirst() {
    List<String> input = Arrays.asList("b", null, "a");

    List<String> ordered = BankAccountOrder.byName(input, Function.identity());

    assertEquals(Arrays.asList(null, "a", "b"), ordered);
  }

  @Test
  void byName_doesNotMutateTheInputList() {
    List<String> input = new ArrayList<>(List.of("c", "a", "b"));

    BankAccountOrder.byName(input, Function.identity());

    assertEquals(List.of("c", "a", "b"), input);
  }
}
