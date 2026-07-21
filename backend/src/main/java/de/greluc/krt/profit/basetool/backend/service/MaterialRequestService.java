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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.event.MaterialRequestFulfillmentSignalledEvent;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestInterest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialItemRequestCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestUpdateRequest;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeRequestInterestRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write/lifecycle half of the Materialbörse Gesuche (wanted-listings) board — the request-side
 * sibling of {@link MaterialExchangeService} (REQ-MARKET-015…, ADR-0116). It owns the create / edit
 * / deactivate lifecycle of a request, the fulfilment-signal register / withdraw ("Ich kann
 * liefern"), and the audit trail for every mutation.
 *
 * <p>Every mutation projects its result through {@link MaterialRequestBoardService} — the injected
 * read half — so the anonymity redaction (REQ-MARKET-019: supplier names for the owner only) is
 * applied by the exact same code that serves {@link MaterialRequestBoardService#detail(UUID)}. The
 * dependency is one-way (write→read), so the split introduces no cycle.
 *
 * <p>Unlike an offer, a request has <b>no backing Lager row</b>: the requester states the material
 * or item identity and the desired quantity directly (closest to a free-stated item offer). Owner
 * and org unit are stamped from the acting member; there is no ownership-of-item check and no stock
 * cap.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaterialRequestService {

  private final MaterialExchangeRequestRepository requestRepository;
  private final MaterialExchangeRequestInterestRepository interestRepository;
  private final MaterialRepository materialRepository;
  private final UserRepository userRepository;
  private final AuthHelperService authHelperService;
  private final AuditService auditService;

  /**
   * The read half this service projects its mutation results through, so every write response is
   * redacted by the identical {@link MaterialRequestBoardService#detailDto(MaterialExchangeRequest,
   * UUID)} code path the board detail read uses (REQ-MARKET-019) — a one-way write→read dependency,
   * no cycle.
   */
  private final MaterialRequestBoardService boardService;

  /**
   * Resolves and validates a blueprint product for an item request (REQ-MARKET-015): {@code
   * resolveByProductKey(...)} is both the "an item for which a blueprint exists" gate and the
   * source of the canonical display name snapshotted onto the request.
   */
  private final BlueprintProductService blueprintProductService;

  /**
   * Resolves the acting member's active {@link de.greluc.krt.profit.basetool.backend.model.OrgUnit}
   * to stamp on a request's squadron badge — a request has no source Lager row to copy from, so the
   * badge is stamped from the requester (exactly as a free-stated item offer stamps its owner).
   */
  private final OwnerScopeService ownerScopeService;

  /**
   * Publishes the {@link MaterialRequestFulfillmentSignalledEvent} that drives the requester's
   * fulfilment-signal notification (REQ-MARKET-020). The after-commit notification listener
   * consumes it, so the publish stays a side-effect-free scalar hand-off inside the signalling
   * transaction (REQ-NOTIF-002).
   */
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Self-reference used only to run {@link #signalFulfillmentInNewTransaction(UUID, UUID)} in a
   * fresh transaction through the Spring proxy, so a concurrent duplicate-signal race surfaces as a
   * {@link DataIntegrityViolationException} the non-transactional orchestrator can catch (CLAUDE.md
   * find-or-create rule) instead of poisoning the caller's transaction.
   */
  private final ObjectProvider<MaterialRequestService> selfProvider;

  /**
   * Posts a material wanted-listing (Gesuch) to the board ("Material suchen", REQ-MARKET-015). The
   * requester names a catalogue material, the desired quantity in the material's own unit and an
   * optional minimum quality; owner and org unit are stamped from the acting member (there is no
   * source item to copy them from). A member may post several requests for the same material.
   *
   * @param request the material id, desired amount, optional minimum quality and description.
   * @return the resulting request detail (the caller is the owner, so names are included).
   * @throws NotFoundException if the caller or the material does not exist.
   * @throws BadRequestException if the desired amount is not positive.
   */
  @Transactional
  public MaterialRequestDto createMaterialRequest(MaterialRequestCreateRequest request) {
    UUID viewerId = requireViewerId();
    User owner =
        userRepository
            .findById(viewerId)
            .orElseThrow(() -> new NotFoundException("User not found: " + viewerId));
    Material material =
        materialRepository
            .findById(request.materialId())
            .orElseThrow(
                () -> new NotFoundException("Material not found: " + request.materialId()));
    double amount = requirePositiveAmount(request.requestedAmount());

    MaterialExchangeRequest entity = new MaterialExchangeRequest();
    entity.setKind(MaterialExchangeRequestKind.MATERIAL);
    entity.setRequestedMaterial(material);
    entity.setRequestedAmount(amount);
    entity.setMinQuality(request.minQuality());
    entity.setOwner(owner);
    entity.setOwningOrgUnit(ownerScopeService.currentOrgUnit().orElse(null));
    entity.setRemark(request.remark());
    entity.setStatus(MaterialExchangeRequestStatus.ACTIVE);
    entity.setPostedAt(Instant.now());
    MaterialExchangeRequest saved = requestRepository.saveAndFlush(entity);

    auditService.record(
        AuditEventType.MARKET_REQUEST_CREATED,
        saved.getId(),
        requestLabel(saved),
        owner.getId(),
        AuditDetails.of("kind", MaterialExchangeRequestKind.MATERIAL)
            .with("material", material.getId())
            .with("minQuality", request.minQuality())
            .with("amt", amount)
            .with("remarkLen", remarkLength(request.remark())));
    return boardService.detailDto(saved, viewerId);
  }

  /**
   * Posts a craftable-item wanted-listing (Gesuch) to the board ("Item suchen", REQ-MARKET-015) —
   * the item counterpart to {@link #createMaterialRequest(MaterialRequestCreateRequest)}. The
   * requester supplies the blueprint {@code productKey}, validated against {@link
   * BlueprintProductService#resolveByProductKey(String)} (only items an active blueprint produces
   * can be requested) and its canonical display name is snapshotted; the desired quantity is a
   * whole number and an optional minimum quality may be stated (a pure preference — items have no
   * intrinsic quality). Owner and squadron are stamped from the acting member.
   *
   * @param request the blueprint product key, the whole-piece quantity, an optional minimum quality
   *     and the description.
   * @return the resulting request detail (the caller is the owner, so names are included).
   * @throws NotFoundException if the caller is unknown or the product key resolves to no active
   *     blueprint product.
   */
  @Transactional
  public MaterialRequestDto createItemRequest(MaterialItemRequestCreateRequest request) {
    UUID viewerId = requireViewerId();
    User owner =
        userRepository
            .findById(viewerId)
            .orElseThrow(() -> new NotFoundException("User not found: " + viewerId));
    ResolvedProduct product =
        blueprintProductService
            .resolveByProductKey(request.productKey())
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "No craftable item (blueprint product) for key: " + request.productKey()));

    MaterialExchangeRequest entity = new MaterialExchangeRequest();
    entity.setKind(MaterialExchangeRequestKind.ITEM);
    entity.setItemProductKey(product.productKey());
    entity.setItemName(product.productName());
    entity.setItemQuantity(request.quantity());
    entity.setMinQuality(request.minQuality());
    entity.setOwner(owner);
    entity.setOwningOrgUnit(ownerScopeService.currentOrgUnit().orElse(null));
    entity.setRemark(request.remark());
    entity.setStatus(MaterialExchangeRequestStatus.ACTIVE);
    entity.setPostedAt(Instant.now());
    MaterialExchangeRequest saved = requestRepository.saveAndFlush(entity);

    auditService.record(
        AuditEventType.MARKET_REQUEST_CREATED,
        saved.getId(),
        requestLabel(saved),
        owner.getId(),
        AuditDetails.of("kind", MaterialExchangeRequestKind.ITEM)
            .with("product", product.productKey())
            .with("minQuality", request.minQuality())
            .with("qty", request.quantity())
            .with("remarkLen", remarkLength(request.remark())));
    return boardService.detailDto(saved, viewerId);
  }

  /**
   * Edits an existing request's desired quantity, minimum quality and description ("Gesuch
   * bearbeiten", REQ-MARKET-016). Only the owner may edit; the echoed version guards against a
   * concurrent edit. The edit is kind-aware: a {@link MaterialExchangeRequestKind#MATERIAL} request
   * stores the new positive amount in {@code requestedAmount}, an {@link
   * MaterialExchangeRequestKind#ITEM} request validates the new quantity to be a positive whole
   * number and stores it in {@code itemQuantity}. The request's {@code desiredAmount} field carries
   * the SCU amount for a material request and the whole-unit quantity for an item request.
   *
   * @param requestId the request to edit.
   * @param request the new desired amount/quantity, minimum quality, description and the client's
   *     last-seen version.
   * @return the updated request detail.
   * @throws NotFoundException if the request does not exist.
   * @throws AccessDeniedException if the caller is not the owner.
   * @throws BadRequestException if the amount/quantity is invalid.
   */
  @Transactional
  public MaterialRequestDto updateRequest(UUID requestId, MaterialRequestUpdateRequest request) {
    UUID viewerId = requireViewerId();
    MaterialExchangeRequest entity =
        requestRepository
            .findById(requestId)
            .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
    requireOwner(entity, viewerId);
    OptimisticLock.check(
        entity.getVersion(), request.version(), MaterialExchangeRequest.class, requestId);

    AuditDetails details;
    if (entity.getKind() == MaterialExchangeRequestKind.ITEM) {
      int quantity = wholeItemQuantity(request.desiredAmount());
      entity.setItemQuantity(quantity);
      details = AuditDetails.of("kind", MaterialExchangeRequestKind.ITEM).with("qty", quantity);
    } else {
      double amount = requirePositiveAmount(request.desiredAmount());
      entity.setRequestedAmount(amount);
      details = AuditDetails.of("amt", amount);
    }
    entity.setMinQuality(request.minQuality());
    entity.setRemark(request.remark());
    MaterialExchangeRequest saved = requestRepository.saveAndFlush(entity);

    auditService.record(
        AuditEventType.MARKET_REQUEST_UPDATED,
        requestId,
        requestLabel(entity),
        entity.getOwner().getId(),
        details
            .with("minQuality", request.minQuality())
            .with("remarkLen", remarkLength(request.remark())));
    return boardService.detailDto(saved, viewerId);
  }

  /**
   * Deactivates a request by its id ("Gesuch zurückziehen" from the board detail).
   *
   * @param requestId the request to take off the board.
   * @return the resulting request detail.
   * @throws NotFoundException if the request does not exist.
   * @throws AccessDeniedException if the caller is not the owner.
   */
  @Transactional
  public MaterialRequestDto deactivate(UUID requestId) {
    UUID viewerId = requireViewerId();
    MaterialExchangeRequest entity =
        requestRepository
            .findById(requestId)
            .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
    requireOwner(entity, viewerId);
    if (entity.getStatus() == MaterialExchangeRequestStatus.ACTIVE) {
      entity.setStatus(MaterialExchangeRequestStatus.DEACTIVATED);
      requestRepository.saveAndFlush(entity);
      auditService.record(
          AuditEventType.MARKET_REQUEST_DEACTIVATED,
          entity.getId(),
          requestLabel(entity),
          entity.getOwner() == null ? null : entity.getOwner().getId(),
          requestSubjectDetails(entity));
    }
    return boardService.detailDto(entity, viewerId);
  }

  /**
   * Signals that the caller can supply a request ("Ich kann liefern", REQ-MARKET-019). A
   * non-transactional orchestrator: it runs the insert in a fresh transaction through the proxy and
   * treats a concurrent duplicate-signal race (the unique {@code (request, user)} constraint) as an
   * idempotent success (CLAUDE.md find-or-create rule), then re-reads the request for the response.
   *
   * @param requestId the request to signal on.
   * @return the resulting request detail (with {@code viewerInterested = true}).
   * @throws NotFoundException if the request does not exist or is not active.
   * @throws AccessDeniedException if the caller is the request's owner.
   */
  public MaterialRequestDto signalFulfillment(UUID requestId) {
    UUID viewerId = requireViewerId();
    try {
      selfProvider.getObject().signalFulfillmentInNewTransaction(requestId, viewerId);
    } catch (DataIntegrityViolationException alreadySignalled) {
      log.debug("Concurrent fulfilment signal ignored for request {}", requestId);
    }
    return boardService.detail(requestId);
  }

  /**
   * The transactional insert behind {@link #signalFulfillment(UUID)}, run in a fresh transaction so
   * a unique-constraint violation aborts only this transaction (the orchestrator catches it).
   * Public so the Spring proxy applies the {@code REQUIRES_NEW} propagation. On a genuinely new
   * signal it publishes a {@link MaterialRequestFulfillmentSignalledEvent} so the after-commit
   * notification listener alerts the request's owner (REQ-MARKET-020); a duplicate signal returns
   * early and publishes nothing.
   *
   * @param requestId the request to signal on.
   * @param viewerId the signalling member.
   * @throws NotFoundException if the request does not exist or is not active.
   * @throws AccessDeniedException if the member is the request's owner.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void signalFulfillmentInNewTransaction(UUID requestId, UUID viewerId) {
    MaterialExchangeRequest request =
        requestRepository
            .findById(requestId)
            .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
    if (request.getStatus() != MaterialExchangeRequestStatus.ACTIVE) {
      throw new NotFoundException("Request is not active: " + requestId);
    }
    if (request.getOwner() != null && viewerId.equals(request.getOwner().getId())) {
      throw new AccessDeniedException("You cannot signal fulfilment on your own request.");
    }
    if (interestRepository.existsByRequestIdAndInterestedUserId(requestId, viewerId)) {
      return;
    }
    User viewer =
        userRepository
            .findById(viewerId)
            .orElseThrow(() -> new NotFoundException("User not found: " + viewerId));
    MaterialExchangeRequestInterest interest = new MaterialExchangeRequestInterest();
    interest.setRequest(request);
    interest.setInterestedUser(viewer);
    interestRepository.save(interest);
    auditService.record(
        AuditEventType.MARKET_REQUEST_INTEREST_SIGNALLED,
        requestId,
        requestLabel(request),
        request.getOwner() == null ? null : request.getOwner().getId(),
        AuditDetails.of("request", requestId));
    // Notify the owner about the would-be supplier (REQ-MARKET-020). Published only on a genuinely
    // new signal (the idempotent-duplicate return above skips it), inside this transaction so the
    // after-commit listener never fires for a rolled-back signal.
    if (request.getOwner() != null) {
      eventPublisher.publishEvent(
          new MaterialRequestFulfillmentSignalledEvent(
              requestId,
              requestLabel(request),
              viewer.getEffectiveName(),
              request.getOwner().getId(),
              viewerId));
    }
  }

  /**
   * Withdraws the caller's fulfilment signal from a request ("doch nicht liefern"). Idempotent —
   * removing a non-existent signal is a no-op that records no audit event.
   *
   * @param requestId the request to withdraw from.
   * @return the resulting request detail (with {@code viewerInterested = false}).
   * @throws NotFoundException if the request does not exist.
   */
  @Transactional
  public MaterialRequestDto withdrawFulfillment(UUID requestId) {
    UUID viewerId = requireViewerId();
    MaterialExchangeRequest request =
        requestRepository
            .findById(requestId)
            .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
    long removed = interestRepository.deleteByRequestIdAndInterestedUserId(requestId, viewerId);
    if (removed > 0) {
      auditService.record(
          AuditEventType.MARKET_REQUEST_INTEREST_WITHDRAWN,
          requestId,
          requestLabel(request),
          request.getOwner() == null ? null : request.getOwner().getId(),
          AuditDetails.of("request", requestId));
    }
    return boardService.detailDto(request, viewerId);
  }

  /**
   * Resolves the current authenticated member's id or fails — used by every write path.
   *
   * @return the caller's user id.
   * @throws AccessDeniedException if there is no authenticated member.
   */
  private UUID requireViewerId() {
    return authHelperService
        .currentUserId()
        .orElseThrow(() -> new AccessDeniedException("Authentication required."));
  }

  /**
   * Asserts the given member owns the request.
   *
   * @param request the request.
   * @param viewerId the member.
   * @throws AccessDeniedException if the member is not the owner.
   */
  private void requireOwner(MaterialExchangeRequest request, UUID viewerId) {
    if (request.getOwner() == null || !viewerId.equals(request.getOwner().getId())) {
      throw new AccessDeniedException("Only the request's owner may perform this action.");
    }
  }

  /**
   * Validates and normalises a client-supplied desired amount for a material request: it must be a
   * positive number, and is rounded to three-decimal SCU storage precision so the stored value
   * never carries floating-point noise (the same rounding a material offer applies). The
   * {@code @Positive}/{@code @NotNull} DTO constraints already reject the null/non-positive input
   * on the controller path; this re-check keeps the service safe when called directly.
   *
   * @param requested the client-supplied desired amount.
   * @return the amount rounded to three-decimal SCU precision.
   * @throws BadRequestException if the amount is not positive.
   */
  private static double requirePositiveAmount(@Nullable Double requested) {
    if (requested == null || requested <= 0.0) {
      throw new BadRequestException("The desired amount must be a positive quantity.");
    }
    double amount = InventoryItem.roundToScuScale(requested);
    if (amount <= 0.0) {
      throw new BadRequestException("The desired amount must be a positive quantity.");
    }
    return amount;
  }

  /**
   * Validates a client-supplied item-request quantity as a positive whole number — the item sibling
   * of {@link #requirePositiveAmount(Double)} (REQ-MARKET-015/016). The request carries the
   * quantity as a {@link Double}; it must round to a whole number.
   *
   * @param requested the client-supplied whole-unit quantity.
   * @return the quantity as a whole int.
   * @throws BadRequestException if the quantity is absent, below one, or not a whole number.
   */
  private static int wholeItemQuantity(@Nullable Double requested) {
    if (requested == null || requested < 1.0) {
      throw new BadRequestException("The desired item quantity must be at least one whole unit.");
    }
    long rounded = Math.round(requested);
    if (Math.abs(requested - rounded) > 1e-6) {
      throw new BadRequestException("The desired item quantity must be a whole number.");
    }
    return (int) rounded;
  }

  /**
   * The non-personal audit subject label of a request — the material name for a {@link
   * MaterialExchangeRequestKind#MATERIAL} request, the snapshotted item name for an {@link
   * MaterialExchangeRequestKind#ITEM} request. Kind-aware so it never dereferences a {@code null}
   * material for an item request (both are game asset names, never PII).
   *
   * @param request the request.
   * @return the audit subject label.
   */
  private static String requestLabel(MaterialExchangeRequest request) {
    if (request.getKind() == MaterialExchangeRequestKind.ITEM) {
      return request.getItemName();
    }
    return request.getRequestedMaterial().getName();
  }

  /**
   * The PII-free {@code kind=… subject=…} audit details identifying a request's subject — the
   * material id for a material request, the blueprint product key for an item request. Kind-aware
   * so it never dereferences a {@code null} material for an item request.
   *
   * @param request the request.
   * @return the composed audit details.
   */
  private static AuditDetails requestSubjectDetails(MaterialExchangeRequest request) {
    if (request.getKind() == MaterialExchangeRequestKind.ITEM) {
      return AuditDetails.of("kind", MaterialExchangeRequestKind.ITEM)
          .with("product", request.getItemProductKey());
    }
    return AuditDetails.of("kind", MaterialExchangeRequestKind.MATERIAL)
        .with("material", request.getRequestedMaterial().getId());
  }

  /**
   * The character length of a description, treating {@code null} as 0 — the only description fact
   * ever recorded in an audit detail (never the body).
   *
   * @param remark the raw description, possibly {@code null}.
   * @return the length, or 0.
   */
  private static int remarkLength(@Nullable String remark) {
    return remark == null ? 0 : remark.length();
  }
}
