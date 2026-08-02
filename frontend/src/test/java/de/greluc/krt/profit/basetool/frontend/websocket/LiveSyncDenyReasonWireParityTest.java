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

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Build-time parity guard for the subscribe-deny {@code reason} that now rides the {@code denied}
 * control frame.
 *
 * <p>The value is a mirror point in the same sense as the live-sync section maps: the server writes
 * it from {@link MetricNames#SUBSCRIBE_DENY_INDETERMINATE} in {@code LiveSyncWebSocketHandler}, and
 * {@code krt-live-sync.js} compares the received frame against its own literal to decide whether
 * the refusal earns its single reconnect retry. If the two drift, nothing fails and nothing logs:
 * the retry silently stops happening and a transient backend blip is back to permanently stripping
 * live sync from a tab — exactly the defect the reason was introduced to fix. This turns that drift
 * into a red build.
 */
class LiveSyncDenyReasonWireParityTest {

  /** The client's declaration of the retryable deny reason, e.g. {@code = 'indeterminate';}. */
  private static final Pattern CLIENT_DECLARATION =
      Pattern.compile("DENY_REASON_INDETERMINATE\\s*=\\s*'([\\w-]+)'");

  @Test
  void clientRetryableDenyReason_matchesTheServerValue() throws IOException {
    String js = readResource("/static/js/krt-live-sync.js");
    Matcher declaration = CLIENT_DECLARATION.matcher(js);
    assertThat(declaration.find())
        .as("krt-live-sync.js declares DENY_REASON_INDETERMINATE = '<value>'")
        .isTrue();
    assertThat(declaration.group(1))
        .as("the client's retryable deny reason vs MetricNames.SUBSCRIBE_DENY_INDETERMINATE")
        .isEqualTo(MetricNames.SUBSCRIBE_DENY_INDETERMINATE);
  }

  @Test
  void clientTreatsOnlyTheIndeterminateReasonAsRetryable() throws IOException {
    String js = readResource("/static/js/krt-live-sync.js");
    // The authorization deny is a real permission verdict and must stay terminal: the client must
    // not branch on it at all. A literal 'authz' appearing in the module would mean someone taught
    // the client to react to it, which is the point at which a denied room starts re-subscribing in
    // a loop against a backend that will keep refusing it.
    assertThat(js)
        .as(
            "krt-live-sync.js must not branch on the terminal '%s' deny reason",
            MetricNames.SUBSCRIBE_DENY_AUTHZ)
        .doesNotContain("'" + MetricNames.SUBSCRIBE_DENY_AUTHZ + "'");
  }

  private static String readResource(String resource) throws IOException {
    try (InputStream in = LiveSyncDenyReasonWireParityTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("classpath resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
