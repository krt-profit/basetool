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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.model.dto.PingResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the values {@link SystemController} returns.
 *
 * <ul>
 *   <li>v1 ping returns the {@code Map<String,String>} body {@code status=UP / version=v1 /
 *       message=pong} verbatim.
 *   <li>v2 ping returns a {@link PingResponse} with the same triple plus a UTC timestamp.
 * </ul>
 */
class SystemControllerTest {

  private final SystemController controller = new SystemController();

  @Test
  void pingV1_returnsLegacyMapBodyVerbatim() {
    Map<String, String> body = controller.pingV1();

    assertNotNull(body);
    assertEquals("UP", body.get("status"));
    assertEquals("v1", body.get("version"));
    assertEquals("pong", body.get("message"));
    assertEquals(
        3, body.size(), "v1 ping must NOT add new fields — the v2 carve-out exists for that");
  }

  @Test
  void pingV2_returnsPingResponseRecordWithUtcTimestamp() {
    Instant before = Instant.now();
    PingResponse response = controller.pingV2();
    Instant after = Instant.now();

    assertNotNull(response);
    assertEquals("UP", response.status());
    assertEquals("v2", response.version());
    assertEquals("pong", response.message());

    assertNotNull(response.timestamp());
    assertFalse(
        response.timestamp().isBefore(before), "Timestamp must be at-or-after the call started");
    assertFalse(
        response.timestamp().isAfter(after), "Timestamp must be at-or-before the call returned");
  }
}
