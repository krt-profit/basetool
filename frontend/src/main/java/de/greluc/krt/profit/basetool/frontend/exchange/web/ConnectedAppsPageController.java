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

package de.greluc.krt.profit.basetool.frontend.exchange.web;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.exchange.client.ExchangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.exchange.model.ConnectedAppDto;
import de.greluc.krt.profit.basetool.frontend.exchange.model.ConnectedInstallationDto;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The member's page „Verbundene Anwendungen": the clients connected to their account, their
 * installations, and the controls to disconnect either (REQ-XCH-008, REQ-XCH-032). The disconnects
 * go through {@link ConnectedAppsRelayController}, after which the list is re-fetched in place.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/connected-apps")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class ConnectedAppsPageController {

  /** The {@code fragment} value that renders only the list, for the in-place swap. */
  static final String APPS_FRAGMENT = "apps";

  /** Talks to the backend. */
  private final ExchangeBackendClient exchangeClient;

  /**
   * Renders the page, or with {@code fragment=apps} only the list. A failed load renders an empty
   * list with {@code error} set.
   *
   * @param fragment {@code apps} for the list fragment; anything else renders the page
   * @param model receives {@code apps}, {@code anyUnseen} and, on a failed load, {@code error}
   * @return {@code connected-apps}, or its {@code apps} fragment
   */
  @NotNull
  @GetMapping
  public String page(@Nullable @RequestParam(required = false) String fragment, Model model) {
    List<ConnectedAppDto> apps = List.of();
    try {
      List<ConnectedAppDto> loaded = exchangeClient.connectedApps();
      apps = loaded == null ? List.of() : loaded;
    } catch (Exception e) {
      log.debug("Failed to load the connected apps", e);
      model.addAttribute("error", "connectedApps.error.load");
    }
    model.addAttribute("apps", apps);
    model.addAttribute(
        "anyUnseen",
        apps.stream()
            .map(ConnectedAppDto::installations)
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .anyMatch(ConnectedInstallationDto::unseen));
    return APPS_FRAGMENT.equals(fragment) ? "connected-apps :: " + APPS_FRAGMENT : "connected-apps";
  }
}
