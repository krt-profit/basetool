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
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Tests the curated {@link BlueprintVariantAliasOverrides}: registered aliases canonicalize to
 * their base family key, unregistered keys pass through verbatim (the self-healing no-op
 * guarantee), the canonical targets are stable fixed points, and {@code null} is tolerated.
 */
class BlueprintVariantAliasOverridesTest {

  private final BlueprintVariantAliasOverrides overrides = new BlueprintVariantAliasOverrides();

  @Test
  void registeredAliasCanonicalizesToBase() {
    assertEquals("pulse laser pistol", overrides.canonical("pulse pistol"));
    assertEquals("salvo frag pistol", overrides.canonical("salvo esteban frag pistol"));
    assertEquals("salvo frag pistol", overrides.canonical("salvo saeed frag pistol"));
    assertEquals("arclight pistol", overrides.canonical("model ii arclight"));
    assertEquals("arclight pistol", overrides.canonical("arclight model ii"));
  }

  @Test
  void unregisteredKeyPassesThroughUnchanged() {
    String key = "fresnel energy lmg";
    assertSame(key, overrides.canonical(key));
    assertEquals("novian crossbow", overrides.canonical("novian crossbow"));
  }

  @Test
  void canonicalTargetsAreStableFixedPoints() {
    assertEquals("pulse laser pistol", overrides.canonical("pulse laser pistol"));
    assertEquals("salvo frag pistol", overrides.canonical("salvo frag pistol"));
    assertEquals("arclight pistol", overrides.canonical("arclight pistol"));
  }

  @Test
  void nullKeyIsTolerated() {
    assertNull(overrides.canonical(null));
  }
}
