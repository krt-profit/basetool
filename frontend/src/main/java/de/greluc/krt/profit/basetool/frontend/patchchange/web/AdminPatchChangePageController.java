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
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeFormDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeOptions;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchCount;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchKind;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeExceptionMode;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeExceptionTarget;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeGroup;
import java.util.List;
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
 * The admin's patch-change page {@code /admin/settings/patch-change} (REQ-PATCH-005,
 * REQ-PATCH-008), a sub-page of the danger zone: the form with its kind presets, the preview, the
 * confirmation and the history. Its writes go through {@link AdminPatchChangeRelayController},
 * after which the history is re-fetched in place (REQ-FE-001).
 */
@Controller
@UsesLayoutModel
@RequestMapping(AdminPatchChangePageController.PAGE)
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminPatchChangePageController {

  /** The page's path, also the base of its relay endpoints. */
  static final String PAGE = "/admin/settings/patch-change";

  /** The {@code fragment} value that renders only the history, for the in-place swap. */
  static final String HISTORY_FRAGMENT = "history";

  /** The kind the form starts with, and returns to after a trigger. */
  static final PatchKind DEFAULT_KIND = PatchKind.REBUILD;

  /** The message key shown in place of the history when it cannot be read. */
  static final String HISTORY_ERROR = "admin.patchChange.error.history";

  /** The message key shown in place of the form when its defaults cannot be read. */
  static final String FORM_ERROR = "admin.patchChange.error.form";

  /** Talks to the backend. */
  private final PatchChangeBackendClient patchChangeClient;

  /**
   * Renders the page, or with {@code fragment=history} only its history. A history that cannot be
   * read renders {@code historyError}; a form whose defaults cannot be read renders {@code
   * formError} instead of the form and the preview.
   *
   * @param fragment {@code history} for the history fragment; anything else renders the page
   * @param model receives {@code history}, {@code wipeGroups} and, on a failed read, {@code
   *     historyError}; for the page also {@code form}, {@code defaults}, {@code defaultKind},
   *     {@code kinds}, {@code countKinds}, {@code exceptionTargets}, {@code exceptionModes} and
   *     {@code exceptionLimit}, or {@code formError}
   * @return {@code patchchange/admin/patch-change}, or its history fragment
   */
  @NotNull
  @GetMapping
  public String page(@Nullable @RequestParam(required = false) String fragment, Model model) {
    loadHistory(model);
    model.addAttribute("wipeGroups", List.of(WipeGroup.values()));
    if (HISTORY_FRAGMENT.equals(fragment)) {
      return "patchchange/admin/patch-change :: history";
    }
    loadForm(model);
    model.addAttribute("defaultKind", DEFAULT_KIND);
    model.addAttribute("kinds", List.of(PatchKind.values()));
    model.addAttribute("countKinds", List.of(PatchCount.values()));
    model.addAttribute("exceptionTargets", List.of(WipeExceptionTarget.values()));
    model.addAttribute("exceptionModes", List.of(WipeExceptionMode.values()));
    model.addAttribute("exceptionLimit", PatchChangeOptions.MAX_WIPE_EXCEPTIONS);
    return "patchchange/admin/patch-change";
  }

  /**
   * Puts the history into the model, or an empty one with {@code historyError} when the backend
   * cannot be read.
   *
   * @param model the page's model
   */
  private void loadHistory(@NotNull Model model) {
    boolean loaded =
        SectionFallback.run(
            log,
            "patch-change-history",
            () -> {
              List<PatchChangeDto> history = patchChangeClient.history();
              model.addAttribute("history", history == null ? List.of() : history);
            });
    if (!loaded) {
      model.addAttribute("history", List.of());
      model.addAttribute("historyError", HISTORY_ERROR);
    }
  }

  /**
   * Puts the form defaults and the default kind's preset into the model, or {@code formError} when
   * the backend cannot be read or sends no defaults.
   *
   * @param model the page's model
   */
  private void loadForm(@NotNull Model model) {
    PatchChangeFormDto form =
        SectionFallback.load(log, "patch-change-form", patchChangeClient::form, null);
    if (form == null) {
      model.addAttribute("formError", FORM_ERROR);
      return;
    }
    model.addAttribute("form", form);
    model.addAttribute("defaults", form.presetFor(DEFAULT_KIND));
  }
}
