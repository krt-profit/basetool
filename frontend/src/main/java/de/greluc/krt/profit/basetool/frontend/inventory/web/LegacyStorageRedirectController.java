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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import jakarta.servlet.http.HttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Redirects the former Lager list pages {@code /inventory/my} and {@code /inventory/all} to the
 * Lager tab with the option selected, keeping the filter query, so bookmarks and notification links
 * keep working (REQ-UI-026).
 *
 * <p>Only the page {@code GET} without the {@code X-Requested-With} header is redirected; the
 * sub-paths of both lists keep their own endpoints.
 */
@Controller
@UsesLayoutModel
@PreAuthorize("isAuthenticated()")
public class LegacyStorageRedirectController {

  private static final String PAGE_ONLY = "!X-Requested-With";

  /**
   * The former personal list, now the „Mein Lager" option of the Lager tab.
   *
   * @param request the request whose filter query is kept
   * @return a redirect to the Lager tab with the personal list selected
   */
  @NotNull
  @GetMapping(value = "/inventory/my", headers = PAGE_ONLY)
  public String myLager(@NotNull HttpServletRequest request) {
    return redirectTo(LagerStorage.MY, request);
  }

  /**
   * The former global list, now the „Globales Lager" option of the Lager tab.
   *
   * @param request the request whose filter query is kept
   * @return a redirect to the Lager tab with the global list selected
   */
  @NotNull
  @GetMapping(value = "/inventory/all", headers = PAGE_ONLY)
  public String globalLager(@NotNull HttpServletRequest request) {
    return redirectTo(LagerStorage.ALL, request);
  }

  @NotNull
  private static String redirectTo(@NotNull String storage, @NotNull HttpServletRequest request) {
    UriComponentsBuilder target =
        UriComponentsBuilder.fromPath("/inventory").queryParam(LagerStorage.PARAM, storage);
    request
        .getParameterMap()
        .forEach((name, values) -> {
          if (!LagerStorage.PARAM.equals(name)) {
            target.queryParam(name, (Object[]) values);
          }
        });
    return "redirect:" + target.build().encode().toUriString();
  }
}
