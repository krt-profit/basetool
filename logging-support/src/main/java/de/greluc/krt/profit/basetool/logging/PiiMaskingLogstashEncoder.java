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

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;

/** Masks structured credentials and text values before JSON serialization. */
public class PiiMaskingLogstashEncoder extends LogstashEncoder {

  private static final Pattern STRUCTURED_CREDENTIAL =
      Pattern.compile("(?is)((?:password|passwd|secret)[\"']?\\s*[:=]\\s*).*");

  private static final Set<String> SECRET_FIELDS =
      Set.of(
          "password",
          "passwd",
          "secret",
          "client_secret",
          "clientsecret",
          "token",
          "access_token",
          "refresh_token",
          "id_token",
          "authorization",
          "session-id",
          "session_id",
          "apikey",
          "api_key");

  /** Installs the encoder's masking decorator without rewriting serialized JSON. */
  public PiiMaskingLogstashEncoder() {
    var decorator = new MaskingJsonGeneratorDecorator();
    decorator.setDefaultMask("***");
    decorator.addFieldMasker(context -> {
      String name = context.currentName();
      if (name == null) {
        return null;
      }
      String key = name.toLowerCase(Locale.ROOT);
      return SECRET_FIELDS.contains(key)
              || key.endsWith("password")
              || key.endsWith("passwd")
              || key.endsWith("secret")
              || key.endsWith("token")
              || key.endsWith("sessionid")
          ? "***"
          : null;
    });
    decorator.addValueMasker((context, value) -> {
      if (value instanceof String text) {
        String masked = PiiMasker.mask(STRUCTURED_CREDENTIAL.matcher(text).replaceAll("$1***"));
        return text.equals(masked) ? null : masked;
      }
      return null;
    });
    decorator.start();
    addDecorator(decorator);
  }
}
