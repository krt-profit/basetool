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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link PlanetColorResolver}. */
class PlanetColorResolverTest {

  @Test
  void nullPlanetYieldsUnknownClass() {
    assertEquals(
        PlanetColorResolver.UNKNOWN_CLASS, PlanetColorResolver.cssClassFor("Stanton", null));
  }

  @Test
  void blankPlanetYieldsUnknownClass() {
    assertEquals(
        PlanetColorResolver.UNKNOWN_CLASS, PlanetColorResolver.cssClassFor("Stanton", "   "));
  }

  @Test
  void canonicalPlanetsResolveToCanonicalClass() {
    assertEquals("planet-hurston", PlanetColorResolver.cssClassFor("Stanton", "Hurston"));
    assertEquals("planet-crusader", PlanetColorResolver.cssClassFor("Stanton", "Crusader"));
    assertEquals("planet-arccorp", PlanetColorResolver.cssClassFor("Stanton", "ArcCorp"));
    assertEquals("planet-microtech", PlanetColorResolver.cssClassFor("Stanton", "microTech"));
    assertEquals("planet-pyro-1", PlanetColorResolver.cssClassFor("Pyro", "Pyro I"));
    assertEquals("planet-pyro-2", PlanetColorResolver.cssClassFor("Pyro", "Pyro II"));
    assertEquals("planet-pyro-2", PlanetColorResolver.cssClassFor("Pyro", "Monox"));
    assertEquals("planet-terra", PlanetColorResolver.cssClassFor("Terra", "Terra"));
  }

  @Test
  void canonicalLookupIsCaseInsensitive() {
    assertEquals("planet-hurston", PlanetColorResolver.cssClassFor("stanton", "HURSTON"));
    assertEquals("planet-hurston", PlanetColorResolver.cssClassFor("stanton", "  hurston  "));
  }

  @Test
  void unknownPlanetFallsBackToHashedClass() {
    String result = PlanetColorResolver.cssClassFor("Nyx", "MadePlanet");
    assertTrue(result.startsWith("planet-hash-"), "expected hash fallback, got: " + result);
    int index;
    try {
      index = Integer.parseInt(result.substring("planet-hash-".length()));
    } catch (NumberFormatException ex) {
      throw new AssertionError("hash class suffix must be a valid integer, got: " + result, ex);
    }
    assertTrue(
        index >= 0 && index < PlanetColorResolver.HASH_PALETTE_SIZE,
        "hash bucket out of range: " + index);
  }

  @Test
  void hashFallbackIsDeterministic() {
    String a = PlanetColorResolver.cssClassFor("Nyx", "MadePlanet");
    String b = PlanetColorResolver.cssClassFor("Nyx", "MadePlanet");
    assertEquals(a, b, "same input must yield same class across calls");
  }

  @Test
  void hashFallbackDiscriminatesAcrossSystems() {
    String inA = PlanetColorResolver.cssClassFor("SystemA", "Unknown");
    String inB = PlanetColorResolver.cssClassFor("SystemB", "Unknown");
    assertNotEquals(inA, inB);
  }

  @Test
  void nullSystemNameDoesNotBlowUp() {
    String result = PlanetColorResolver.cssClassFor(null, "UnknownPlanet");
    assertTrue(result.startsWith("planet-hash-"));
  }
}
