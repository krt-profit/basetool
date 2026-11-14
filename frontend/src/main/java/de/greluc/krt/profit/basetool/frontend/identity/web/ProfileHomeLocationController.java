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

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyHomeLocationRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.ProfileHomeLocationForm;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Saves the profile's setting "my home location" (REQ-PATCH-030): in place from the page's script,
 * or as a plain form post without one. The backend derives the member from the token and accepts
 * only a curated home location.
 */
@Controller
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class ProfileHomeLocationController {

  /** The form post's path, and the section's form action. */
  private static final String PATH = "/profile/home-location";

  /** Writes the member's setting on the backend. */
  private final IdentityBackendClient identityClient;

  /**
   * Saves the caller's choice and answers with the stored choice and the setting's new version.
   *
   * @param form the chosen home location and the version the page last read
   * @return {@code 200} with {@code {homeLocationId, version}}, or the relayed backend status and
   *     {@code code}
   */
  @ResponseBody
  @PostMapping(
      value = PATH,
      headers = "X-Requested-With=XMLHttpRequest",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> updateHomeLocationAjax(
      @RequestBody @NotNull ProfileHomeLocationForm form) {
    MyHomeLocationRequest payload = toRequest(form);
    return relay(
        log,
        "updating the home location (ajax)",
        () -> ResponseEntity.ok(identityClient.updateMyHomeLocation(payload)));
  }

  /**
   * Saves the caller's choice from the section's plain form post and returns to the profile with a
   * toast; a stale version shows the optimistic-lock toast.
   *
   * @param form the chosen home location and the version the page last read
   * @param bindingResult the binding errors of the post
   * @param redirectAttributes carries the result toast
   * @return a redirect to {@code /profile}
   */
  @NotNull
  @PostMapping(PATH)
  public String updateHomeLocation(
      @Valid @ModelAttribute ProfileHomeLocationForm form,
      @NotNull BindingResult bindingResult,
      @NotNull RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      redirectAttributes.addFlashAttribute("errorToast", "profile.homeLocation.required");
      return "redirect:/profile";
    }
    try {
      identityClient.updateMyHomeLocation(toRequest(form));
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Home-location update failed", e);
      redirectAttributes.addFlashAttribute(
          "errorToast",
          e.getStatusCode() == 409 ? "error.concurrency.conflict" : "error.profile.update.failed");
    }
    return "redirect:/profile";
  }

  /**
   * Builds the backend body from the section's form.
   *
   * @param form the chosen home location and the version the page last read
   * @return the body, with a missing version read as a first choice
   */
  private static @NotNull MyHomeLocationRequest toRequest(@NotNull ProfileHomeLocationForm form) {
    return new MyHomeLocationRequest(
        form.homeLocationId(), form.version() == null ? 0L : form.version());
  }
}
