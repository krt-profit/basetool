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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.ProfileHomeLocationForm;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Saves the profile's setting "my home location" from its plain form post without a script
 * (REQ-PATCH-030); the page's script sends the same form to {@link
 * ProfileHomeLocationProxyController} instead.
 */
@Controller
@UsesLayoutModel
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class ProfileHomeLocationController {

  /** Writes the member's setting on the backend. */
  private final IdentityBackendClient identityClient;

  /**
   * Saves the caller's choice and returns to the profile with a toast; a stale version shows the
   * optimistic-lock toast.
   *
   * @param form the chosen home location and the version the page last read
   * @param bindingResult the binding errors of the post
   * @param redirectAttributes carries the result toast
   * @return a redirect to {@code /profile}
   */
  @NotNull
  @PostMapping("/profile/home-location")
  public String updateHomeLocation(
      @Valid @ModelAttribute ProfileHomeLocationForm form,
      @NotNull BindingResult bindingResult,
      @NotNull RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      redirectAttributes.addFlashAttribute("errorToast", "profile.homeLocation.required");
      return "redirect:/profile";
    }
    try {
      identityClient.updateMyHomeLocation(form.toRequest());
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Home-location update failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
      } else {
        redirectAttributes.addFlashAttribute("errorToast", "error.profile.update.failed");
      }
    }
    return "redirect:/profile";
  }
}
