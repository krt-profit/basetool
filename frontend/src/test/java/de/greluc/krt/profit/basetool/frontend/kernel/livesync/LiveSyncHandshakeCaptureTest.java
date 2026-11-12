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

package de.greluc.krt.profit.basetool.frontend.kernel.livesync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The values a live-sync handshake captured never print the access token (REQ-OBS-004). */
class LiveSyncHandshakeCaptureTest {

  @Test
  void toStringRedactsTheAccessToken() {
    UUID pin = UUID.randomUUID();
    LiveSyncSubscriptions.HandshakeCapture capture =
        new LiveSyncSubscriptions.HandshakeCapture(
            "eyJ-access-token", pin, Set.of("ROLE_KRT_MEMBER"), null);

    assertThat(capture.toString())
        .doesNotContain("eyJ-access-token")
        .contains("token=<redacted>")
        .contains(pin.toString());
  }

  @Test
  void toStringShowsAMissingTokenAsNull() {
    assertThat(new LiveSyncSubscriptions.HandshakeCapture(null, null, null, null).toString())
        .contains("token=null");
  }
}
