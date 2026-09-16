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
}
