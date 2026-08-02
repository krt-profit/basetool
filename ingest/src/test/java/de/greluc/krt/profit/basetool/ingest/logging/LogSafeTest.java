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
 * Unit tests for the log-injection guard. The gateway is the only internet-facing module and its
 * accepted payload carries free-text provenance fields, so a value that reaches a log line must not
 * be able to forge a second one — a fabricated {@code ERROR} line in the ingest log would be taken
 * at face value during an incident.
 */
class LogSafeTest {

  @Test
  void replacesEveryControlCharacterSoASecondLineCannotBeForged() {
    String forged = "krt-extractor\n2026-08-02 07:00:00.000 ERROR --- fabricated";

    String safe = LogSafe.text(forged, 200);

    assertThat(safe).doesNotContain("\n").doesNotContain("\r");
    assertThat(safe).startsWith("krt-extractor?");
  }

  @Test
  void replacesCarriageReturnsAndTabsToo() {
    assertThat(LogSafe.text("a\r\nb\tc", 50)).isEqualTo("a??b?c");
  }

  @Test
  void capsAnOverlongValueAndMarksTheCut() {
    String safe = LogSafe.text("x".repeat(500), 10);

    assertThat(safe).startsWith("xxxxxxxxxx").hasSize(11);
    assertThat(safe).endsWith("…");
  }

  @Test
  void doesNotMarkAValueThatFitsExactly() {
    assertThat(LogSafe.text("1234567890", 10)).isEqualTo("1234567890");
  }

  @Test
  void rendersNullAndBlankAsAStableToken() {
    // A stable token keeps the field count of the log line constant, so a line stays greppable.
    assertThat(LogSafe.text(null, 10)).isEqualTo(LogSafe.NONE);
    assertThat(LogSafe.text("", 10)).isEqualTo(LogSafe.NONE);
    assertThat(LogSafe.text("   ", 10)).isEqualTo(LogSafe.NONE);
  }

  @Test
  void leavesOrdinaryProvenanceTextUntouched() {
    assertThat(LogSafe.text("krt-extractor 1.4.2-beta+build.7", 60))
        .isEqualTo("krt-extractor 1.4.2-beta+build.7");
  }
}
