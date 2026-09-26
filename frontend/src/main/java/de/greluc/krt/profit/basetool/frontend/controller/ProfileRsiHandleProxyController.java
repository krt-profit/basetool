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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.model.dto.MyRsiHandleResponse;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfileRsiHandleForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of the member's own RSI handle from the profile page (REQ-SEC-072); the backend
 * derives the member from the token.
 */
@RestController
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class ProfileRsiHandleProxyController {

  private final BackendApiClient backendApiClient;
  private final MessageSource messageSource;

  /**
   * Sets or clears the caller's RSI handle and answers with the stored handle and the new user-row
   * version.
   *
   * @param form the handle and the version the page last read
   * @param bindingResult validation errors of the JSON body
   * @return {@code 200} with {@code {rsiHandle, version}}, {@code 422} with the localized shape
   *     message, or the relayed backend status and {@code code}
   */
  @PostMapping(
      value = "/profile/rsi-handle",
      headers = "X-Requested-With=XMLHttpRequest",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> updateRsiHandle(
      @jakarta.validation.Valid @RequestBody @NotNull ProfileRsiHandleForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("status", 422);
      body.put("code", "VALIDATION");
      body.put(
          "detail",
          messageSource.getMessage(
              "profile.rsiHandle.invalid", null, LocaleContextHolder.getLocale()));
      return ResponseEntity.unprocessableContent()
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(body);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("rsiHandle", form.rsiHandle() == null ? "" : form.rsiHandle().trim());
    payload.put("version", form.version() == null ? 0L : form.version());
    return relay(
        log,
        "updating the RSI handle (ajax)",
        () -> {
          MyRsiHandleResponse saved =
              backendApiClient.put(
                  "/api/v1/users/me/rsi-handle", payload, MyRsiHandleResponse.class);
          return ResponseEntity.ok(saved);
        });
  }
}
