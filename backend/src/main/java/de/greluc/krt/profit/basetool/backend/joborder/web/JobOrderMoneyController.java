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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.MoneyProvisionRefusedException;
import de.greluc.krt.profit.basetool.backend.joborder.internal.CreateAuecJobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.DesignateJobOrderMoneyRecipientDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderLinkedMoneyRepair;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyBankAccountQueries;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyBankAccounts;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyCommands;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyHolderCandidate;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyMovementPreview;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyMovements;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyOrderChanges;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyOutcome;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyProvisionPreview;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyQueries;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyRecipientView;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyRecipients;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyRepairQuote;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyRepairView;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyView;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MoveJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.PreviewJobOrderMoneyProvisionDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.ProvideJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.ReplaceJobOrderBankAttemptDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.UpdateJobOrderMoneyInformationDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.UpdateJobOrderMoneyTargetDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Shared workspace endpoints for an aUEC order and its reported money. */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders")
public class JobOrderMoneyController {

  private final JobOrderMoneyCommands commands;
  private final JobOrderMoneyBankAccountQueries bankAccounts;
  private final JobOrderLinkedMoneyRepair repair;
  private final JobOrderMoneyQueries queries;
  private final JobOrderMoneyMovements movements;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyOrderChanges changes;

  /**
   * Commissions an exact net amount through the existing unit writer authority.
   *
   * @param dto order creation terms
   * @return the new order
   */
  @PostMapping("/auec")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "createAuecJobOrder", summary = "Create an aUEC job order")
  public JobOrderDto createMoneyOrder(@Valid @RequestBody CreateAuecJobOrderDto dto) {
    return commands.create(dto);
  }

  /**
   * Returns the current monetary figures for an order the caller may read.
   *
   * @param id the order
   * @return the reported, purpose-bound money view
   */
  @GetMapping("/{id}/money")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "getJobOrderMoney", summary = "Get reported aUEC order money")
  public JobOrderMoneyView getMoney(@PathVariable UUID id) {
    return queries.getMoney(id);
  }

  /**
   * Finds Bank account references for the caller's authorized money actions.
   *
   * @param id the order
   * @return selectable Bank references and the Order-local revisions
   */
  @GetMapping("/{id}/money/bank-accounts")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "findJobOrderBankAccounts",
      summary = "Find Bank accounts for order money")
  public JobOrderMoneyBankAccounts bankAccounts(@PathVariable UUID id) {
    return bankAccounts.candidates(id);
  }

  /**
   * Records or replays a provision using the supplied logical identity.
   *
   * @param id the order
   * @param dto the submitted provision
   * @return the stored result
   */
  @PostMapping("/{id}/money/provisions")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "provideJobOrderMoney",
      summary = "Record aUEC order provision",
      description =
          "An inactive order or stale holder basis refuses the identity without booking funds."
              + " The stable conflict code is MONEY_PROVISION_REFUSED.")
  public JobOrderMoneyOutcome provideMoney(
      @PathVariable UUID id, @Valid @RequestBody ProvideJobOrderMoneyDto dto) {
    return commands.provide(id, dto).orElseThrow(MoneyProvisionRefusedException::new);
  }

  /**
   * Previews a provision with no financial or audit effect.
   *
   * @param id the order
   * @param dto the proposed provision
   * @return exact amount and the resulting reported holding at the submitted revision
   */
  @PostMapping("/{id}/money/provisions/preview")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "previewJobOrderMoneyProvision", summary = "Preview aUEC provision")
  public JobOrderMoneyProvisionPreview previewMoney(
      @PathVariable UUID id, @Valid @RequestBody PreviewJobOrderMoneyProvisionDto dto) {
    return commands.preview(id, dto);
  }

  /**
   * Offers selectable accounts only within the current responsible writer's provision action.
   *
   * @param id the order
   * @param q optional name fragment
   * @return active account references
   */
  @GetMapping("/{id}/money/holder-candidates")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "findJobOrderMoneyHolderCandidates",
      summary = "Find aUEC holder accounts")
  public List<JobOrderMoneyHolderCandidate> holderCandidates(
      @PathVariable UUID id, @RequestParam(required = false) @Nullable @Size(max = 200) String q) {
    return queries.holderCandidates(id, q);
  }

  /**
   * Calculates a collection or requester handover.
   *
   * @param id the order
   * @param dto the proposed terms
   * @return exact amount, costs and resulting figures at the observed basis
   */
  @PostMapping("/{id}/money/movements/preview")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "previewJobOrderMoneyMovement", summary = "Preview an aUEC movement")
  public JobOrderMoneyMovementPreview previewMovement(
      @PathVariable UUID id, @Valid @RequestBody MoveJobOrderMoneyDto dto) {
    return movements.preview(id, dto);
  }

  /**
   * Records or recovers one actual movement using its submitted identity.
   *
   * @param id the order
   * @param dto the amount, parties, terms and observed revisions
   * @return the recorded result
   */
  @PostMapping("/{id}/money/movements")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "recordJobOrderMoneyMovement", summary = "Record an aUEC movement")
  public JobOrderMoneyOutcome recordMovement(
      @PathVariable UUID id, @Valid @RequestBody MoveJobOrderMoneyDto dto) {
    return movements.record(id, dto).orElseThrow(MoneyProvisionRefusedException::new);
  }

  /**
   * Searches eligible receiving accounts for an authorized outgoing movement.
   *
   * @param id the order
   * @param sourceHolderId the actual source holder
   * @param q optional name fragment
   * @return active account references
   */
  @GetMapping("/{id}/money/movement-candidates")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "findJobOrderMoneyMovementCandidates",
      summary = "Find collection holders")
  public List<JobOrderMoneyHolderCandidate> movementCandidates(
      @PathVariable UUID id,
      @RequestParam UUID sourceHolderId,
      @RequestParam(required = false) @Nullable @Size(max = 200) String q) {
    return movements.candidates(id, sourceHolderId, q);
  }

  /**
   * Searches eligible requester recipient accounts.
   *
   * @param id the order
   * @param q optional name fragment
   * @return active account references
   */
  @GetMapping("/{id}/money/recipient-candidates")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "findJobOrderMoneyRecipientCandidates",
      summary = "Find handover recipients")
  public List<JobOrderMoneyHolderCandidate> recipientCandidates(
      @PathVariable UUID id, @RequestParam(required = false) @Nullable @Size(max = 200) String q) {
    return queries.recipientCandidates(id, q);
  }

  /**
   * Deliberately designates a requester recipient.
   *
   * @param id the order
   * @param dto the selected party
   * @return the current designation
   */
  @PostMapping("/{id}/money/recipients")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "designateJobOrderMoneyRecipient",
      summary = "Designate a handover recipient")
  public JobOrderMoneyRecipientView designateRecipient(
      @PathVariable UUID id, @Valid @RequestBody DesignateJobOrderMoneyRecipientDto dto) {
    return recipients.designate(id, dto);
  }

  /**
   * Retires a requester designation while retaining its recorded handovers.
   *
   * @param id the order
   * @param recipient the designation
   */
  @DeleteMapping("/{id}/money/recipients/{recipient}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "retireJobOrderMoneyRecipient", summary = "Retire a handover recipient")
  public void retireRecipient(@PathVariable UUID id, @PathVariable UUID recipient) {
    recipients.retire(id, recipient);
  }

  /**
   * Updates the requested net target.
   *
   * @param id the order
   * @param dto the exact amount and observed revision
   * @return the current financial view
   */
  @PatchMapping("/{id}/money/target")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "updateJobOrderMoneyTarget", summary = "Update the aUEC target")
  public JobOrderMoneyView updateTarget(
      @PathVariable UUID id, @Valid @RequestBody UpdateJobOrderMoneyTargetDto dto) {
    changes.target(id, dto);
    return queries.getMoney(id);
  }

  /**
   * Updates the contact and comment independently of monetary edits.
   *
   * @param id the order
   * @param dto the edited information and observed revision
   * @return the current financial view
   */
  @PatchMapping("/{id}/money/information")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "updateJobOrderMoneyInformation",
      summary = "Update aUEC order information")
  public JobOrderMoneyView updateInformation(
      @PathVariable UUID id, @Valid @RequestBody UpdateJobOrderMoneyInformationDto dto) {
    changes.information(id, dto);
    return queries.getMoney(id);
  }

  /**
   * Reads the permitted repair for a failed linked request.
   *
   * @param id the order
   * @param operation the original logical operation identity
   * @return editable payment facts and permitted account choices
   */
  @GetMapping("/{id}/money/operations/{operation}/bank-replacement")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "getJobOrderBankRepair", summary = "Read a failed linked Bank attempt")
  public JobOrderMoneyRepairView getBankRepair(
      @PathVariable UUID id, @PathVariable UUID operation) {
    return repair.getRepair(id, operation);
  }

  /**
   * Quotes corrected terms at the retained Bank attempt rate.
   *
   * @param id the order
   * @param operation the original logical operation identity
   * @param dto the prior attempt revision and corrected terms
   * @return the current order binding and exact fee
   */
  @PostMapping("/{id}/money/operations/{operation}/bank-replacement/preview")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "previewJobOrderBankRepair", summary = "Preview a linked Bank repair")
  public JobOrderMoneyRepairQuote previewBankRepair(
      @PathVariable UUID id,
      @PathVariable UUID operation,
      @Valid @RequestBody ReplaceJobOrderBankAttemptDto dto) {
    return repair.preview(id, operation, dto);
  }

  /**
   * Replaces the failed linked request for an already submitted operation.
   *
   * @param id the order
   * @param operation the original logical operation identity
   * @param dto the prior attempt revision and full corrected terms
   * @return the new authoritative outcome
   */
  @PostMapping("/{id}/money/operations/{operation}/bank-replacement")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "replaceJobOrderBankAttempt",
      summary = "Replace a failed linked Bank request")
  public JobOrderMoneyOutcome replaceBankAttempt(
      @PathVariable UUID id,
      @PathVariable UUID operation,
      @Valid @RequestBody ReplaceJobOrderBankAttemptDto dto) {
    return repair.replace(id, operation, dto);
  }

  /**
   * Retrieves the durable result without rebooking an operation.
   *
   * @param id the order
   * @param operation the logical operation
   * @return the stored result if the attempt was accepted
   */
  @GetMapping("/{id}/money/operations/{operation}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "getJobOrderMoneyOutcome",
      summary = "Recover an aUEC operation outcome",
      description =
          "Returns the accepted outcome, a MONEY_PROVISION_REFUSED conflict for a permanent"
              + " no-effect refusal, or not found when the outcome is unknown.")
  public JobOrderMoneyOutcome getMoneyOutcome(@PathVariable UUID id, @PathVariable UUID operation) {
    return queries.getOutcome(id, operation).orElseThrow(MoneyProvisionRefusedException::new);
  }
}
