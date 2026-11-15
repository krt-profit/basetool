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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of the profile's setting "my home location" (REQ-PATCH-030, REQ-FE-001); the backend
 * derives the member from the token and accepts only a curated home location.
 */
@RestController
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class ProfileHomeLocationProxyController {

  /** Writes the member's setting on the backend. */
  private final IdentityBackendClient identityClient;

  /**
   * Saves the caller's choice and answers with the stored choice and the setting's new version.
   *
   * @param form the chosen home location and the version the page last read
   * @return {@code 200} with {@code {homeLocationId, version}}, or the relayed backend status and
   *     {@code code}
   */
  @PostMapping(
      value = "/profile/home-location",
      headers = "X-Requested-With=XMLHttpRequest",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> updateHomeLocation(
      @RequestBody @NotNull ProfileHomeLocationForm form) {
    MyHomeLocationRequest payload = form.toRequest();
    return relay(
        log,
        "updating the home location (ajax)",
        () -> ResponseEntity.ok(identityClient.updateMyHomeLocation(payload)));
  }
}
