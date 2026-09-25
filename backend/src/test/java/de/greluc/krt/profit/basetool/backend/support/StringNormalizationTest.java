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

package de.greluc.krt.profit.basetool.backend.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StringNormalization}: NFC normalization with length cap, {@link
 * StringNormalization#blankToNull(String)}, {@link StringNormalization#trimToNull(String)} and the
 * full {@link StringNormalization#normalize(String, int, boolean) normalize} pipeline.
 */
class StringNormalizationTest {

  /** Combining acute accent (U+0301) — the trailing mark of a decomposed "e-acute". */
  private static final String COMBINING_ACUTE = Character.toString(0x0301);

  /** Decomposed "e-acute": base letter {@code e} plus {@link #COMBINING_ACUTE} (two code units). */
  private static final String DECOMPOSED_E_ACUTE = "e" + COMBINING_ACUTE;

  /** Precomposed "e-acute" (U+00E9): the single-code-unit NFC form the sequence collapses to. */
  private static final String PRECOMPOSED_E_ACUTE = Character.toString(0x00E9);

  /**
   * Em space (U+2003): a Unicode whitespace code point that {@link String#isBlank()} and {@link
   * String#strip()} recognise (via {@link Character#isWhitespace(int)}) but {@link String#trim()}
   * does NOT (trim only removes code points {@code <= U+0020}). It is the operative distinction
   * between the strip-based collapse and the old trim-based note idioms.
   */
  private static final String EM_SPACE = Character.toString(0x2003);

  /**
   * Non-breaking space (U+00A0): a space character that is deliberately NOT {@link
   * Character#isWhitespace(int)}, so {@code isBlank()} treats it as content and {@code strip()}
   * leaves it in place — the edge that proves the collapse keys on {@code isWhitespace}, not on
   * "looks like a space".
   */
  private static final String NBSP = Character.toString(0x00A0);

  @Test
  void normalizeAndCap_collapsesCombiningSequenceToNfc() {
    String input = "caf" + DECOMPOSED_E_ACUTE;
    assertEquals(5, input.length(), "precondition: decomposed input is five code units");

    String result =
        StringNormalization.normalizeAndCap(input, StringNormalization.MAX_FREE_TEXT_LENGTH);

    assertEquals("caf" + PRECOMPOSED_E_ACUTE, result);
    assertEquals(4, result.length(), "NFC collapses the two-code-unit accent into one");
  }

  @Test
  void normalizeAndCap_passesThroughWhenWithinCap() {
    String input = "a".repeat(10);

    assertEquals(input, StringNormalization.normalizeAndCap(input, 10));
  }

  @Test
  void normalizeAndCap_throwsWhenExceedingCap() {
    String input = "a".repeat(11);

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> StringNormalization.normalizeAndCap(input, 10));
    assertEquals("String exceeds maximum allowed length of 10", ex.getMessage());
  }

  @Test
  void normalizeAndCap_measuresLengthAfterNormalization() {
    String input = DECOMPOSED_E_ACUTE.repeat(10);
    assertEquals(20, input.length(), "precondition: decomposed input is twenty code units");

    String result = StringNormalization.normalizeAndCap(input, 10);
    assertEquals(PRECOMPOSED_E_ACUTE.repeat(10), result);
  }

  @Test
  void blankToNull_collapsesNullAndIsWhitespaceBlankButLeavesContentUnstripped() {
    assertNull(StringNormalization.blankToNull(null));
    assertNull(StringNormalization.blankToNull(""));
    assertNull(StringNormalization.blankToNull("   "));
    assertNull(StringNormalization.blankToNull(EM_SPACE));

    assertEquals(NBSP, StringNormalization.blankToNull(NBSP));

    assertEquals("  x  ", StringNormalization.blankToNull("  x  "));
    assertEquals("hello", StringNormalization.blankToNull("hello"));
  }

  @Test
  void trimToNull_collapsesBlankAndStripsIsWhitespaceFromContent() {
    assertNull(StringNormalization.trimToNull(null));
    assertNull(StringNormalization.trimToNull(""));
    assertNull(StringNormalization.trimToNull("   "));
    assertNull(StringNormalization.trimToNull(EM_SPACE));

    assertEquals("note", StringNormalization.trimToNull("  note  "));
    assertEquals("note", StringNormalization.trimToNull(EM_SPACE + "note" + EM_SPACE));
    assertEquals("a b", StringNormalization.trimToNull(" a b "));

    assertEquals(NBSP + "note" + NBSP, StringNormalization.trimToNull(NBSP + "note" + NBSP));
  }

  @Test
  void normalize_trimsAppliesEmptyPolicyThenNfcAndCaps() {
    assertNull(StringNormalization.normalize(null, 10, true));
    assertNull(StringNormalization.normalize(null, 10, false));

    assertNull(StringNormalization.normalize("   ", 10, true));
    assertEquals(NBSP, StringNormalization.normalize(NBSP, 10, true));

    assertEquals("", StringNormalization.normalize("   ", 10, false));

    assertEquals(
        "caf" + PRECOMPOSED_E_ACUTE,
        StringNormalization.normalize("  caf" + DECOMPOSED_E_ACUTE + "  ", 10, true));

    assertThrows(
        IllegalArgumentException.class,
        () -> StringNormalization.normalize("a".repeat(11), 10, true));
  }
}
