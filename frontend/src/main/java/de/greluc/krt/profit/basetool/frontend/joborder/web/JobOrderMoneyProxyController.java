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

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderMoneyBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.CreateAuecJobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyOutcome;
import de.greluc.krt.profit.basetool.frontend.joborder.model.PreviewJobOrderMoneyProvisionDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ProvideJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Relays aUEC order commands and submitted outcome recovery. */
@RestController
@RequestMapping("/orders")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class JobOrderMoneyProxyController {

  private final JobOrderMoneyBackendClient money;
  private final LiveSyncLocalBus liveSyncLocalBus;

  /**
   * Creates the monetary order through the common workspace.
   *
   * @param request the shared create fields and net target
   * @return the created order or the backend refusal
   */
  @PostMapping("/auec")
  public ResponseEntity<Object> create(@RequestBody @Valid CreateAuecJobOrderDto request) {
    return relay(
        log,
        "create aUEC order",
        () -> {
          JobOrderDto order = money.create(request);
          liveSyncLocalBus.publish("orders", List.of("queue"));
          return ResponseEntity.ok(order);
        });
  }

  /**
   * Records the reported holding once for the submitted operation.
   *
   * @param id the order
   * @param request the exact terms and captured version
   * @return the recorded result or the backend refusal
   */
  @PostMapping("/{id}/money/provisions")
  public ResponseEntity<Object> provide(
      @PathVariable UUID id, @RequestBody @Valid ProvideJobOrderMoneyDto request) {
    return relay(
        log,
        "record order provision",
        () -> {
          JobOrderMoneyOutcome outcome = money.provide(id, request);
          return ResponseEntity.ok(outcome);
        });
  }

  /**
   * Projects the effect of a draft against the order's current provision authority.
   *
   * @param id the order
   * @param request the proposed terms
   * @return the backend preview or the refusal
   */
  @PostMapping("/{id}/money/provisions/preview")
  public ResponseEntity<Object> preview(
      @PathVariable UUID id, @RequestBody @Valid PreviewJobOrderMoneyProvisionDto request) {
    return relay(
        log, "preview order provision", () -> ResponseEntity.ok(money.preview(id, request)));
  }

  /**
   * Offers current active accounts for an authorized on-behalf provision.
   *
   * @param id the order
   * @param q the typed name fragment
   * @return the backend candidates or the refusal
   */
  @GetMapping("/{id}/money/holder-candidates")
  public ResponseEntity<Object> holderCandidates(
      @PathVariable UUID id, @RequestParam(defaultValue = "") @Size(max = 200) String q) {
    return relay(
        log,
        "search order holder accounts",
        () -> ResponseEntity.ok(money.holderCandidates(id, q)));
  }

  /**
   * Looks up a submitted outcome through the backend's current authorization.
   *
   * @param id the order
   * @param operation the submitted operation
   * @return the outcome or the backend refusal
   */
  @GetMapping("/{id}/money/operations/{operation}")
  public ResponseEntity<Object> outcome(@PathVariable UUID id, @PathVariable UUID operation) {
    return relay(
        log, "read order provision outcome", () -> ResponseEntity.ok(money.outcome(id, operation)));
  }
}
