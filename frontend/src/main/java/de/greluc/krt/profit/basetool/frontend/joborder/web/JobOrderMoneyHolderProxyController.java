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

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderMoneyHolderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyHolderIdentityDto;
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

/** Proxies scoped holder maintenance and its account picker. */
@RestController
@RequestMapping("/orders/{id}/money/holders")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class JobOrderMoneyHolderProxyController {

  private final JobOrderMoneyHolderBackendClient holders;

  /**
   * Applies the submitted holder correction.
   *
   * @param id the order
   * @param holder the holder being maintained
   * @param request the action and observed holder revisions
   * @return an empty success response or the backend refusal
   */
  @PatchMapping("/{holder}/identity")
  public ResponseEntity<Object> change(
      @PathVariable UUID id,
      @PathVariable UUID holder,
      @Valid @RequestBody JobOrderMoneyHolderIdentityDto request) {
    return relay(
        log,
        "correct aUEC holder identity",
        () -> {
          holders.change(id, holder, request);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Returns a bounded, action-scoped list of active accounts.
   *
   * @param id the order
   * @param q the typed account name fragment
   * @return account references or the backend refusal
   */
  @GetMapping("/identity-candidates")
  public ResponseEntity<Object> candidates(
      @PathVariable UUID id, @RequestParam(defaultValue = "") @Size(max = 200) String q) {
    return relay(
        log,
        "search holder correction accounts",
        () -> ResponseEntity.ok(holders.candidates(id, q)));
  }
}
