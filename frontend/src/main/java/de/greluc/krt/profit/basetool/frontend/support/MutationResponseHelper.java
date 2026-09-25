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

package de.greluc.krt.profit.basetool.frontend.support;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Runs a Post/Redirect/Get mutation: flashes a success toast, or on any exception logs it and
 * flashes an error toast, then redirects (REQ-FE-002).
 *
 * <p>Handlers that repopulate a form or branch on the problem type keep their own logic.
 */
@Component
@Slf4j
public class MutationResponseHelper {

  /**
   * A mutating backend interaction run for its side effects (the WebClient call). May fail with any
   * runtime exception (e.g. {@code BackendServiceException} from the WebClient layer), which {@link
   * #mutate} turns into an {@code errorToast}.
   */
  @FunctionalInterface
  public interface MutationAction {
    /** Performs the backend mutation. */
    void run();
  }

  /**
   * Runs {@code action}; on success flashes {@code successToast=successKey}, on any {@link
   * Exception} logs the failure and flashes {@code errorToast=errorKey}; either way returns a
   * redirect to {@code redirectView}.
   *
   * @param redirectAttributes the flash-attribute carrier for the post-redirect toast
   * @param redirectView the target the browser is redirected to (without the {@code redirect:}
   *     prefix, e.g. {@code /orders})
   * @param successKey the i18n message key flashed as {@code successToast} on success
   * @param errorKey the i18n message key flashed as {@code errorToast} on failure
   * @param action the backend mutation to run
   * @return the {@code redirect:}-prefixed view name for {@code redirectView}
   */
  @NotNull
  public String mutate(
      @NotNull RedirectAttributes redirectAttributes,
      @NotNull String redirectView,
      @NotNull String successKey,
      @NotNull String errorKey,
      @NotNull MutationAction action) {
    try {
      action.run();
      redirectAttributes.addFlashAttribute("successToast", successKey);
    } catch (Exception e) {
      log.error("Mutation failed, redirecting to {}: {}", redirectView, e.getMessage(), e);
      redirectAttributes.addFlashAttribute("errorToast", errorKey);
    }
    return "redirect:" + redirectView;
  }
}
