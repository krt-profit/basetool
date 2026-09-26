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

package de.greluc.krt.profit.basetool.frontend.logging;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * Relays the caller's UI locale to the backend as {@code Accept-Language} on every outbound {@code
 * WebClient} call, so backend-localized problem details arrive in the user's language.
 *
 * <p>Reads {@link LocaleContextHolder}, restored on the Reactor worker thread by {@link
 * de.greluc.krt.profit.basetool.frontend.config.ReactorContextPropagationConfig}; adds nothing when
 * no locale is bound.
 */
@Component
public class UserLocaleRelayFilter {

  /**
   * Returns the filter function that adds the {@code Accept-Language} header carrying the user's
   * resolved locale (as a BCP 47 language tag) to outbound requests. No header is added when no
   * locale context is bound to the current thread.
   *
   * @return filter function for the WebClient pipeline; never {@code null}.
   */
  @NotNull
  public ExchangeFilterFunction relayUserLocale() {
    return (request, next) -> {
      Locale locale =
          LocaleContextHolder.getLocaleContext() != null
              ? LocaleContextHolder.getLocaleContext().getLocale()
              : null;
      if (locale == null) {
        return next.exchange(request);
      }
      return next.exchange(
          ClientRequest.from(request)
              .header(HttpHeaders.ACCEPT_LANGUAGE, locale.toLanguageTag())
              .build());
    };
  }
}
