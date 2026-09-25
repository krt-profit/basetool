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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Build-time pin of how {@code notifications.js} stops reconnecting its push stream once the
 * session is gone (REQ-NOTIF-010, REQ-SEC-012).
 *
 * <p>The server refuses an anonymous stream with {@code 401} + {@code X-Reauthenticate} ({@code
 * AnonymousSurfaceSweepMvcTest#anonymousEventSourceGets401}). An {@code EventSource} cannot read
 * that status: it fires the same {@code error} for a 401 as for a network blip, and the module used
 * to answer every {@code error} with a reconnect a few seconds later. A tab whose session ended —
 * logout in another tab, expiry, the renamed session cookie of the v1.11.0 deploy — therefore kept
 * asking the stream every three to six seconds for as long as it stayed open, hidden tabs included,
 * where the badge poll that would have noticed is paused.
 *
 * <p>The fix is spread over three places in the module, and any one of them missing re-opens the
 * loop without a test or an error message saying so — hence this guard, in the technique of {@link
 * HandRolledFetchGateContractTest}:
 *
 * <ol>
 *   <li>a connect refused before it ever opened probes the session through the badge read;
 *   <li>that read stops the stream on a {@code 401}, before the re-auth helper — which may decline
 *       to navigate inside its loop guard — decides anything;
 *   <li>a reconnect that was already waiting re-checks the stop flag before it opens a new stream.
 * </ol>
 */
class NotificationStreamReconnectContractTest {

  /** The notification bell + unread-badge module. */
  private static final String NOTIFICATIONS_MODULE = "/static/js/notifications.js";

  /** The gate-aware reader, from its declaration to the re-auth hand-off. */
  private static final Pattern READER_UP_TO_REAUTH =
      Pattern.compile(
          "function readJson\\(res, fallback\\) \\{(.*?)krtReauth\\.check\\(res\\)",
          Pattern.DOTALL);

  /** The stream's {@code error} listener, up to the reconnect it schedules. */
  private static final Pattern ERROR_LISTENER =
      Pattern.compile(
          "source\\.addEventListener\\('error', function \\(\\)"
              + " \\{(.*?)scheduleSseReconnect\\(\\);",
          Pattern.DOTALL);

  /** The body of the reconnect timer's callback. */
  private static final Pattern RECONNECT_CALLBACK =
      Pattern.compile(
          "sseReconnectTimer = window\\.setTimeout\\(function \\(\\) \\{(.*?)\\}, delay\\);",
          Pattern.DOTALL);

  /** The body of {@code stopSse}. */
  private static final Pattern STOP_SSE =
      Pattern.compile("function stopSse\\(\\) \\{(.*?)\\n    \\}", Pattern.DOTALL);

  /**
   * A 401 on any badge read stops the stream, and does so before the re-auth helper runs: that
   * helper returns without navigating inside its ten-second loop guard, and a stop made only on its
   * success would then never happen.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void aRefusedReadStopsTheStreamBeforeTheReauthHelperDecides() throws IOException {
    String reader = group(READER_UP_TO_REAUTH, readModule(), "readJson up to krtReauth.check");

    assertThat(reader)
        .as("readJson must test for the 401 and stop the stream ahead of krtReauth.check")
        .containsPattern("res\\.status === 401\\)\\s*\\{\\s*(//[^\\n]*\\n\\s*)*stopSse\\(\\);");
  }

  /**
   * A connect refused before {@code open} is the only shape a 401 can take for an {@code
   * EventSource}, so it must trigger the probe — and must be counted, so repeated refusals back
   * off.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void aConnectRefusedBeforeOpenProbesTheSessionAndBacksOff() throws IOException {
    String module = readModule();
    String listener = group(ERROR_LISTENER, module, "the stream's error listener");

    assertThat(listener)
        .as("an error before open must probe the session through the badge read")
        .containsPattern(
            "if \\(!opened\\)\\s*\\{[^}]*sseRefusals \\+= 1;[^}]*refreshUnreadCount\\(\\);");
    assertThat(module)
        .as("the open listener must mark the stream opened and reset the refusal count")
        .containsPattern(
            "addEventListener\\('open', function \\(\\) \\{\\s*opened = true;\\s*sseRefusals = 0;");
    assertThat(module)
        .as("the reconnect delay must grow with consecutive refusals")
        .containsPattern("Math\\.pow\\(2, Math\\.min\\(sseRefusals, SSE_MAX_BACKOFF_STEPS\\)\\)");
  }

  /**
   * The probe answers while a reconnect is already waiting; the waiting callback must see the stop,
   * and {@code stopSse} must clear the timer and close any open source.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void aStoppedStreamIsNeverReopened() throws IOException {
    String module = readModule();

    assertThat(group(RECONNECT_CALLBACK, module, "the reconnect timer callback"))
        .as("the waiting reconnect must re-check the stop flag before it opens a stream")
        .containsPattern("if \\(!sseStopped\\)\\s*\\{\\s*startSse\\(\\);");
    assertThat(group(STOP_SSE, module, "stopSse"))
        .as("stopSse must set the flag, clear the pending reconnect and close the source")
        .contains("sseStopped = true;")
        .contains("window.clearTimeout(sseReconnectTimer);")
        .contains("sseSource.close();");
  }

  /**
   * Returns group 1 of the first match, failing loudly when the anchor is gone so a rename breaks
   * this guard instead of silently emptying it.
   *
   * @param pattern the anchored pattern
   * @param text the module source
   * @param what a description of the anchor for the failure message
   * @return the captured text
   */
  private static String group(Pattern pattern, String text, String what) {
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find())
        .as("%s not found in %s (anchor renamed?)", what, NOTIFICATIONS_MODULE)
        .isTrue();
    return matcher.group(1);
  }

  /**
   * Reads the module from the classpath as UTF-8 text, failing if it is missing.
   *
   * @return the module source
   * @throws IOException if the resource stream cannot be read
   */
  private static String readModule() throws IOException {
    try (InputStream in =
        NotificationStreamReconnectContractTest.class.getResourceAsStream(NOTIFICATIONS_MODULE)) {
      assertThat(in).as("classpath resource %s", NOTIFICATIONS_MODULE).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
