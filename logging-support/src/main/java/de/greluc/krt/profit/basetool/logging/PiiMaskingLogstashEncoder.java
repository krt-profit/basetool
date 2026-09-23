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

package de.greluc.krt.profit.basetool.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.nio.charset.StandardCharsets;
import net.logstash.logback.encoder.LogstashEncoder;

/**
 * {@link LogstashEncoder} extension that masks PII / secrets in the serialized JSON output via
 * {@link PiiMasker} before it is written to the appender. It is the encoder of the prod JSON sink
 * of all three applications ({@code logs/backend.json}, {@code logs/frontend.json}, {@code
 * logs/ingest.json}), each of which used the stock, unmasked {@code LogstashEncoder} once (audit
 * M-5 for the frontend, epic #936 Phase 1 for the ingest gateway).
 *
 * <p>The base {@code LogstashEncoder} has no extension point that operates on the fully rendered
 * JSON, so this encoder post-processes the byte array. Since the masker only ever produces
 * alphanumeric replacements (no quotes, backslashes or control characters), applying it to a
 * serialized JSON document leaves the JSON syntactically valid — the affected string values get
 * replaced 1:1 with shorter placeholders.
 */
public class PiiMaskingLogstashEncoder extends LogstashEncoder {

  /**
   * Encodes {@code event} through the stock JSON encoder, then masks the rendered document. The
   * original byte array is returned untouched when the masker found nothing, so the common PII-free
   * event costs one decode and one scan but no second encode.
   *
   * @param event the logging event to render
   * @return the masked JSON bytes, or the encoder's own bytes when nothing had to be masked
   */
  @Override
  public byte[] encode(ILoggingEvent event) {
    byte[] raw = super.encode(event);
    if (raw == null || raw.length == 0) {
      return raw;
    }
    String json = new String(raw, StandardCharsets.UTF_8);
    String masked = PiiMasker.mask(json);
    // Audit finding L-7: the earlier `masked == json` reference check relied on the fragile
    // invariant that PiiMasker#mask returns the same String instance when there is no PII to
    // scrub. Compare by value so a future refactor that returns a new instance for the no-match
    // case does not silently force a re-encode round-trip per log event.
    if (json.equals(masked)) {
      return raw;
    }
    return masked.getBytes(StandardCharsets.UTF_8);
  }
}
