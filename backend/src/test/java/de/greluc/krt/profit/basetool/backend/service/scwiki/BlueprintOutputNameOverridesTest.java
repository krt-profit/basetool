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

package de.greluc.krt.profit.basetool.backend.service.scwiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.service.BlueprintNameNormalizer;
import de.greluc.krt.profit.basetool.backend.service.scwiki.BlueprintOutputNameOverrides.Correction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BlueprintOutputNameOverrides}, the curated blueprint output-name
 * corrections (REQ-INV-047).
 */
class BlueprintOutputNameOverridesTest {

  private static final String ARMS_KEY = "BP_CRAFT_qrt_specialist_heavy_arms_01_01_13";
  private static final String HELMET_KEY = "BP_CRAFT_qrt_specialist_heavy_helmet_01_01_12";

  private BlueprintOutputNameOverrides overrides;

  @BeforeEach
  void setUp() {
    overrides = new BlueprintOutputNameOverrides(new BlueprintNameNormalizer());
  }

  @Test
  void seedEntries_carryTheConfirmedWrongAndInGameCorrectNames() {
    Correction arms = overrides.findByKey(ARMS_KEY).orElseThrow();
    assertEquals("Antium Helmet Jet", arms.expectedWrongName());
    assertEquals("Antium Arms Maroon", arms.correctedName());

    Correction helmet = overrides.findByKey(HELMET_KEY).orElseThrow();
    assertEquals("Antium Core Jet", helmet.expectedWrongName());
    assertEquals("Antium Helmet Jet", helmet.correctedName());
  }

  @Test
  void correct_appliesCorrection_whenIncomingMatchesTheWrongName() {
    assertEquals("Antium Arms Maroon", overrides.correct(ARMS_KEY, "Antium Helmet Jet"));
    assertEquals("Antium Helmet Jet", overrides.correct(HELMET_KEY, "Antium Core Jet"));
    assertTrue(overrides.fires(ARMS_KEY, "Antium Helmet Jet"));
  }

  @Test
  void correct_isNormalizationInsensitive_onCaseAndWhitespace() {
    assertEquals("Antium Arms Maroon", overrides.correct(ARMS_KEY, "  antium   HELMET jet "));
    assertTrue(overrides.fires(ARMS_KEY, "ANTIUM HELMET JET"));
  }

  @Test
  void correct_passesThrough_whenCigFixedOrChangedTheName() {
    assertEquals("Antium Arms Maroon", overrides.correct(ARMS_KEY, "Antium Arms Maroon"));
    assertEquals("Totally New Name", overrides.correct(ARMS_KEY, "Totally New Name"));
    assertFalse(overrides.fires(ARMS_KEY, "Antium Arms Maroon"));
  }

  @Test
  void correct_passesThrough_forUnrelatedKey() {
    assertEquals(
        "Antium Helmet Jet", overrides.correct("BP_CRAFT_unrelated_01", "Antium Helmet Jet"));
    assertFalse(overrides.isRegistered("BP_CRAFT_unrelated_01"));
    assertFalse(overrides.fires("BP_CRAFT_unrelated_01", "Antium Helmet Jet"));
  }

  @Test
  void crossedNames_areKeyedIndependently_soTheyDoNotInterfere() {
    assertEquals("Antium Arms Maroon", overrides.correct(ARMS_KEY, "Antium Helmet Jet"));
    assertEquals("Antium Helmet Jet", overrides.correct(HELMET_KEY, "Antium Helmet Jet"));
    assertFalse(overrides.fires(HELMET_KEY, "Antium Helmet Jet"));
  }

  @Test
  void nullInputs_areHandledGracefully() {
    assertFalse(overrides.isRegistered(null));
    assertFalse(overrides.fires(null, "x"));
    assertFalse(overrides.fires(ARMS_KEY, null));
    assertNull(overrides.correct(ARMS_KEY, null));
    assertEquals("x", overrides.correct(null, "x"));
    assertTrue(overrides.findByKey(null).isEmpty());
  }
}
