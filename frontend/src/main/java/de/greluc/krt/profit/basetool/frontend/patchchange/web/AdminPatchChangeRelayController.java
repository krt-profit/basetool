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

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.CorrectPatchChangeLabelRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangePreviewRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.TriggerPatchChangeRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of the patch-change page to the backend's admin patch-change endpoints (REQ-PATCH-005,
 * REQ-PATCH-008, REQ-PATCH-024); a backend refusal is relayed as {@code application/problem+json}
 * with the backend's localised detail.
 */
@RestController
@RequestMapping(AdminPatchChangePageController.PAGE)
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminPatchChangeRelayController {

  /** Talks to the backend. */
  private final PatchChangeBackendClient patchChangeClient;

  /**
   * Returns the defaults a new patch change starts from, for the reset after a trigger and the
   * previous-values hint after a peer's change.
   *
   * @return the form defaults, or the relayed backend error
   */
  @GetMapping(value = "/form", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> form() {
    return relay(
        log,
        "load the patch-change form (ajax)",
        () -> ResponseEntity.ok(patchChangeClient.form()));
  }

  /**
   * Counts what a patch change with the given live time and options would touch now.
   *
   * @param request the live time and options
   * @return the counts, or the relayed backend error
   */
  @PostMapping(value = "/preview", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> preview(@RequestBody @NotNull PatchChangePreviewRequest request) {
    return relay(
        log,
        "preview a patch change (ajax)",
        () -> ResponseEntity.ok(patchChangeClient.preview(request)));
  }

  /**
   * Triggers a patch change for every org unit; the backend checks the typed confirmation again.
   *
   * @param request the label, kind, live time, options and confirmation
   * @return {@code 201} with the recorded patch change, or the relayed backend error
   */
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> trigger(@RequestBody @NotNull TriggerPatchChangeRequest request) {
    return relay(
        log,
        "trigger a patch change (ajax)",
        () -> ResponseEntity.status(HttpStatus.CREATED).body(patchChangeClient.trigger(request)));
  }

  /**
   * Corrects a patch change's label, forwarding the version the admin last saw.
   *
   * @param id the patch change
   * @param request the corrected label and the version
   * @return the corrected patch change, or the relayed backend error ({@code 409} on a stale
   *     version)
   */
  @PatchMapping(value = "/{id}/label", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> correctLabel(
      @PathVariable @NotNull UUID id,
      @RequestBody @NotNull CorrectPatchChangeLabelRequest request) {
    return relay(
        log,
        "correct the label of patch change " + id + " (ajax)",
        () -> ResponseEntity.ok(patchChangeClient.correctLabel(id, request)));
  }

  /**
   * Ends a patch change's open home-location phase early (REQ-PATCH-024).
   *
   * @param id the patch change
   * @return the patch change with its ended phase, or the relayed backend error ({@code 409} when
   *     its phase is not the open one)
   */
  @PostMapping(value = "/{id}/phase-end", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> endPhase(@PathVariable @NotNull UUID id) {
    return relay(
        log,
        "end the home-location phase of patch change " + id + " (ajax)",
        () -> ResponseEntity.ok(patchChangeClient.endPhase(id)));
  }
}
