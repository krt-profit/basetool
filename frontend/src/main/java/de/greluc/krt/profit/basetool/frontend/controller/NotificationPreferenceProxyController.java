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
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationPreferenceDto;
import de.greluc.krt.profit.basetool.frontend.notification.NotificationPreferenceGroups;
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Renders the profile page's notification card as a fragment for the in-place swap (REQ-NOTIF-027,
 * REQ-FE-001); the writes are in {@link NotificationPreferenceWriteController}.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/profile/notification-preferences")
@RequiredArgsConstructor
@Slf4j
public class NotificationPreferenceProxyController {

  /** Reads and writes the member's notification preferences on the backend. */
  private final NotificationBackendClient notificationClient;

  /**
   * Re-renders the profile page's notification card as a fragment (REQ-FE-001).
   *
   * @param model the view model the fragment reads the groups and the unavailable flag from
   * @return the fragment view name
   */
  @NotNull
  @GetMapping(params = "fragment=card")
  @PreAuthorize("isAuthenticated()")
  public String card(Model model) {
    populate(model, notificationClient);
    return "fragments/profile-notification-prefs :: card";
  }

  /**
   * Loads the member's preferences into the model for the card: the groups, or the unavailable flag
   * when the backend cannot be read.
   *
   * @param model the view model
   * @param client the typed backend client
   */
  static void populate(@NotNull Model model, @NotNull NotificationBackendClient client) {
    List<NotificationPreferenceDto> preferences = null;
    try {
      preferences = client.preferences();
    } catch (Exception e) {
      log.debug("Could not load the notification preferences", e);
    }
    model.addAttribute("notificationPrefsUnavailable", preferences == null);
    model.addAttribute(
        "notificationPrefGroups",
        NotificationPreferenceGroups.group(preferences == null ? List.of() : preferences));
  }
}
