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

package de.greluc.krt.profit.basetool.frontend.navigation.web;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.navigation.client.NavigationBackendClient;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Reads the caller's stored navigation preferences once per request, so the favourites and the
 * appearance of one page share a single backend call (REQ-UI-032).
 *
 * <p>Fails closed: a failed read yields an empty result, which renders the defaults.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NavigationPreferencesLoader {

  private static final String REQUEST_ATTRIBUTE = NavigationPreferencesLoader.class.getName();

  private final NavigationBackendClient client;

  /**
   * Returns the caller's preferences, reading them on the first call of the request.
   *
   * @return the stored sections, or empty when the read failed or the backend sent no body
   */
  @NotNull
  public Optional<NavigationPreferencesResponse> load() {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request != null
        && request.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
            instanceof Memo memo) {
      return memo.preferences();
    }
    Optional<NavigationPreferencesResponse> read = read();
    remember(read);
    return read;
  }

  /**
   * Replaces the memo of this request by the sections the backend answered after a deletion.
   *
   * @param preferences the sections as they are now
   */
  public void replace(@NotNull NavigationPreferencesResponse preferences) {
    remember(Optional.of(preferences));
  }

  private static void remember(Optional<NavigationPreferencesResponse> preferences) {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request != null) {
      request.setAttribute(
          REQUEST_ATTRIBUTE, new Memo(preferences), RequestAttributes.SCOPE_REQUEST);
    }
  }

  private Optional<NavigationPreferencesResponse> read() {
    try {
      return Optional.ofNullable(client.preferences());
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (BackendServiceException e) {
      log.debug("Failed to read the navigation preferences", e);
      return Optional.empty();
    }
  }

  /** The memoised answer of one request. */
  private record Memo(Optional<NavigationPreferencesResponse> preferences) {}
}
