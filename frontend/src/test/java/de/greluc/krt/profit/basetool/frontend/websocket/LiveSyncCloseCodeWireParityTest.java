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

package de.greluc.krt.profit.basetool.frontend.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Build-time parity guard for the application-defined WebSocket close codes.
 *
 * <p>Each code is a mirror point in the same sense as the deny {@code reason} ({@link
 * LiveSyncDenyReasonWireParityTest}) and the live-sync section maps: the server declares it in
 * {@link LiveSyncWebSocketHandler}, {@code krt-live-sync.js} compares the received {@code
 * CloseEvent.code} against its own literal, and a mismatch fails <em>silently</em> in the worst
 * possible direction — the close falls through to the generic reconnect path, which is precisely
 * the loop these codes exist to break. Both codes get the same guard because a drifting number
 * looks identical either way in a review diff.
 */
class LiveSyncCloseCodeWireParityTest {

  /** The client's declaration of the consent-gate close code, e.g. {@code = 4003;}. */
  private static final Pattern CLIENT_TERMS_GATE_CODE =
      Pattern.compile("TERMS_GATE_CLOSE_CODE\\s*=\\s*(\\d{4})");

  /** The client's declaration of the per-user socket-cap close code, e.g. {@code = 4029;}. */
  private static final Pattern CLIENT_SOCKET_CAP_CODE =
      Pattern.compile("SOCKET_CAP_CLOSE_CODE\\s*=\\s*(\\d{4})");

  @Test
  void clientConsentGateCloseCode_matchesTheServerValue() throws IOException {
    assertThat(declaredCode(CLIENT_TERMS_GATE_CODE, "TERMS_GATE_CLOSE_CODE"))
        .as("krt-live-sync.js vs LiveSyncWebSocketHandler.TERMS_CONSENT_REQUIRED_CODE")
        .isEqualTo(LiveSyncWebSocketHandler.TERMS_CONSENT_REQUIRED_CODE);
  }

  @Test
  void clientSocketCapCloseCode_matchesTheServerValue() throws IOException {
    assertThat(declaredCode(CLIENT_SOCKET_CAP_CODE, "SOCKET_CAP_CLOSE_CODE"))
        .as("krt-live-sync.js vs LiveSyncWebSocketHandler.SOCKET_CAP_EXCEEDED")
        .isEqualTo(LiveSyncWebSocketHandler.SOCKET_CAP_EXCEEDED.getCode());
  }

  /**
   * The two codes must stay distinct, because the client's two branches are opposites: the cap
   * refusal backs off to the maximum interval and keeps probing for a freed slot, while the consent
   * refusal stops for good and navigates away. Collapsing them onto one number would silently pick
   * whichever branch is written first — either a permanent loop or a tab that never reconnects.
   */
  @Test
  void theTwoCloseCodesAreDistinct() {
    assertThat(LiveSyncWebSocketHandler.TERMS_CONSENT_REQUIRED_CODE)
        .isNotEqualTo(LiveSyncWebSocketHandler.SOCKET_CAP_EXCEEDED.getCode());
  }

  /**
   * Extracts a close code the client declares.
   *
   * @param pattern the declaration pattern, with the numeric value in group 1
   * @param constantName the client-side constant name, for the failure message
   * @return the declared code
   * @throws IOException if the script cannot be read from the classpath
   */
  private static int declaredCode(Pattern pattern, String constantName) throws IOException {
    Matcher declaration = pattern.matcher(readResource("/static/js/krt-live-sync.js"));
    assertThat(declaration.find())
        .as("krt-live-sync.js declares %s = <code>", constantName)
        .isTrue();
    return Integer.parseInt(declaration.group(1));
  }

  private static String readResource(String resource) throws IOException {
    try (InputStream in = LiveSyncCloseCodeWireParityTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("classpath resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
