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

/** Unit tests for {@link BlueprintPackTags} (REQ-INV-050). */
class BlueprintPackTagsTest {

  @Test
  void stripsTheStarStringsClassPrefix() {
    assertEquals("Cirrus", BlueprintPackTags.strip("Sth/2/C Cirrus"));
    assertEquals("Citadel", BlueprintPackTags.strip("Ind/2/B Citadel"));
    assertEquals("Cirrus", BlueprintPackTags.strip("sth/2/c Cirrus"));
  }

  @Test
  void stripsTheBracketedClassPrefix() {
    assertEquals("Cirrus", BlueprintPackTags.strip("[STH-S2-C] Cirrus"));
    assertEquals(
        "Aegis Eclipse 20xS3 Bomb Rack",
        BlueprintPackTags.strip("[BRK-S3-A] Aegis Eclipse 20xS3 Bomb Rack"));
    assertEquals("Omnisky III Cannon", BlueprintPackTags.strip("[E-S1] Omnisky III Cannon"));
  }

  @Test
  void stripsTheGermanPackSuffix() {
    assertEquals("Cirrus", BlueprintPackTags.strip("Cirrus (S2 C Stealth)"));
    assertEquals("IcePlunge", BlueprintPackTags.strip("IcePlunge (S1 C Competition)"));
    assertEquals("Citadel", BlueprintPackTags.strip("Citadel (Ind/2/B)"));
  }

  @Test
  void leavesUntaggedAndNonPackNamesAlone() {
    assertNull(BlueprintPackTags.strip("Cirrus"));
    assertNull(BlueprintPackTags.strip("S71 Rifle Magazine (30 cap)"));
    assertNull(BlueprintPackTags.strip("[PH] Noodles v2"));
    assertNull(BlueprintPackTags.strip("Yubarev \"Mirage\" Pistol"));
    assertNull(BlueprintPackTags.strip("Hammer Propulsion HL 2.4 (TR4)"));
    assertNull(BlueprintPackTags.strip(null));
  }

  @Test
  void neverStripsANameDownToNothing() {
    assertNull(BlueprintPackTags.strip("Sth/2/C"));
    assertNull(BlueprintPackTags.strip("[STH-S2-C]"));
  }
}
