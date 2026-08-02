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

package de.greluc.krt.profit.basetool.ingest.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Direct tests for the masker's contract, complementing the layout/encoder tests that exercise it
 * through logback. Two properties matter beyond "it scrubs": it must be allocation-free for the
 * overwhelmingly common PII-free line (the encoder's identity check depends on the input being
 * returned unchanged), and it must never throw on a degenerate input — it runs on every single log
 * event, so an exception here would take out logging itself.
 */
class PiiMaskerTest {

  @Test
  void returnsNullUnchanged() {
    assertThat(PiiMasker.mask(null)).isNull();
  }

  @Test
  void returnsAnEmptyStringUnchanged() {
    assertThat(PiiMasker.mask("")).isEmpty();
  }

  @Test
  void returnsAPiiFreeLineAsTheSameInstance() {
    // The encoder returns the original bytes when the masked text equals the input; keeping the
    // identity here is what makes that check free for the common case.
    String line = "Staged REFINERY handoff (sub=u-1a2b3c, hid=h-4d5e6f, ttl=PT30M)";

    assertThat(PiiMasker.mask(line)).isSameAs(line);
  }

  @Test
  void keepsTheKeywordAndMasksOnlyTheSecret() {
    assertThat(PiiMasker.mask("token=abc123DEF")).isEqualTo("token=***");
  }

  @Test
  void masksEveryOccurrenceInOneLine() {
    String masked = PiiMasker.mask("from a@b.de to c@d.de");

    assertThat(masked).doesNotContain("a@b.de", "c@d.de");
    assertThat(masked).isEqualTo("from ***@***.*** to ***@***.***");
  }

  @Test
  void leavesAnAtSignWithoutATldAlone() {
    // The domain pattern requires a TLD label; a bare '@' string is not an address and must not be
    // mangled — this is also the shape that used to backtrack quadratically (security audit L5).
    String line = "queue@" + "a".repeat(200);

    assertThat(PiiMasker.mask(line)).isEqualTo(line);
  }

  @Test
  void masksAStandaloneJwt() {
    String jwt =
        "eyJhbGciOiJIUzI1NiIsInR5cCI.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZ"
            + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

    assertThat(PiiMasker.mask("relaying " + jwt)).isEqualTo("relaying JWT_***");
  }
}
