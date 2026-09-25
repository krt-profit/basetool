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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link BlueprintNameNormalizer}. */
class BlueprintNameNormalizerTest {

  private final BlueprintNameNormalizer normalizer = new BlueprintNameNormalizer();

  @Test
  void nullAndBlankYieldEmptyString() {
    assertEquals("", normalizer.normalize(null));
    assertEquals("", normalizer.normalize("   "));
  }

  @Test
  void trimsCollapsesWhitespaceAndLowercases() {
    assertEquals("arclight pistol", normalizer.normalize("  Arclight   Pistol  "));
  }

  @Test
  void foldsUnicodeDoubleQuotesToAscii() {
    assertEquals(
        normalizer.normalize("Arclight \"Nightstalker\" Pistol"),
        normalizer.normalize("Arclight “Nightstalker” Pistol"));
  }

  @Test
  void foldsUnicodeApostropheToAscii() {
    assertEquals(normalizer.normalize("Gallenson's"), normalizer.normalize("Gallenson’s"));
  }

  @Test
  void keepsDistinctNamesDistinct() {
    assertEquals("adp core", normalizer.normalize("ADP Core"));
    assertEquals("adp-mk4 core woodland", normalizer.normalize("ADP-mk4 Core Woodland"));
  }
}
