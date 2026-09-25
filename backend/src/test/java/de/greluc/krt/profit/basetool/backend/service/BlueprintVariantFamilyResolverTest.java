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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link BlueprintVariantFamilyResolver} against the real Star Citizen naming corpus that
 * drove its design: cosmetic-variant merging in both directions, the deliberately-conservative
 * non-merging of unquoted sub-models and cross-family names, the magazine exclusion (atomic,
 * capacity-sensitive, energy/ballistic/throwable spellings), the normalization traps (double-space
 * re-collapse, apostrophes inside a nickname, curly quotes), and the curated alias overrides. The
 * real {@link BlueprintNameNormalizer} and {@link BlueprintVariantAliasOverrides} are wired in so
 * the derivation runs end-to-end exactly as production does.
 */
class BlueprintVariantFamilyResolverTest {

  private final BlueprintVariantFamilyResolver resolver =
      new BlueprintVariantFamilyResolver(
          new BlueprintNameNormalizer(), new BlueprintVariantAliasOverrides());

  /**
   * Provides (base, variant) pairs from the real corpus: each cosmetic variant carries a quoted
   * nickname spliced into the base, so both must resolve to the same family key.
   *
   * @return base/variant argument pairs
   */
  private static Stream<Arguments> baseAndVariant() {
    return Stream.of(
        Arguments.of("Fresnel Energy LMG", "Fresnel \"Rockfall\" Energy LMG"),
        Arguments.of("Fresnel Energy LMG", "Fresnel \"Molten\" Energy LMG"),
        Arguments.of("Novian Crossbow", "Novian \"Wildshot\" Crossbow"),
        Arguments.of("Novian Crossbow", "Novian \"Nighthunter\" Crossbow"),
        Arguments.of("Novian Crossbow", "Novian \"Ghostmaker\" Crossbow"),
        Arguments.of("Gallant Rifle", "Gallant \"Nightstalker\" Rifle"),
        Arguments.of("Demeco LMG", "Demeco \"Purgatory Camo\" LMG"),
        Arguments.of("Arrowhead Sniper Rifle", "Arrowhead \"Lamplighter\" Sniper Rifle"),
        Arguments.of("Karna Rifle", "Karna \"Dominion Camo\" Rifle"),
        Arguments.of("Custodian SMG", "Custodian \"CitizenCon 2947\" SMG"),
        Arguments.of("P4-AR Rifle", "P4-AR \"Blacklist\" Rifle"),
        Arguments.of("Devastator Shotgun", "Devastator \"Executive Edition\" Shotgun"),
        Arguments.of("LH86 Pistol", "LH86 \"Takahashi Racing\" Pistol"),
        Arguments.of("F55 LMG", "F55 \"Mark I\" LMG"),
        Arguments.of("Scourge Railgun", "Scourge \"Quite Useful\" Railgun"));
  }

  @ParameterizedTest(name = "{1} merges into {0}")
  @MethodSource("baseAndVariant")
  void variantSharesFamilyKeyWithBase(String base, String variant) {
    assertEquals(
        resolver.familyKey(base),
        resolver.familyKey(variant),
        "variant must resolve to the same family as its base");
  }

  @Test
  void twoVariantsOfTheSameBaseShareFamilyKey() {
    assertEquals(
        resolver.familyKey("Novian \"Wildshot\" Crossbow"),
        resolver.familyKey("Novian \"Ghostmaker\" Crossbow"));
    assertEquals(
        resolver.familyKey("Fresnel \"Rockfall\" Energy LMG"),
        resolver.familyKey("Fresnel \"Molten\" Energy LMG"));
  }

  @Test
  void baseFamilyKeyIsTheCleanLowercasedBaseName() {
    assertEquals("fresnel energy lmg", resolver.familyKey("Fresnel \"Rockfall\" Energy LMG"));
    assertEquals("novian crossbow", resolver.familyKey("Novian \"Wildshot\" Crossbow"));
  }

  @Test
  void doubleSpaceFromMidNameNicknameIsRecollapsed() {
    String key = resolver.familyKey("Fresnel \"Rockfall\" Energy LMG");
    assertFalse(key.contains("  "), "family key must not contain a double space");
    assertEquals("fresnel energy lmg", key);
  }

  @Test
  void apostropheInsideNicknameSurvivesAndWholeQuotedSpanIsRemoved() {
    assertEquals("custodian smg", resolver.familyKey("Custodian \"Citizen's Pride\" SMG"));
    assertEquals("custodian smg", resolver.familyKey("Custodian \"Foundry's Fire\" SMG"));
    assertEquals("novian crossbow", resolver.familyKey("Novian \"Xy'kara\" Crossbow"));
  }

  @Test
  void curlyQuotedVariantMergesWithStraightQuotedAndBase() {
    assertEquals(
        resolver.familyKey("Sawtooth Combat Knife"),
        resolver.familyKey("Sawtooth “Sirocco” Combat Knife"));
    assertEquals(
        resolver.familyKey("Sawtooth \"Squall\" Combat Knife"),
        resolver.familyKey("Sawtooth “Sirocco” Combat Knife"));
  }

  @Test
  void multiWordNicknameIsRemovedAsOneSpan() {
    assertEquals("scourge railgun", resolver.familyKey("Scourge \"Quite Useful\" Railgun"));
    assertEquals("custodian smg", resolver.familyKey("Custodian \"CitizenCon 2947\" SMG"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t"})
  void blankNameYieldsEmptyKey(String blank) {
    assertEquals("", resolver.familyKey(blank));
  }

  @Test
  void nullNameYieldsEmptyKey() {
    assertEquals("", resolver.familyKey(null));
  }

  /**
   * Provides pairs of names that must stay in <em>distinct</em> families: cross-family names, ship
   * sub-models, and unquoted sub-models the conservative rule deliberately does not merge.
   *
   * @return distinct-name argument pairs
   */
  private static Stream<Arguments> mustStayDistinct() {
    return Stream.of(
        Arguments.of("Sawtooth \"Sirocco\" Combat Knife", "Karna \"Valor\" Rifle"),
        Arguments.of("Aurora MR", "Aurora LN"),
        Arguments.of("Cutlass Black", "Cutlass Red"),
        Arguments.of("300i", "325a"),
        Arguments.of("Karna Rifle", "Karna Pistol"));
  }

  @ParameterizedTest(name = "{0} != {1}")
  @MethodSource("mustStayDistinct")
  void distinctProductsDoNotMerge(String a, String b) {
    assertNotEquals(resolver.familyKey(a), resolver.familyKey(b));
  }

  /**
   * Provides (weapon, magazine) pairs spanning the real ammo-container spellings: ballistic
   * "Magazine", energy "Battery", "Ammo Box", crossbow "Bolt Magazine", and assorted capacities and
   * casings. The magazine must never share its weapon's family key.
   *
   * @return weapon/magazine argument pairs
   */
  private static Stream<Arguments> weaponAndMagazine() {
    return Stream.of(
        Arguments.of("Novian Crossbow", "Novian Bolt Magazine (5 Cap)"),
        Arguments.of("Karna Rifle", "Karna Rifle Battery (35 cap)"),
        Arguments.of("Gallant Rifle", "Gallant Rifle Battery (45 cap)"),
        Arguments.of("F55 LMG", "F55 Ammo Box (300 cap)"),
        Arguments.of("Custodian SMG", "Custodian SMG Magazine (60 cap)"),
        Arguments.of("Pulverizer LMG", "Pulverizer LMG Magazine (120 Cap)"),
        Arguments.of("P4-AR Rifle", "P4-AR Magazine (40 cap)"),
        Arguments.of("Scourge Railgun", "Scourge Railgun Magazine (5 cap)"));
  }

  @ParameterizedTest(name = "{1} is not a variant of {0}")
  @MethodSource("weaponAndMagazine")
  void magazineNeverSharesWeaponFamily(String weapon, String magazine) {
    assertTrue(resolver.isMagazine(magazine), "expected magazine to be detected: " + magazine);
    assertFalse(resolver.isMagazine(weapon), "weapon must not be a magazine: " + weapon);
    assertNotEquals(
        resolver.familyKey(weapon),
        resolver.familyKey(magazine),
        "magazine must not fold into the weapon family");
  }

  @Test
  void magazineKeyIsAtomicAndCapacitySensitive() {
    assertNotEquals(
        resolver.familyKey("S-38 Magazine (15 cap)"), resolver.familyKey("S-38 Magazine (20 cap)"));
    assertEquals(
        resolver.familyKey("Karna Magazine (40 cap)"),
        resolver.familyKey("Karna Magazine (40 cap)"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Demeco LMG Battery (100 cap)",
        "Atzkav Sniper Rifle Battery (8 cap)",
        "Salvo Frag Pistol Magazine (8 cap)",
        "P8-AR Rifle Magazine (15 Cap)"
      })
  void realMagazinesAreDetected(String magazine) {
    assertTrue(resolver.isMagazine(magazine));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Coda Pistol (Modified)", "Capacitor Module", "Magma Cutter"})
  void nonMagazinesAreNotMisdetected(String name) {
    assertFalse(resolver.isMagazine(name), name + " must not be detected as a magazine");
  }

  @Test
  void nicknameContainingMagLettersDoesNotTriggerMagazineDetection() {
    assertEquals(
        resolver.familyKey("Pulverizer LMG"), resolver.familyKey("Pulverizer \"Magma\" LMG"));
  }

  @Test
  void baseNameDriftIsCanonicalizedByAliasLayer() {
    assertEquals(
        resolver.familyKey("Pulse \"ArcCorp\" Laser Pistol"),
        resolver.familyKey("Pulse \"Blacklist\" Pistol"));
    assertEquals("pulse laser pistol", resolver.familyKey("Pulse \"Blacklist\" Pistol"));
  }

  @Test
  void displayBaseNamePreservesCaseAndStripsNickname() {
    assertEquals("Fresnel Energy LMG", resolver.displayBaseName("Fresnel \"Molten\" Energy LMG"));
    assertEquals("Novian Crossbow", resolver.displayBaseName("Novian “Wildshot” Crossbow"));
    assertEquals("Custodian SMG", resolver.displayBaseName("Custodian \"Citizen's Pride\" SMG"));
    assertEquals(
        "Karna Rifle Battery (35 cap)", resolver.displayBaseName("Karna Rifle Battery (35 cap)"));
  }

  @Test
  void unquotedSubModelsAreMergedOnlyWhenAliased() {
    assertEquals(
        resolver.familyKey("Salvo Frag Pistol"), resolver.familyKey("Salvo Esteban Frag Pistol"));
    assertEquals(
        resolver.familyKey("Salvo Frag Pistol"), resolver.familyKey("Salvo Saeed Frag Pistol"));
    assertEquals(resolver.familyKey("Arclight Pistol"), resolver.familyKey("Model II Arclight"));
  }
}
