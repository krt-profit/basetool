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
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationPromptDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.MemberRelocationState;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The member's home-location page {@code /home-location} behind the home-location gate
 * (REQ-PATCH-020, REQ-PATCH-022): the curated home locations, what the relocation would move and
 * the one final choice, which {@link HomeLocationRelayController} relays.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class HomeLocationPageController {

  /** The message key shown in place of the choice when the prompt cannot be read. */
  static final String PROMPT_ERROR = "homeLocation.error.load";

  /** Reads the prompt. */
  private final HomeLocationPageReader pageReader;

  /** The gate whose cached verdict a pending prompt discards. */
  private final HomeLocationGate homeLocationGate;

  /**
   * Renders the prompt of a pending member, or sends a member who has nothing to choose into the
   * tool. A prompt that cannot be read renders {@code promptError}, so the gate cannot loop.
   *
   * @param request the current request, whose session caches the gate's verdict
   * @param model receives {@code prompt}, or {@code promptError} on a failed read
   * @return {@code patchchange/home-location}, or a redirect to the start page when the member is
   *     not pending
   */
  @NotNull
  @GetMapping(HomeLocationGate.PAGE_PATH)
  public String page(@NotNull HttpServletRequest request, Model model) {
    Optional<HomeLocationPromptDto> read = pageReader.myPrompt();
    if (read.isEmpty()) {
      model.addAttribute("promptError", PROMPT_ERROR);
      return "patchchange/home-location";
    }
    HomeLocationPromptDto prompt = read.get();
    if (prompt.state() != MemberRelocationState.PENDING) {
      return "redirect:/";
    }
    homeLocationGate.forget(request);
    model.addAttribute("prompt", prompt);
    return "patchchange/home-location";
  }
}
