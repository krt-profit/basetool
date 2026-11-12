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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderMoneyRecipientIdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.DesignateJobOrderMoneyRecipientDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Proxies explicit correction of a direct requester designation. */
@RestController
@RequestMapping("/orders/{id}/money/recipients/{recipient}")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class JobOrderMoneyRecipientIdentityProxyController {

  private final JobOrderMoneyRecipientIdentityBackendClient identity;

  /**
   * Corrects the recorded recipient for the observed designation.
   *
   * @param id the order
   * @param recipient the original designation
   * @param request the correct recipient
   * @return the replacement or the backend refusal
   */
  @PatchMapping("/identity")
  public ResponseEntity<Object> correct(
      @PathVariable UUID id,
      @PathVariable UUID recipient,
      @Valid @RequestBody DesignateJobOrderMoneyRecipientDto request) {
    return relay(
        log,
        "correct order requester designation",
        () -> ResponseEntity.ok(identity.correct(id, recipient, request)));
  }

  /**
   * Finds selectable accounts under the order's current correction grant.
   *
   * @param id the order
   * @param recipient the original designation
   * @param q the account name fragment
   * @return the matching accounts or the backend refusal
   */
  @GetMapping("/identity-candidates")
  public ResponseEntity<Object> candidates(
      @PathVariable UUID id,
      @PathVariable UUID recipient,
      @RequestParam(defaultValue = "") @Size(max = 200) String q) {
    return relay(
        log,
        "find order requester correction accounts",
        () -> ResponseEntity.ok(identity.candidates(id, recipient, q)));
  }
}
