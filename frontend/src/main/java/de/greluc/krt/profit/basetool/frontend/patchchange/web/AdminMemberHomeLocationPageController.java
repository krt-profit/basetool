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

package de.greluc.krt.profit.basetool.frontend.patchchange.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The admin's member tab {@code /admin/members/{userId}/home-location} (REQ-PATCH-022): where the
 * member stands in the latest home-location phase and, while the member is pending, what would move
 * and the choice on the member's behalf. The choice goes through {@link
 * AdminMemberHomeLocationRelayController}, after which the content re-renders in place.
 */
@Controller
@UsesLayoutModel
@RequestMapping(AdminMemberHomeLocationPageController.PAGE)
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminMemberHomeLocationPageController {

  /** The page's path, also the path of its relay endpoint. */
  static final String PAGE = "/admin/members/{userId}/home-location";

  /** The {@code fragment} value that renders only the content, for the in-place swap. */
  static final String CONTENT_FRAGMENT = "content";

  /** The message key shown in place of the content when the prompt cannot be read. */
  static final String PROMPT_ERROR = "admin.memberHomeLocation.error.load";

  /** Reads the member's prompt and the member. */
  private final HomeLocationPageReader pageReader;

  /**
   * Renders the tab, or with {@code fragment=content} only its content. A prompt that cannot be
   * read renders {@code promptError} in place of the content.
   *
   * @param userId the member
   * @param fragment {@code content} for the content fragment; anything else renders the page
   * @param model receives {@code memberId} and {@code prompt}, or {@code promptError} on a failed
   *     read; for the page also {@code memberName}
   * @return {@code patchchange/admin/member-home-location}, or its content fragment
   */
  @NotNull
  @GetMapping
  public String page(
      @PathVariable @NotNull UUID userId,
      @Nullable @RequestParam(required = false) String fragment,
      Model model) {
    boolean contentOnly = CONTENT_FRAGMENT.equals(fragment);
    HomeLocationPageReader.MemberTab tab = pageReader.memberTab(userId, !contentOnly);
    model.addAttribute("memberId", userId);
    tab.prompt()
        .ifPresentOrElse(
            prompt -> model.addAttribute("prompt", prompt),
            () -> model.addAttribute("promptError", PROMPT_ERROR));
    if (contentOnly) {
      return "patchchange/admin/member-home-location :: content";
    }
    model.addAttribute("memberName", tab.memberName().orElse(null));
    return "patchchange/admin/member-home-location";
  }
}
