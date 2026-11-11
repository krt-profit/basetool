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

package de.greluc.krt.profit.basetool.frontend.joborder.client;

import de.greluc.krt.profit.basetool.frontend.joborder.model.CorrectJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.CreateAuecJobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.DesignateJobOrderMoneyRecipientDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyBankAccounts;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyCorrectionPreview;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyHolderCandidate;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyMovementPreview;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyOutcome;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyProvisionPreview;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyReassignmentDestination;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyReassignmentPreview;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyRecipientView;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyRepairQuote;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyRepairView;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyView;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MoveJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.PreviewJobOrderMoneyProvisionDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ProvideJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ReassignJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ReplaceJobOrderBankAttemptDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.SettleJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.UpdateJobOrderMoneyInformationDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.UpdateJobOrderMoneyTargetDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Reads and records exact order-local monetary facts through the backend API. */
@Service
@RequiredArgsConstructor
public class JobOrderMoneyBackendClient {

  private static final String MONEY = "/api/v1/orders/{id}/money";
  private final BackendApiClient backend;

  /**
   * Creates an aUEC order with its initial net target.
   *
   * @param request the shared creation fields and exact target
   * @return the created order
   */
  public JobOrderDto create(@NotNull CreateAuecJobOrderDto request) {
    return backend.post("/api/v1/orders/auec", request, JobOrderDto.class);
  }

  /**
   * Reads the authorized order's monetary projections.
   *
   * @param id the order
   * @return current holdings, history and granted actions
   */
  public JobOrderMoneyView view(@NotNull UUID id) {
    return backend.get(MONEY, JobOrderMoneyView.class, id);
  }

  /**
   * Reads the Bank accounts available to the caller for the current Order actions.
   *
   * @param id the order
   * @return the scoped references for collection, designation and withdrawal
   */
  public JobOrderMoneyBankAccounts bankAccounts(@NotNull UUID id) {
    return backend.get(MONEY + "/bank-accounts", JobOrderMoneyBankAccounts.class, id);
  }

  /**
   * Submits or replays a provision under its persistent operation identity.
   *
   * @param id the order
   * @param request the submitted terms
   * @return the recorded provision
   */
  public JobOrderMoneyOutcome provide(@NotNull UUID id, @NotNull ProvideJobOrderMoneyDto request) {
    return backend.post(MONEY + "/provisions", request, JobOrderMoneyOutcome.class, id);
  }

  /**
   * Projects a provision against its proposed terms and holder revision.
   *
   * @param id the order
   * @param request the proposed exact terms
   * @return the amount, fee and resulting holding
   */
  public JobOrderMoneyProvisionPreview preview(
      @NotNull UUID id, @NotNull PreviewJobOrderMoneyProvisionDto request) {
    return backend.post(
        MONEY + "/provisions/preview", request, JobOrderMoneyProvisionPreview.class, id);
  }

  /**
   * Finds active accounts the current responsible writer may select.
   *
   * @param id the order
   * @param query the typed name fragment
   * @return selectable account identities and labels
   */
  public List<JobOrderMoneyHolderCandidate> holderCandidates(
      @NotNull UUID id, @NotNull String query) {
    return backend.get(
        MONEY + "/holder-candidates?q={query}", new ParameterizedTypeReference<>() {}, id, query);
  }

  /**
   * Projects the movement against its observed revisions and applicable fee rate.
   *
   * @param id the order
   * @param request the proposed movement
   * @return the server's exact preview
   */
  public JobOrderMoneyMovementPreview previewMovement(
      @NotNull UUID id, @NotNull MoveJobOrderMoneyDto request) {
    return backend.post(
        MONEY + "/movements/preview", request, JobOrderMoneyMovementPreview.class, id);
  }

  /**
   * Records or replays an exact movement under its submitted identity.
   *
   * @param id the order
   * @param request the movement and captured terms
   * @return the recorded outcome
   */
  public JobOrderMoneyOutcome move(@NotNull UUID id, @NotNull MoveJobOrderMoneyDto request) {
    return backend.post(MONEY + "/movements", request, JobOrderMoneyOutcome.class, id);
  }

  /**
   * Projects a settlement against the exact holder and fulfilment revisions.
   *
   * @param id the order
   * @param request the proposed settlement
   * @return the fee, funding and effects calculated by the backend
   */
  public JobOrderMoneyMovementPreview previewSettlement(
      @NotNull UUID id, @NotNull SettleJobOrderMoneyDto request) {
    return backend.post(
        MONEY + "/movements/settlement/preview", request, JobOrderMoneyMovementPreview.class, id);
  }

  /**
   * Posts a settlement or replays its accepted outcome.
   *
   * @param id the order
   * @param request the settlement and observed revisions
   * @return the durable monetary outcome
   */
  public JobOrderMoneyOutcome settle(@NotNull UUID id, @NotNull SettleJobOrderMoneyDto request) {
    return backend.post(MONEY + "/movements/settlement", request, JobOrderMoneyOutcome.class, id);
  }

  /**
   * Searches accounts scoped to the selected settlement and its authorized sending party.
   *
   * @param id the order
   * @param kind the settlement purpose
   * @param sourceHolderId the holder paying a beneficiary, if applicable
   * @param recipientId the returning requester designation, if applicable
   * @param query the typed account name fragment
   * @return selectable account references
   */
  public List<JobOrderMoneyHolderCandidate> settlementCandidates(
      @NotNull UUID id,
      @NotNull String kind,
      @Nullable UUID sourceHolderId,
      @Nullable UUID recipientId,
      @NotNull String query) {
    return backend.get(
        MONEY
            + "/movements/settlement/candidates?kind={kind}&sourceHolderId={holder}"
            + "&recipientId={recipient}&q={query}",
        new ParameterizedTypeReference<>() {},
        id,
        kind,
        Objects.toString(sourceHolderId, ""),
        Objects.toString(recipientId, ""),
        query);
  }

  /**
   * Finds the exact accessible destination number and its current selectable holders.
   *
   * @param id the source order
   * @param sourceHolderId the holder who owns the money
   * @param number the displayed destination order number
   * @return the destination and relevant holder versions
   */
  public JobOrderMoneyReassignmentDestination reassignmentDestination(
      @NotNull UUID id, @NotNull UUID sourceHolderId, int number) {
    return backend.get(
        MONEY + "/movements/reassignment/destination?sourceHolderId={holder}&number={number}",
        JobOrderMoneyReassignmentDestination.class,
        id,
        sourceHolderId,
        number);
  }

  /**
   * Projects both balances of a purpose reassignment.
   *
   * @param id the source order
   * @param request the proposed reassignment
   * @return the backend's exact fee-free result
   */
  public JobOrderMoneyReassignmentPreview previewReassignment(
      @NotNull UUID id, @NotNull ReassignJobOrderMoneyDto request) {
    return backend.post(
        MONEY + "/movements/reassignment/preview",
        request,
        JobOrderMoneyReassignmentPreview.class,
        id);
  }

  /**
   * Posts both purpose legs or replays the source outcome for the same submission.
   *
   * @param id the source order
   * @param request the reassignment and observed holder revisions
   * @return the durable source outcome
   */
  public JobOrderMoneyOutcome reassign(
      @NotNull UUID id, @NotNull ReassignJobOrderMoneyDto request) {
    return backend.post(MONEY + "/movements/reassignment", request, JobOrderMoneyOutcome.class, id);
  }

  /**
   * Finds accounts that can be selected as a destination of the specified source.
   *
   * @param id the order
   * @param sourceHolderId the movement's source holder
   * @param query the typed name fragment
   * @return selectable account identities and labels
   */
  public List<JobOrderMoneyHolderCandidate> movementCandidates(
      @NotNull UUID id, @NotNull UUID sourceHolderId, @NotNull String query) {
    return backend.get(
        MONEY + "/movement-candidates?sourceHolderId={source}&q={query}",
        new ParameterizedTypeReference<>() {},
        id,
        sourceHolderId,
        query);
  }

  /**
   * Finds accounts that can be designated for the order's handover.
   *
   * @param id the order
   * @param query the typed name fragment
   * @return selectable account identities and labels
   */
  public List<JobOrderMoneyHolderCandidate> recipientCandidates(
      @NotNull UUID id, @NotNull String query) {
    return backend.get(
        MONEY + "/recipient-candidates?q={query}",
        new ParameterizedTypeReference<>() {},
        id,
        query);
  }

  /**
   * Designates a handover recipient.
   *
   * @param id the order
   * @param request the explicit recipient selection
   * @return the resulting designation
   */
  public JobOrderMoneyRecipientView designateRecipient(
      @NotNull UUID id, @NotNull DesignateJobOrderMoneyRecipientDto request) {
    return backend.post(MONEY + "/recipients", request, JobOrderMoneyRecipientView.class, id);
  }

  /**
   * Ends a recipient's designation.
   *
   * @param id the order
   * @param recipientId the order-local recipient
   */
  public void removeRecipient(@NotNull UUID id, @NotNull UUID recipientId) {
    backend.delete(MONEY + "/recipients/{recipient}", Void.class, id, recipientId);
  }

  /**
   * Updates the net target at its independent revision.
   *
   * @param id the order
   * @param request the target and observed revision
   * @return the current money view
   */
  public JobOrderMoneyView updateTarget(
      @NotNull UUID id, @NotNull UpdateJobOrderMoneyTargetDto request) {
    return backend.patch(MONEY + "/target", request, JobOrderMoneyView.class, id);
  }

  /**
   * Updates the contact and comment at their independent revision.
   *
   * @param id the order
   * @param request the information and observed revision
   * @return the current money view
   */
  public JobOrderMoneyView updateInformation(
      @NotNull UUID id, @NotNull UpdateJobOrderMoneyInformationDto request) {
    return backend.patch(MONEY + "/information", request, JobOrderMoneyView.class, id);
  }

  /**
   * Previews an exact connected correction before submission.
   *
   * @param id the order
   * @param request revised effective terms and their observed versions
   * @return the correction preview
   */
  public JobOrderMoneyCorrectionPreview previewCorrection(
      @NotNull UUID id, @NotNull CorrectJobOrderMoneyDto request) {
    return backend.post(
        MONEY + "/corrections/preview", request, JobOrderMoneyCorrectionPreview.class, id);
  }

  /**
   * Submits a correction or returns its already recorded result.
   *
   * @param id the order
   * @param request the logical operation and corrected terms
   * @return the durable outcome
   */
  public JobOrderMoneyOutcome correct(@NotNull UUID id, @NotNull CorrectJobOrderMoneyDto request) {
    return backend.post(MONEY + "/corrections", request, JobOrderMoneyOutcome.class, id);
  }

  /**
   * Reads the current action and choices for a failed attempt.
   *
   * @param id the order
   * @param operation the original logical identity
   * @return the authorized repair terms
   */
  public JobOrderMoneyRepairView bankRepair(@NotNull UUID id, @NotNull UUID operation) {
    return backend.get(
        MONEY + "/operations/{operation}/bank-replacement",
        JobOrderMoneyRepairView.class,
        id,
        operation);
  }

  /**
   * Quotes corrected terms using the failed attempt's retained basis.
   *
   * @param id the order
   * @param operation the original logical identity
   * @param request the failed revision and proposed corrections
   * @return the exact fee and bound amount
   */
  public JobOrderMoneyRepairQuote previewBankRepair(
      @NotNull UUID id, @NotNull UUID operation, @NotNull ReplaceJobOrderBankAttemptDto request) {
    return backend.post(
        MONEY + "/operations/{operation}/bank-replacement/preview",
        request,
        JobOrderMoneyRepairQuote.class,
        id,
        operation);
  }

  /**
   * Repairs a failed request under the same logical identity.
   *
   * @param id the order
   * @param operation the original logical identity
   * @param request the failed revision and confirmed terms
   * @return the new authoritative outcome
   */
  public JobOrderMoneyOutcome replaceBankAttempt(
      @NotNull UUID id, @NotNull UUID operation, @NotNull ReplaceJobOrderBankAttemptDto request) {
    return backend.post(
        MONEY + "/operations/{operation}/bank-replacement",
        request,
        JobOrderMoneyOutcome.class,
        id,
        operation);
  }

  /**
   * Reads an outcome under the order's current access policy.
   *
   * @param id the order
   * @param operation the submitted identity
   * @return the recorded order money outcome
   */
  public JobOrderMoneyOutcome outcome(@NotNull UUID id, @NotNull UUID operation) {
    return backend.get(
        MONEY + "/operations/{operation}", JobOrderMoneyOutcome.class, id, operation);
  }
}
