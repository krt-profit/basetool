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

package de.greluc.krt.profit.basetool.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IngestProperties} defaults. Guards the handoff TTL default so a future edit
 * cannot silently revert it below the value the one-click ingest flow needs (REQ-INGEST-003).
 */
class IngestPropertiesTest {

  /**
   * The handoff TTL default is 30 minutes, not the original 5. Staging happens when the user clicks
   * Send in the extractor, but opening the pre-filled page is a separate manual click afterwards,
   * so a 5-minute window expired before pickup for slower users and surfaced as "Import-Link
   * abgelaufen oder ungültig" on every send (REQ-INGEST-003). This locks the widened default in
   * place.
   */
  @Test
  void handoffTtlDefaultsToThirtyMinutes() {
    // Given a freshly constructed properties holder (no external property binding)
    IngestProperties properties = new IngestProperties();

    // When / Then the code default is the widened 30-minute window
    assertThat(properties.getHandoffTtl()).isEqualTo(Duration.ofMinutes(30));
  }
}
