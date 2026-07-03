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
 * Unit tests for {@link StringNormalization}, the shared NFC-normalize-and-length-cap step
 * extracted from {@code NormalizedStringDeserializer} and {@code NormalizedStringEditor} plus the
 * blank-collapse primitives ({@link StringNormalization#blankToNull(String)}, {@link
 * StringNormalization#trimToNull(String)}) and the full {@link
 * StringNormalization#normalize(String, int, boolean) normalize} pipeline the service layer and
 * form editor reuse. The accent code points are built via {@link Character#toString(int)} rather
 * than literals or escaped unicode, so the decomposed-vs-precomposed distinction the tests hinge on
 * is unambiguous.
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
    // Given a decomposed grapheme that is longer in code units than its precomposed form
    String input = "caf" + DECOMPOSED_E_ACUTE;
    assertEquals(5, input.length(), "precondition: decomposed input is five code units");

    // When
    String result =
        StringNormalization.normalizeAndCap(input, StringNormalization.MAX_FREE_TEXT_LENGTH);

    // Then it canonicalizes to the single-code-point NFC form
    assertEquals("caf" + PRECOMPOSED_E_ACUTE, result);
    assertEquals(4, result.length(), "NFC collapses the two-code-unit accent into one");
  }

  @Test
  void normalizeAndCap_passesThroughWhenWithinCap() {
    // Given a value exactly at the cap
    String input = "a".repeat(10);

    // When / Then it is returned unchanged
    assertEquals(input, StringNormalization.normalizeAndCap(input, 10));
  }

  @Test
  void normalizeAndCap_throwsWhenExceedingCap() {
    // Given a value one character over the cap
    String input = "a".repeat(11);

    // When / Then the length guard fires
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> StringNormalization.normalizeAndCap(input, 10));
    assertEquals("String exceeds maximum allowed length of 10", ex.getMessage());
  }

  @Test
  void normalizeAndCap_measuresLengthAfterNormalization() {
    // Given 10 decomposed accents (20 code units) that collapse to 10 precomposed ones
    String input = DECOMPOSED_E_ACUTE.repeat(10);
    assertEquals(20, input.length(), "precondition: decomposed input is twenty code units");

    // When capped at 10 — the pre-normalization length (20) would exceed it, the NFC length (10)
    // does not — Then the guard must use the post-normalization length and accept it
    String result = StringNormalization.normalizeAndCap(input, 10);
    assertEquals(PRECOMPOSED_E_ACUTE.repeat(10), result);
  }

  @Test
  void blankToNull_collapsesNullAndIsWhitespaceBlankButLeavesContentUnstripped() {
    // Null / empty / ASCII-whitespace / isWhitespace-only (em space) all collapse to null
    assertNull(StringNormalization.blankToNull(null));
    assertNull(StringNormalization.blankToNull(""));
    assertNull(StringNormalization.blankToNull("   "));
    assertNull(StringNormalization.blankToNull(EM_SPACE));

    // A non-breaking space is NOT isWhitespace, so it counts as content and is kept
    assertEquals(NBSP, StringNormalization.blankToNull(NBSP));

    // A value with content is returned byte-for-byte — surrounding whitespace is NOT stripped
    assertEquals("  x  ", StringNormalization.blankToNull("  x  "));
    assertEquals("hello", StringNormalization.blankToNull("hello"));
  }

  @Test
  void trimToNull_collapsesBlankAndStripsIsWhitespaceFromContent() {
    // Null / empty / ASCII-whitespace / isWhitespace-only (em space) all collapse to null
    assertNull(StringNormalization.trimToNull(null));
    assertNull(StringNormalization.trimToNull(""));
    assertNull(StringNormalization.trimToNull("   "));
    assertNull(StringNormalization.trimToNull(EM_SPACE));

    // Content is stripped of ASCII and Unicode edge whitespace (em space), interior kept
    assertEquals("note", StringNormalization.trimToNull("  note  "));
    assertEquals("note", StringNormalization.trimToNull(EM_SPACE + "note" + EM_SPACE));
    assertEquals("a b", StringNormalization.trimToNull(" a b "));

    // A non-breaking space is NOT isWhitespace: strip() leaves it, so it survives as content
    assertEquals(NBSP + "note" + NBSP, StringNormalization.trimToNull(NBSP + "note" + NBSP));
  }

  @Test
  void normalize_trimsAppliesEmptyPolicyThenNfcAndCaps() {
    // Null stays null regardless of the flag
    assertNull(StringNormalization.normalize(null, 10, true));
    assertNull(StringNormalization.normalize(null, 10, false));

    // emptyAsNull=true collapses a trimmed-empty (ASCII) value to null; the NBSP survives trim so
    // it is NOT collapsed (mirrors NormalizedStringEditor's ASCII-only empty policy)
    assertNull(StringNormalization.normalize("   ", 10, true));
    assertEquals(NBSP, StringNormalization.normalize(NBSP, 10, true));

    // emptyAsNull=false keeps a trimmed-empty value as the empty string
    assertEquals("", StringNormalization.normalize("   ", 10, false));

    // Content is trimmed, NFC-normalized and length-checked
    assertEquals(
        "caf" + PRECOMPOSED_E_ACUTE,
        StringNormalization.normalize("  caf" + DECOMPOSED_E_ACUTE + "  ", 10, true));

    // The cap is enforced on the post-trim, post-NFC length
    assertThrows(
        IllegalArgumentException.class,
        () -> StringNormalization.normalize("a".repeat(11), 10, true));
  }
}
