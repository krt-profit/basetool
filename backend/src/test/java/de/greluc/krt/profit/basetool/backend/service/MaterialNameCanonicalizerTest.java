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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Tests the shared commodity-name folding, including the {@code canonicalCore} results the SC Wiki
 * sync relies on.
 */
class MaterialNameCanonicalizerTest {

  @Test
  void canonicalCore_stripsQualifiersAndParentheticals() {
    assertEquals("silicon", MaterialNameCanonicalizer.canonicalCore("Raw Silicon"));
    assertEquals("silicon", MaterialNameCanonicalizer.canonicalCore("Silicon (Raw)"));
    assertEquals("silicon", MaterialNameCanonicalizer.canonicalCore("Silicon"));
    assertEquals("stileron", MaterialNameCanonicalizer.canonicalCore("Stileron (Ore)"));
    assertNull(MaterialNameCanonicalizer.canonicalCore(null));
    assertNull(MaterialNameCanonicalizer.canonicalCore("   "));
  }

  @Test
  void canonicalCore_foldsScreenUppercaseToMasterDataForm() {
    String screen = MaterialNameCanonicalizer.canonicalCore("STILERON (ORE)");
    String master = MaterialNameCanonicalizer.canonicalCore("Stileron (Raw)");

    assertEquals("stileron", screen);
    assertEquals(screen, master);
  }

  @Test
  void canonicalCore_concatenatesMultiWordNames() {
    assertEquals(
        "constructionsalvage", MaterialNameCanonicalizer.canonicalCore("Construction Salvage"));
    assertEquals("uctionsalvage", MaterialNameCanonicalizer.canonicalCore("UCTION SALVAGE"));
  }

  @Test
  void fuzzyKey_preservesWordBoundaries() {
    assertEquals(
        "construction salvage", MaterialNameCanonicalizer.fuzzyKey("Construction Salvage"));
    assertEquals("stileron", MaterialNameCanonicalizer.fuzzyKey("STILERON (ORE)"));
    assertNull(MaterialNameCanonicalizer.fuzzyKey(null));
    assertNull(MaterialNameCanonicalizer.fuzzyKey(" "));
  }
}
