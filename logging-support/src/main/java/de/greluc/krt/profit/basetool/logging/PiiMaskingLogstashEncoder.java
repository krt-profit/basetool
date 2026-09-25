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
 * {@link LogstashEncoder} that masks PII and secrets in the rendered JSON via {@link PiiMasker};
 * the encoder of the prod JSON sink of all three applications.
 */
public class PiiMaskingLogstashEncoder extends LogstashEncoder {

  /**
   * Encodes {@code event} with the stock JSON encoder and masks the result.
   *
   * @param event the logging event to render
   * @return the masked JSON bytes, or the encoder's own bytes when nothing was masked
   */
  @Override
  public byte[] encode(ILoggingEvent event) {
    byte[] raw = super.encode(event);
    if (raw == null || raw.length == 0) {
      return raw;
    }
    String json = new String(raw, StandardCharsets.UTF_8);
    String masked = PiiMasker.mask(json);
    if (json.equals(masked)) {
      return raw;
    }
    return masked.getBytes(StandardCharsets.UTF_8);
  }
}
