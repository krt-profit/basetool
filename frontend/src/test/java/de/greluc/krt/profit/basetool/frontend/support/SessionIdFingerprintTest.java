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

package de.greluc.krt.profit.basetool.frontend.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/**
 * Pins the shape of {@link SessionIdFingerprint}: a short, stable, one-way stand-in for a session
 * id that log lines can carry instead of the bearer credential itself (APPSEC-12).
 */
class SessionIdFingerprintTest {

  private static final String SESSION_ID = "4f1c2a9e-7d3b-4c55-9a0e-2b6d8f1e3c77";

  @Test
  void theFingerprintIsTwelveLowercaseHexCharactersAndNeverTheIdItself() {
    String fingerprint = SessionIdFingerprint.of(SESSION_ID);

    assertTrue(fingerprint.matches("[0-9a-f]{12}"), "unexpected shape: " + fingerprint);
    assertFalse(SESSION_ID.contains(fingerprint), "the fingerprint must not be a substring");
  }

  @Test
  void theSameIdAlwaysYieldsTheSameFingerprintSoLinesStillCorrelate() {
    assertEquals(SessionIdFingerprint.of(SESSION_ID), SessionIdFingerprint.of(SESSION_ID));
    assertNotEquals(SessionIdFingerprint.of(SESSION_ID), SessionIdFingerprint.of(SESSION_ID + "x"));
  }

  @Test
  void itIsTheTruncatedSha256OfTheId() {
    // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
    assertEquals("ba7816bf8f01", SessionIdFingerprint.of("abc"));
  }

  @Test
  void anAbsentOrBlankIdOrSessionReadsAsNone() {
    assertEquals(SessionIdFingerprint.NONE, SessionIdFingerprint.of((String) null));
    assertEquals(SessionIdFingerprint.NONE, SessionIdFingerprint.of(" "));
    assertEquals(SessionIdFingerprint.NONE, SessionIdFingerprint.of((HttpSession) null));
  }

  @Test
  void aSessionIsFingerprintedByItsId() {
    MockHttpSession session = new MockHttpSession(null, SESSION_ID);

    assertEquals(SessionIdFingerprint.of(SESSION_ID), SessionIdFingerprint.of(session));
  }
}
