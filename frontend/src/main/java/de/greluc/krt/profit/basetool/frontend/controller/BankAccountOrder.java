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

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/**
 * Shared ordering for every bank account picker and listing: A→Z by name, case-insensitively, so
 * accounts appear in the same order everywhere.
 */
public final class BankAccountOrder {

  /** Utility class — not instantiable. */
  private BankAccountOrder() {}

  /**
   * Returns a new list with the accounts ordered alphabetically (case-insensitive) by the name the
   * extractor yields. A {@code null} name sorts as an empty string (first) so a missing name never
   * throws; the input list is left untouched.
   *
   * @param accounts the accounts to order (never {@code null})
   * @param nameOf extracts the display name of one account (may itself yield {@code null})
   * @param <T> the account row / card / reference type being ordered
   * @return a new, name-sorted list preserving every element of {@code accounts}
   */
  @NotNull
  public static <T> List<T> byName(@NotNull List<T> accounts, @NotNull Function<T, String> nameOf) {
    Comparator<T> byName =
        Comparator.comparing(
            account -> {
              String name = nameOf.apply(account);
              return name == null ? "" : name;
            },
            String.CASE_INSENSITIVE_ORDER);
    return accounts.stream().sorted(byName).toList();
  }
}
