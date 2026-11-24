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

package de.greluc.krt.profit.basetool.frontend.exchange.model;

import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * An area switch of the exchange beside the global switch, as the admin page shows it
 * (REQ-BXCH-006, REQ-OXCH-009): the path segment of its backend endpoint and of its relay route,
 * the id of its card and its bundle keys. A new area switch is one constant here and its bundle
 * keys.
 */
@Getter
public enum ExchangeAreaSwitch {

  /** The bank switch. */
  BANK("bank", "xc-bank-switch", "bank"),

  /** The job-order family switch. */
  JOB_ORDERS("job-orders", "xc-job-order-switch", "jobOrder"),

  /** The job-order writes switch. */
  JOB_ORDER_WRITES("job-order-writes", "xc-job-order-writes-switch", "jobOrderWrites");

  /** The prefix every bundle key of the admin page shares. */
  private static final String KEY_PREFIX = "admin.exchangeClients.";

  /**
   * The last path segment of the switch's backend endpoint under {@code
   * /api/v1/connected-apps/admin/settings/} and of its relay route under {@code
   * /admin/exchange-clients/settings/}.
   */
  @NotNull private final String segment;

  /** The id of the switch's card on the admin page, which its button names too. */
  @NotNull private final String cardId;

  /** The bundle key of the card's title. */
  @NotNull private final String titleKey;

  /** The bundle key of the hint while the switch is on. */
  @NotNull private final String hintOnKey;

  /** The bundle key of the hint while the switch is off. */
  @NotNull private final String hintOffKey;

  /** The bundle key of the message after a flip. */
  @NotNull private final String switchedKey;

  /** The bundle key of the confirmation title before switching off. */
  @NotNull private final String confirmOffTitleKey;

  /** The bundle key of the confirmation text before switching off. */
  @NotNull private final String confirmOffBodyKey;

  /** The bundle key of the confirmation title before switching on. */
  @NotNull private final String confirmOnTitleKey;

  /** The bundle key of the confirmation text before switching on. */
  @NotNull private final String confirmOnBodyKey;

  /**
   * Derives the bundle keys from their stem: {@code admin.exchangeClients.<stem>Switch.title},
   * {@code .hintOn} and {@code .hintOff}, {@code admin.exchangeClients.success.<stem>Switched} and
   * {@code admin.exchangeClients.confirm<Stem>Off.title} and {@code .body}, and the same for {@code
   * On}.
   *
   * @param segment the last path segment of the backend endpoint and the relay route
   * @param cardId the id of the card
   * @param keyStem the stem of the bundle keys, in lower camel case
   */
  ExchangeAreaSwitch(@NotNull String segment, @NotNull String cardId, @NotNull String keyStem) {
    this.segment = segment;
    this.cardId = cardId;
    String card = KEY_PREFIX + keyStem + "Switch.";
    this.titleKey = card + "title";
    this.hintOnKey = card + "hintOn";
    this.hintOffKey = card + "hintOff";
    this.switchedKey = KEY_PREFIX + "success." + keyStem + "Switched";
    String confirm =
        KEY_PREFIX + "confirm" + Character.toUpperCase(keyStem.charAt(0)) + keyStem.substring(1);
    this.confirmOffTitleKey = confirm + "Off.title";
    this.confirmOffBodyKey = confirm + "Off.body";
    this.confirmOnTitleKey = confirm + "On.title";
    this.confirmOnBodyKey = confirm + "On.body";
  }

  /**
   * Returns the area switch a path segment names.
   *
   * @param segment the last path segment
   * @return the switch, or empty when no area switch has that segment
   */
  public static @NotNull Optional<ExchangeAreaSwitch> ofSegment(@NotNull String segment) {
    return Arrays.stream(values()).filter(area -> area.segment.equals(segment)).findFirst();
  }
}
