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
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeOptions;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchCount;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchKind;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeExceptionMode;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeExceptionTarget;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeGroup;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
public class AdminPatchChangePageController {

  /** The page's path, also the base of its relay endpoints. */
  static final String PAGE = "/admin/settings/patch-change";

  /** The {@code fragment} value that renders only the history, for the in-place swap. */
  static final String HISTORY_FRAGMENT = "history";

  /** The message key shown in place of the history when it cannot be read. */
  static final String HISTORY_ERROR = "admin.patchChange.error.history";

  /** The message key shown in place of the form when its defaults cannot be read. */
  static final String FORM_ERROR = "admin.patchChange.error.form";

  /** Reads the page's backend data. */
  private final PatchChangePageReader pageReader;

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
    model.addAttribute("wipeGroups", List.of(WipeGroup.values()));
    if (HISTORY_FRAGMENT.equals(fragment)) {
      putHistory(pageReader.history(), model);
      return "patchchange/admin/patch-change :: history";
    }
    PatchChangePageReader.AdminPage page = pageReader.adminPage();
    putHistory(page.history(), model);
    page.form()
        .ifPresentOrElse(
            form -> {
              model.addAttribute("form", form.form());
              model.addAttribute("defaults", form.defaults());
            },
            () -> model.addAttribute("formError", FORM_ERROR));
    model.addAttribute("defaultKind", PatchChangePageReader.DEFAULT_KIND);
    model.addAttribute("kinds", List.of(PatchKind.values()));
    model.addAttribute("countKinds", List.of(PatchCount.values()));
    model.addAttribute("exceptionTargets", List.of(WipeExceptionTarget.values()));
    model.addAttribute("exceptionModes", List.of(WipeExceptionMode.values()));
    model.addAttribute("exceptionLimit", PatchChangeOptions.MAX_WIPE_EXCEPTIONS);
    return "patchchange/admin/patch-change";
  }

  /**
   * Maps the history into the model, with {@code historyError} when it was unavailable.
   *
   * @param history the history the reader returned
   * @param model the page's model
   */
  private static void putHistory(
      @NotNull PatchChangePageReader.History history, @NotNull Model model) {
    model.addAttribute("history", history.entries());
    if (!history.available()) {
      model.addAttribute("historyError", HISTORY_ERROR);
    }
  }
}
