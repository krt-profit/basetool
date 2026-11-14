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

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.HomeLocationGateFilter;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.ChooseHomeLocationRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationPromptDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.MemberRelocationState;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.RelocationResultDto;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The member's home-location page {@code /home-location} behind the home-location gate
 * (REQ-PATCH-020, REQ-PATCH-022): the curated home locations, what the relocation would move and
 * the one final choice, relayed to the backend.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class HomeLocationPageController {

  /** The page's view. */
  static final String VIEW = "patchchange/home-location";

  /** The message key shown in place of the choice when the prompt cannot be read. */
  static final String PROMPT_ERROR = "homeLocation.error.load";

  /** Reads the prompt and relays the choice. */
  private final PatchChangeBackendClient patchChangeClient;

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
  @GetMapping(HomeLocationGateFilter.GATE_PATH)
  public String page(@NotNull HttpServletRequest request, Model model) {
    HomeLocationPromptDto prompt =
        SectionFallback.load(
            log, "home-location-prompt", patchChangeClient::myHomeLocationPrompt, null);
    if (prompt == null) {
      model.addAttribute("promptError", PROMPT_ERROR);
      return VIEW;
    }
    if (prompt.state() != MemberRelocationState.PENDING) {
      return "redirect:/";
    }
    HomeLocationGateFilter.clearCachedVerdict(request);
    model.addAttribute("prompt", prompt);
    return VIEW;
  }

  /**
   * Relays the caller's final choice; the page navigates into the tool afterwards.
   *
   * @param choice the chosen home location
   * @param request the current request, whose session caches the gate's verdict
   * @return what moved, or the relayed backend refusal with its localised detail
   */
  @PostMapping(
      value = HomeLocationGateFilter.GATE_PATH,
      headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> chooseHomeLocation(
      @RequestBody @NotNull ChooseHomeLocationRequest choice, @NotNull HttpServletRequest request) {
    return relay(
        log,
        "choose the caller's home location (ajax)",
        () -> {
          RelocationResultDto result = patchChangeClient.chooseMyHomeLocation(choice);
          HomeLocationGateFilter.clearCachedVerdict(request);
          return ResponseEntity.ok(result);
        });
  }
}
