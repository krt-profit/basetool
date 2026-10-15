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

package de.greluc.krt.profit.basetool.frontend.exchange.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.exchange.client.ExchangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.exchange.model.NameWithholdingRequest;
import de.greluc.krt.profit.basetool.frontend.exchange.model.ProfileNameWithholdingForm;
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
 * AJAX relay of the profile's name-withholding toggle (REQ-XCH-044, REQ-FE-001); the backend
 * derives the member from the token.
 */
@RestController
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class ProfileNameWithholdingProxyController {

  /** Writes the member's name withholding on the backend. */
  private final ExchangeBackendClient exchangeClient;

  /**
   * Saves the caller's choice and answers with the stored choice and the setting's new version.
   *
   * @param form the choice and the version the page last read
   * @return {@code 200} with {@code {withholdName, version}}, or the relayed backend status and
   *     {@code code}
   */
  @PostMapping(
      value = "/profile/name-withholding",
      headers = "X-Requested-With=XMLHttpRequest",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> updateNameWithholding(
      @RequestBody @NotNull ProfileNameWithholdingForm form) {
    NameWithholdingRequest payload =
        new NameWithholdingRequest(
            form.withholdName(), form.version() == null ? 0L : form.version());
    return relay(
        log,
        "updating the name withholding (ajax)",
        () -> ResponseEntity.ok(exchangeClient.updateNameWithholding(payload)));
  }
}
