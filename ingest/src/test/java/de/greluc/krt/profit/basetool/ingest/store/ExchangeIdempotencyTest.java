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

package de.greluc.krt.profit.basetool.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The request fingerprint of the idempotency cache (REQ-XCH-020). */
class ExchangeIdempotencyTest {

  @ParameterizedTest
  @ValueSource(strings = {"", "{\"ops\":[]}", "{\"ref\":{\"name\":\"Ölfass ä\"}}"})
  void theFingerprintIsTheSha256OfTheHeadLineFollowedByTheBody(String body) throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream concatenated = new ByteArrayOutputStream();
    concatenated.write(
        "POST /exchange/v1/me/blueprints/changes\n".getBytes(StandardCharsets.UTF_8));
    concatenated.write(bytes);
    String expected =
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(concatenated.toByteArray()));

    assertThat(ExchangeIdempotency.fingerprint("POST", "/exchange/v1/me/blueprints/changes", bytes))
        .isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"a\":1}", "{\"a\":2}"})
  void aDifferentBodyOrPathGivesADifferentFingerprint(String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

    assertThat(ExchangeIdempotency.fingerprint("POST", "/x", bytes))
        .isNotEqualTo(ExchangeIdempotency.fingerprint("POST", "/y", bytes))
        .isNotEqualTo(
            ExchangeIdempotency.fingerprint(
                "POST", "/x", "{\"a\":3}".getBytes(StandardCharsets.UTF_8)));
  }
}
