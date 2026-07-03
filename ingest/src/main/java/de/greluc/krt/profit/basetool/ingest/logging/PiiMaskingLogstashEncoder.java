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

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.nio.charset.StandardCharsets;
import net.logstash.logback.encoder.LogstashEncoder;

/**
 * {@link LogstashEncoder} extension that masks PII / secrets in the serialized JSON output via
 * {@link PiiMasker} before it is written to the appender. Mirrors the backend/frontend encoders of
 * the same name — the ingest prod console sink previously used the stock {@code LogstashEncoder}
 * with no masking, making it the last unmasked log output in the system (closed by epic #936 Phase
 * 1 as a prerequisite for Loki ingestion, REQ-OBS-004/-007).
 *
 * <p>The base {@code LogstashEncoder} has no extension point that operates on the fully rendered
 * JSON, so this encoder post-processes the byte array. Since the masker only ever produces
 * alphanumeric replacements (no quotes, backslashes or control characters), applying it to a
 * serialized JSON document leaves the JSON syntactically valid — the affected string values get
 * replaced 1:1 with shorter placeholders.
 */
public class PiiMaskingLogstashEncoder extends LogstashEncoder {

  @Override
  public byte[] encode(ILoggingEvent event) {
    byte[] raw = super.encode(event);
    if (raw == null || raw.length == 0) {
      return raw;
    }
    String json = new String(raw, StandardCharsets.UTF_8);
    String masked = PiiMasker.mask(json);
    // Compare by value, not reference: PiiMasker.mask only allocates a new String when it actually
    // scrubbed something, so an equal result means "no PII" and we return the original bytes to
    // avoid a needless re-encode round-trip per log event.
    if (masked.equals(json)) {
      return raw;
    }
    return masked.getBytes(StandardCharsets.UTF_8);
  }
}
