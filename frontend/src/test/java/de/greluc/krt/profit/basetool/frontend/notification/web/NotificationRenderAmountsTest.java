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

package de.greluc.krt.profit.basetool.frontend.notification.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendSideChannels;
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.reactive.function.client.WebClient;

/** Amount parameters of a notification are rendered with thousands separators. */
class NotificationRenderAmountsTest {

  private static NotificationPageController controller() {
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage(
        "notifications.type.PAYOUT",
        Locale.GERMAN,
        "{actor} hat {amount} aUEC ausgezahlt (Konto {accountNo}, Gebühr {fee} aUEC)");
    return new NotificationPageController(
        mock(NotificationBackendClient.class),
        messages,
        new BackendSideChannels(mock(WebClient.class), null),
        mock(OAuth2AuthorizedClientManager.class),
        new SimpleMeterRegistry());
  }

  @Test
  void amountAndFeeAreGroupedButOtherNumbersAreNot() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("actor", "Ada");
    params.put("amount", "3000000");
    params.put("accountNo", "1234567");
    params.put("fee", "15000");

    assertEquals(
        "Ada hat 3.000.000 aUEC ausgezahlt (Konto 1234567, Gebühr 15.000 aUEC)",
        controller().render("PAYOUT", params, Locale.GERMAN));
  }

  @ParameterizedTest
  @CsvSource({
    "0, 0",
    "999, 999",
    "1000, 1.000",
    "1234567, 1.234.567",
    "12.50, '12,50'",
    "1500000.25, '1.500.000,25'",
    "-25000, -25.000",
    "abc, abc",
    "'1,5', '1,5'"
  })
  void groupAmountFormatsOnlyPlainDecimals(String stored, String shown) {
    assertEquals(shown, NotificationPageController.groupAmount(stored));
  }
}
