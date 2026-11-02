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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;
import static de.greluc.krt.profit.basetool.frontend.kernel.web.RequestHeaders.AJAX;

import de.greluc.krt.profit.basetool.frontend.dashboard.client.DashboardBackendClient;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.AnnouncementDto;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.AnnouncementRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The in-place writes of the announcement's inline editor in the Information widget (REQ-UI-034):
 * save and remove, both with the optimistic-lock version.
 */
@RestController
@RequestMapping("/admin/announcement")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminAnnouncementPageController {

  /** Reads and writes the announcement. */
  private final DashboardBackendClient dashboardClient;

  /**
   * Saves the announcement. Returns the bumped version so the page can update its hidden input;
   * backend errors are relayed as {@code application/problem+json}.
   *
   * @param form the Markdown {@code content} (required) and the optional {@code version}
   * @return {@code 200} with the new version on success, the relayed backend status on
   *     conflict/failure, {@code 400} when {@code content} is missing, {@code 500} on an unexpected
   *     error
   */
  @PostMapping(value = "/update", headers = AJAX)
  public ResponseEntity<Object> updateAnnouncementAjax(
      @Valid @RequestBody AnnouncementUpdateForm form) {
    return relay(
        log,
        "update announcement (ajax)",
        () -> {
          dashboardClient.saveAnnouncement(new AnnouncementRequest(form.content(), form.version()));

          AnnouncementDto updated = dashboardClient.adminAnnouncement();
          return ResponseEntity.ok(
              new AnnouncementVersionResponse(updated != null ? updated.version() : null));
        });
  }

  /**
   * Removes the announcement; backend failures are relayed for an inline toast.
   *
   * @return {@code 200} on success, the relayed backend status on failure, {@code 500} on an
   *     unexpected error
   */
  @PostMapping(value = "/delete", headers = AJAX)
  public ResponseEntity<Object> deleteAnnouncementAjax() {
    return relay(
        log,
        "delete announcement (ajax)",
        () -> {
          dashboardClient.deleteAnnouncement();
          return ResponseEntity.ok().build();
        });
  }
}
