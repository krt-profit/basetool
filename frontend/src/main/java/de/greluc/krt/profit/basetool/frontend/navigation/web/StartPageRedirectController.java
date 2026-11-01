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

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAppearance;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationMode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The entry point of the start page (REQ-UI-034): a plain login, the launch of the installed app
 * and the switch into administration mode come here, and the caller is sent to the start page of
 * the mode or, without a usable one, to the dashboard. The dashboard itself never redirects, so the
 * logo always opens it.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class StartPageRedirectController {

  /** The path of the entry point, which the login, the web app manifest and the sidebar target. */
  public static final String PATH = "/start";

  private static final String MEMBER_DASHBOARD = "/";

  private static final String ADMIN_DASHBOARD = "/admin";

  private final StartPageResolver resolver;

  /**
   * Redirects to the start page of a mode.
   *
   * @param modeSegment {@code admin} for the administration mode, anything else for the member mode
   * @param access the caller's reachable roles and flags
   * @param appearance the caller's stored appearance, which holds the start pages
   * @param request the current request, for the context path
   * @return {@code 302} to the start page, or to the mode's dashboard
   */
  @NotNull
  @GetMapping(PATH)
  public ResponseEntity<Void> start(
      @RequestParam(name = "mode", defaultValue = "member") @NotNull String modeSegment,
      @ModelAttribute(name = "navigationAccess", binding = false) @NotNull NavigationAccess access,
      @ModelAttribute(name = "navigationAppearance", binding = false) @NotNull
          NavigationAppearance appearance,
      @NotNull HttpServletRequest request) {
    NavigationMode mode = NavigationMode.fromSegment(modeSegment).orElse(NavigationMode.MEMBER);
    String target =
        resolver
            .resolve(mode, appearance, access)
            .orElse(mode == NavigationMode.ADMIN ? ADMIN_DASHBOARD : MEMBER_DASHBOARD);
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, URI.create(request.getContextPath() + target).toString())
        .build();
  }
}
