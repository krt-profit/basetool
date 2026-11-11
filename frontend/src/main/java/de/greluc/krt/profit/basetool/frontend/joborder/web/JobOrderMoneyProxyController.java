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
import de.greluc.krt.profit.basetool.frontend.joborder.model.DesignateJobOrderMoneyRecipientDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyOutcome;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MoveJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.PreviewJobOrderMoneyProvisionDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ProvideJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.UpdateJobOrderMoneyInformationDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.UpdateJobOrderMoneyTargetDto;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
   * Projects a movement against the order's current financial basis.
   *
   * @param id the order
   * @param request the proposed movement
   * @return the preview or the backend refusal
   */
  @PostMapping("/{id}/money/movements/preview")
  public ResponseEntity<Object> previewMovement(
      @PathVariable UUID id, @RequestBody @Valid MoveJobOrderMoneyDto request) {
    return relay(
        log,
        "preview order money movement",
        () -> ResponseEntity.ok(money.previewMovement(id, request)));
  }

  /**
   * Records an actual collection or handover under one operation identity.
   *
   * @param id the order
   * @param request the submitted movement
   * @return the recorded outcome or the backend refusal
   */
  @PostMapping("/{id}/money/movements")
  public ResponseEntity<Object> move(
      @PathVariable UUID id, @RequestBody @Valid MoveJobOrderMoneyDto request) {
    return relay(
        log, "record order money movement", () -> ResponseEntity.ok(money.move(id, request)));
  }

  /**
   * Offers accounts for a collection from the selected holder.
   *
   * @param id the order
   * @param sourceHolderId the source holder
   * @param q the typed name fragment
   * @return the candidates or the backend refusal
   */
  @GetMapping("/{id}/money/movement-candidates")
  public ResponseEntity<Object> movementCandidates(
      @PathVariable UUID id,
      @RequestParam UUID sourceHolderId,
      @RequestParam(defaultValue = "") @Size(max = 200) String q) {
    return relay(
        log,
        "search movement destination accounts",
        () -> ResponseEntity.ok(money.movementCandidates(id, sourceHolderId, q)));
  }

  /**
   * Offers accounts for an authorized recipient designation.
   *
   * @param id the order
   * @param q the typed name fragment
   * @return the candidates or the backend refusal
   */
  @GetMapping("/{id}/money/recipient-candidates")
  public ResponseEntity<Object> recipientCandidates(
      @PathVariable UUID id, @RequestParam(defaultValue = "") @Size(max = 200) String q) {
    return relay(
        log,
        "search handover recipient accounts",
        () -> ResponseEntity.ok(money.recipientCandidates(id, q)));
  }

  /**
   * Designates a recipient for this order.
   *
   * @param id the order
   * @param request the explicit recipient selection
   * @return the designation or the backend refusal
   */
  @PostMapping("/{id}/money/recipients")
  public ResponseEntity<Object> designateRecipient(
      @PathVariable UUID id, @RequestBody @Valid DesignateJobOrderMoneyRecipientDto request) {
    return relay(
        log,
        "designate order handover recipient",
        () ->
            ResponseEntity.status(HttpStatus.CREATED).body(money.designateRecipient(id, request)));
  }

  /**
   * Ends a recipient's designation while preserving existing history.
   *
   * @param id the order
   * @param recipient the order-local recipient
   * @return the backend result
   */
  @DeleteMapping("/{id}/money/recipients/{recipient}")
  public ResponseEntity<Object> removeRecipient(
      @PathVariable UUID id, @PathVariable UUID recipient) {
    return relay(
        log,
        "remove order handover recipient",
        () -> {
          money.removeRecipient(id, recipient);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Changes the net target without editing the order information.
   *
   * @param id the order
   * @param request the target and observed revision
   * @return the updated money view or the backend refusal
   */
  @PatchMapping("/{id}/money/target")
  public ResponseEntity<Object> updateTarget(
      @PathVariable UUID id, @RequestBody @Valid UpdateJobOrderMoneyTargetDto request) {
    return relay(
        log, "update order money target", () -> ResponseEntity.ok(money.updateTarget(id, request)));
  }

  /**
   * Changes the contact and comment without editing the net target.
   *
   * @param id the order
   * @param request the information and observed revision
   * @return the updated money view or the backend refusal
   */
  @PatchMapping("/{id}/money/information")
  public ResponseEntity<Object> updateInformation(
      @PathVariable UUID id, @RequestBody @Valid UpdateJobOrderMoneyInformationDto request) {
    return relay(
        log,
        "update money order information",
        () -> ResponseEntity.ok(money.updateInformation(id, request)));
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
        log, "read order money outcome", () -> ResponseEntity.ok(money.outcome(id, operation)));
  }
}
