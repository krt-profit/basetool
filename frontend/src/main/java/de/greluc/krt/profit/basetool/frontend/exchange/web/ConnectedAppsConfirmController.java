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

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The page on which the member confirms a change set the mass-change guard held back (REQ-XCH-021,
 * ADR-0110): its load never consumes the handoff and only hands the id to the page's script; {@link
 * ConnectedAppsConfirmRelayController} does the rest.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/connected-apps/confirm")
@PreAuthorize("isAuthenticated()")
public class ConnectedAppsConfirmController {

  /** The shape of a gateway handoff id. */
  static final Pattern HANDOFF_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

  /**
   * Renders the page and hands the handoff id to its script, without consuming it.
   *
   * @param handoff the handoff id from the gateway's confirmation link, or {@code null}
   * @param model the model
   * @return the {@code connected-apps-confirm} view
   */
  @NotNull
  @GetMapping
  public String page(@RequestParam(required = false) @Nullable String handoff, Model model) {
    model.addAttribute(
        "pendingHandoffId",
        handoff != null && HANDOFF_ID.matcher(handoff).matches() ? handoff : null);
    return "connected-apps-confirm";
  }
}
