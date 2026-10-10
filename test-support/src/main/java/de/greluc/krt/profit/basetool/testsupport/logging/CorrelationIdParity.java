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

package de.greluc.krt.profit.basetool.testsupport.logging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The security contract the backend, frontend and ingest {@code CorrelationIdFilter} copies share
 * (PSA-03): an inbound id that could break a log line or a response header never comes back out, no
 * id is longer than {@value #MAX_LENGTH} characters, an id that is already safe is echoed
 * unchanged, and an absent or blank one is replaced by a fresh UUID.
 *
 * <p>The copies differ on purpose in how they treat an over-long inbound id (the backend and
 * frontend truncate it, the ingest mints a fresh one), so the contract states only what all three
 * must hold.
 */
public final class CorrelationIdParity {

  /** Longest correlation id any of the three filters lets through. */
  public static final int MAX_LENGTH = 128;

  /** The only characters an echoed id may contain. */
  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]+$");

  /**
   * Inbound header values every filter has to be run against; {@code null} is the absent header.
   */
  private static final List<String> INBOUND =
      Arrays.asList(
          null,
          "",
          "   ",
          "abc-123_XY.7",
          "a",
          "A".repeat(MAX_LENGTH),
          "A".repeat(MAX_LENGTH + 40),
          "a".repeat(MAX_LENGTH) + "\r\nX-Injected: 1",
          "id with space",
          "id\nnewline",
          "id\r\nSet-Cookie: x=1",
          "id;semicolon",
          "id/slash",
          "id%0d%0a",
          "id\u0000nul",
          "" + (char) 0xE4 + (char) 0xF6 + (char) 0xFC,
          "<script>alert(1)</script>",
          "${jndi:ldap://x}",
          "id\"quote");

  /** Non-instantiable holder of the contract. */
  private CorrelationIdParity() {}

  /**
   * Returns the inbound header values a filter is checked against.
   *
   * @return an unmodifiable list; {@code null} stands for a request without the header
   */
  public static @NotNull List<@Nullable String> inboundCases() {
    return INBOUND;
  }

  /**
   * Checks one filter outcome against the contract.
   *
   * @param inbound the header value the request carried, {@code null} when it carried none
   * @param echoed the id the filter put on the response, {@code null} when it put none
   * @return one message per violated rule; empty when the outcome holds the contract
   */
  public static @NotNull List<String> violations(
      @Nullable String inbound, @Nullable String echoed) {
    List<String> out = new ArrayList<>();
    if (echoed == null || echoed.isEmpty()) {
      out.add("no correlation id was echoed for " + describe(inbound));
      return out;
    }
    if (echoed.length() > MAX_LENGTH) {
      out.add("echoed id is " + echoed.length() + " characters for " + describe(inbound));
    }
    if (!SAFE.matcher(echoed).matches()) {
      out.add("echoed id holds an unsafe character for " + describe(inbound));
    }
    boolean inboundIsSafe =
        inbound != null && inbound.length() <= MAX_LENGTH && SAFE.matcher(inbound).matches();
    if (inboundIsSafe && !echoed.equals(inbound)) {
      out.add("a safe inbound id was not echoed unchanged: " + describe(inbound));
    }
    if ((inbound == null || inbound.isBlank()) && !isUuid(echoed)) {
      out.add("an absent or blank inbound id did not get a fresh UUID: " + describe(inbound));
    }
    return out;
  }

  /**
   * Tells whether a value parses as a UUID in its canonical form.
   *
   * @param value the value to test
   * @return {@code true} for a canonical UUID string
   */
  private static boolean isUuid(@NotNull String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException _) {
      return false;
    }
  }

  /**
   * Renders an inbound value for a failure message without letting control characters through.
   *
   * @param inbound the value, possibly {@code null}
   * @return a printable description
   */
  private static String describe(@Nullable String inbound) {
    if (inbound == null) {
      return "<absent>";
    }
    String printable = inbound.replace("\r", "\\r").replace("\n", "\\n").replace("\u0000", "\\0");
    return "'" + (printable.length() > 60 ? printable.substring(0, 60) + "...'" : printable + "'");
  }
}
