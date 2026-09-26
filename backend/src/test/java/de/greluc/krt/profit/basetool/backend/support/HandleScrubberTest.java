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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link HandleScrubber}, which removes other members' handles from the free text of
 * a data export (REQ-SEC-058). Pins that the longest match wins and that case is ignored.
 */
class HandleScrubberTest {

  @Test
  void replacesAThirdPartyHandle() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Valkyrie"));

    assertThat(scrubber.scrub("Material an Valkyrie uebergeben"))
        .isEqualTo("Material an " + HandleScrubber.REPLACEMENT + " uebergeben");
  }

  @Test
  void ignoresCase() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Valkyrie"));

    assertThat(scrubber.scrub("an VALKYRIE und valkyrie"))
        .isEqualTo("an " + HandleScrubber.REPLACEMENT + " und " + HandleScrubber.REPLACEMENT);
  }

  @Test
  void theLongestHandleWins() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Val", "Valkyrie"));

    String result = scrubber.scrub("Valkyrie war dabei");

    assertThat(result).isEqualTo(HandleScrubber.REPLACEMENT + " war dabei");
    assertThat(result).doesNotContain("kyrie");
  }

  @Test
  void aMatchMustBeFlankedByWordBoundaries() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Ore"));

    assertThat(scrubber.scrub("Erz sortiert, Store gefuellt"))
        .as("inside a word is not a mention of the person")
        .isEqualTo("Erz sortiert, Store gefuellt");
    assertThat(scrubber.scrub("Ore aus Daymar"))
        .as("at the start, flanked by the string edge and a space")
        .isEqualTo(HandleScrubber.REPLACEMENT + " aus Daymar");
    assertThat(scrubber.scrub("uebergeben an Ore"))
        .as("at the end")
        .isEqualTo("uebergeben an " + HandleScrubber.REPLACEMENT);
    assertThat(scrubber.scrub("an @Ore, danke"))
        .as("punctuation is a boundary, so a mention beside it still matches")
        .isEqualTo("an @" + HandleScrubber.REPLACEMENT + ", danke");
  }

  @Test
  void theSubjectsOwnNameIsMatchedAndPassedThroughVerbatim() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Val"), List.of("Valkyrie"));

    assertThat(scrubber.scrub("Notiz von Valkyrie"))
        .as("the subject's own longer name wins and is emitted unchanged")
        .isEqualTo("Notiz von Valkyrie");
    assertThat(scrubber.scrub("Val war auch da"))
        .as("and the third party is still replaced")
        .isEqualTo(HandleScrubber.REPLACEMENT + " war auch da");
  }

  @Test
  void aScrubberWithOnlyProtectedNamesIsInactive() {
    assertThat(new HandleScrubber(List.of(), List.of("Valkyrie")).isActive()).isFalse();
  }

  @Test
  void aTermStartingWithAWideCaseFoldingCharacterIsStillFound() {
    HandleScrubber scrubber = new HandleScrubber(List.of("KELVIN".replace("K", "\u212A")));

    assertThat(scrubber.scrub("uebergeben an kelvin heute"))
        .isEqualTo("uebergeben an " + HandleScrubber.REPLACEMENT + " heute");
  }

  @Test
  void anOrdinaryTermIsFoundWhereTheTextUsesTheWideCharacter() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Kelvin"));

    assertThat(scrubber.scrub("uebergeben an " + "Kelvin".replace("K", "\u212A") + " heute"))
        .isEqualTo("uebergeben an " + HandleScrubber.REPLACEMENT + " heute");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Islay|\u0130slay",
        "Islay|\u0131slay",
        "\u03c3igma|\u03c2igma",
        "\u00dfeta|\u1e9eeta",
      })
  void aTermIsFoundWhereTheTextSubstitutesAWideCaseFolding(String handle, String written) {
    HandleScrubber scrubber = new HandleScrubber(List.of(handle));

    assertThat(scrubber.scrub("notiz von " + written))
        .as("%s written as %s", handle, written)
        .isEqualTo("notiz von " + HandleScrubber.REPLACEMENT);
  }

  @Test
  void theLongestTermStillWinsAcrossTheFoldedBuckets() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Val", "Valkyrie"));

    assertThat(scrubber.scrub("Valkyrie war dabei"))
        .isEqualTo(HandleScrubber.REPLACEMENT + " war dabei");
  }

  @Test
  void skipsHandlesTooShortToMatchSafely() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Al", "X"));

    assertThat(scrubber.isActive()).isFalse();
    assertThat(scrubber.scrub("Alles klar, Material abgeholt"))
        .isEqualTo("Alles klar, Material abgeholt");
  }

  @Test
  void leavesTextWithoutAMatchAlone() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Valkyrie"));

    assertThat(scrubber.scrub("Nichts zu sehen")).isEqualTo("Nichts zu sehen");
  }

  @Test
  void toleratesNullAndEmptyInput() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Valkyrie"));

    assertThat(scrubber.scrub(null)).isNull();
    assertThat(scrubber.scrub("")).isEmpty();
  }

  @Test
  void anEmptyHandleListIsInactive() {
    assertThat(new HandleScrubber(List.of()).isActive()).isFalse();
    assertThat(new HandleScrubber(List.of()).scrub("Valkyrie")).isEqualTo("Valkyrie");
  }

  @Test
  void replacesEveryOccurrence() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Nova"));

    assertThat(scrubber.scrub("Nova, dann Nova, zuletzt Nova"))
        .isEqualTo(
            HandleScrubber.REPLACEMENT
                + ", dann "
                + HandleScrubber.REPLACEMENT
                + ", zuletzt "
                + HandleScrubber.REPLACEMENT);
  }

  @Test
  void aCharacterThatChangesLengthWhenLowerCasedDoesNotShiftTheSplice() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Valkyrie"));

    String result = scrubber.scrub("\u0130 hat mit Valkyrie geredet");

    assertThat(result).isEqualTo("\u0130 hat mit " + HandleScrubber.REPLACEMENT + " geredet");
    assertThat(result).as("no prefix of the handle survives").doesNotContain("V");
  }

  @Test
  void aMatchAtTheEndAfterSuchACharacterDoesNotThrow() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Valkyrie"));

    assertThat(scrubber.scrub("\u0130 Valkyrie")).isEqualTo("\u0130 " + HandleScrubber.REPLACEMENT);
  }

  @Test
  void aHandleThatIsASubstringOfTheReplacementLeavesItIntact() {
    String insideTheToken = HandleScrubber.REPLACEMENT.substring(1, 6);
    assertThat(insideTheToken.length())
        .as("the fixture only means anything if it clears the minimum length")
        .isGreaterThanOrEqualTo(HandleScrubber.MIN_HANDLE_LENGTH);

    HandleScrubber scrubber = new HandleScrubber(List.of("Valkyrie", insideTheToken));

    String result = scrubber.scrub("Uebergabe an Valkyrie erledigt");

    assertThat(result).isEqualTo("Uebergabe an " + HandleScrubber.REPLACEMENT + " erledigt");
    assertThat(result)
        .as("exactly one replacement, not one nested in another")
        .containsOnlyOnce(HandleScrubber.REPLACEMENT);
  }

  @Test
  void adjacentOccurrencesAreBothReplaced() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Nova"));

    assertThat(scrubber.scrub("Nova/Nova"))
        .isEqualTo(HandleScrubber.REPLACEMENT + "/" + HandleScrubber.REPLACEMENT);
    assertThat(scrubber.scrub("NovaNova")).isEqualTo("NovaNova");
  }
}
