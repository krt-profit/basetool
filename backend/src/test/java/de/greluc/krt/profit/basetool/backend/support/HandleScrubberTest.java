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

/**
 * Unit tests for {@link HandleScrubber} — removing other members' handles from the free text that
 * goes into a data export (REQ-SEC-058).
 *
 * <p>The properties worth pinning down are the two that are easy to get wrong and hard to notice:
 * the <b>longest match wins</b> (or a shorter handle leaves a fragment of a longer one behind,
 * which is both a leak and a corruption), and <b>case is ignored</b> (whoever wrote the note was
 * typing, not copying from a roster).
 *
 * <p>The last three tests are regressions for defects this class did not catch the first time, and
 * each of them was reachable by any member from their own profile: a handle that is a substring of
 * the replacement token, and a single character in their own note. They are the reason the
 * implementation is one forward pass over the original text rather than a loop over the handles.
 */
class HandleScrubberTest {

  // covers REQ-SEC-058 — a third party named in the requester's own prose is removed
  @Test
  void replacesAThirdPartyHandle() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Valkyrie"));

    assertThat(scrubber.scrub("Material an Valkyrie uebergeben"))
        .isEqualTo("Material an " + HandleScrubber.REPLACEMENT + " uebergeben");
  }

  // covers REQ-SEC-058 — case-insensitive, because the text was typed
  @Test
  void ignoresCase() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Valkyrie"));

    assertThat(scrubber.scrub("an VALKYRIE und valkyrie"))
        .isEqualTo("an " + HandleScrubber.REPLACEMENT + " und " + HandleScrubber.REPLACEMENT);
  }

  // covers REQ-SEC-058 — the longest handle wins, or "Valkyrie" leaves "kyrie" behind
  @Test
  void theLongestHandleWins() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Val", "Valkyrie"));

    String result = scrubber.scrub("Valkyrie war dabei");

    assertThat(result).isEqualTo(HandleScrubber.REPLACEMENT + " war dabei");
    assertThat(result).doesNotContain("kyrie");
  }

  // covers REQ-SEC-058 — a two-character handle would shred every note in the export
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

  // covers REQ-SEC-058 — several occurrences in one value, not just the first
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

  // covers REQ-SEC-058 - regression: toLowerCase is not length-preserving
  @Test
  void aCharacterThatChangesLengthWhenLowerCasedDoesNotShiftTheSplice() {
    // U+0130 (LATIN CAPITAL LETTER I WITH DOT ABOVE) lowercases to two characters. The previous
    // implementation found the match in a lower-cased copy and spliced the original at that index,
    // so everything after such a character was off by one: the export kept the first letter of the
    // third party's handle and still reported the removal as complete.
    HandleScrubber scrubber = new HandleScrubber(List.of("Valkyrie"));

    String result = scrubber.scrub("\u0130 hat mit Valkyrie geredet");

    assertThat(result).isEqualTo("\u0130 hat mit " + HandleScrubber.REPLACEMENT + " geredet");
    assertThat(result).as("no prefix of the handle survives").doesNotContain("V");
  }

  // covers REQ-SEC-058 - regression: the same drift threw the export away entirely
  @Test
  void aMatchAtTheEndAfterSuchACharacterDoesNotThrow() {
    // Same cause, worse symptom: with the match at the end of the value the shifted offsets made
    // the final append run backwards and IndexOutOfBoundsException left the whole export as a 500.
    HandleScrubber scrubber = new HandleScrubber(List.of("Valkyrie"));

    assertThat(scrubber.scrub("\u0130Valkyrie")).isEqualTo("\u0130" + HandleScrubber.REPLACEMENT);
  }

  // covers REQ-SEC-058 - regression: the replacement must not be scrubbed by a later handle
  @Test
  void aHandleThatIsASubstringOfTheReplacementLeavesItIntact() {
    // The previous implementation scrubbed once per handle, each pass reading the previous pass's
    // output, so a short handle occurring inside the placeholder was substituted inside it --
    // eleven such handles turned a 30-character note into 1780 characters. displayName is
    // self-service, so any member could pick one and corrupt the free text in every other member's
    // export. The forward pass cannot re-read what it has emitted.
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

  // covers REQ-SEC-058 - a handle occurring twice adjacently is still two separate replacements
  @Test
  void adjacentOccurrencesAreBothReplaced() {
    HandleScrubber scrubber = new HandleScrubber(List.of("Nova"));

    assertThat(scrubber.scrub("NovaNova"))
        .isEqualTo(HandleScrubber.REPLACEMENT + HandleScrubber.REPLACEMENT);
  }
}
