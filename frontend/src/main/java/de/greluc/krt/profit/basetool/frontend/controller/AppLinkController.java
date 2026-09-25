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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Web fallback for the Android app's App Link {@code /app/callback} (REQ-SEC-038), reached when a
 * device has not verified the link or a desktop browser follows it.
 *
 * <p>Not listed in {@code PublicPaths}, so the pending-approval and consent gates still apply to
 * authenticated callers.
 */
@Controller
@UsesLayoutModel
public class AppLinkController {

  /** Where {@link #callback()} sends the browser, and the view that page renders. */
  private static final String HELP_PATH = "/app/link-help";

  /**
   * Answers the App Link with an immediate {@code 303} redirect to {@link #HELP_PATH}, dropping the
   * query so the OAuth2 authorization code does not stay in the address bar, history or {@code
   * Referer}.
   *
   * @return a {@code 303} redirect to the help page, with no query string
   */
  @NotNull
  @GetMapping("/app/callback")
  public RedirectView callback() {
    RedirectView redirect = new RedirectView(HELP_PATH);
    redirect.setStatusCode(HttpStatus.SEE_OTHER);
    redirect.setExposeModelAttributes(false);
    return redirect;
  }

  /**
   * Renders the page explaining why the login ended up in the browser and how to repair the App
   * Link. Anonymous, since the caller is mid-login and may have no web session.
   *
   * @return the {@code app-link-help} view name
   */
  @NotNull
  @GetMapping(HELP_PATH)
  public String linkHelp() {
    return "app-link-help";
  }
}
