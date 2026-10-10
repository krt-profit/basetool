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

import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationPreferenceDto;
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX-only relay of the member's own notification preferences (REQ-NOTIF-027). No user id is
 * accepted; the backend derives the member from the token.
 */
@RestController
@RequestMapping("/profile/notification-preferences")
@RequiredArgsConstructor
@Slf4j
public class NotificationPreferenceWriteController {

  /** Reads and writes the member's notification preferences on the backend. */
  private final NotificationBackendClient notificationClient;

  /**
   * Mutes or unmutes one notification type.
   *
   * @param type the notification type name
   * @param request the client payload; only {@code muted} is read, a missing or malformed value
   *     counting as {@code false}
   * @return {@code 200} with the stored preference, or the relayed backend status
   */
  @PutMapping(path = "/{type}", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Object> setPreference(
      @NotNull @PathVariable String type, @NotNull @RequestBody Map<String, Object> request) {
    boolean muted = Boolean.TRUE.equals(request.get("muted"));
    return relay(
        log,
        "setting a notification preference (ajax)",
        () -> {
          NotificationPreferenceDto stored = notificationClient.setPreference(type, muted);
          return ResponseEntity.ok(stored);
        });
  }
}
