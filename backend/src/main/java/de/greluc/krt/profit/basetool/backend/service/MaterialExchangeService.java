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

import de.greluc.krt.profit.basetool.backend.event.MaterialExchangeInterestRegisteredEvent;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeInterest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeItemReleaseRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeOfferUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeReleaseRequest;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeInterestRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write half of the Materialbörse offer board (REQ-MARKET-001): release, re-release, edit and
 * deactivation of offers, interest signals, and their audit trail.
 *
 * <p>Every mutation returns its result through {@link MaterialExchangeBoardService}, so the
 * anonymity redaction (REQ-MARKET-006) is shared. Offered amounts are validated against the item's
 * current stock on every write.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaterialExchangeService {

  private final MaterialExchangeOfferRepository offerRepository;
  private final MaterialExchangeInterestRepository interestRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final UserRepository userRepository;
  private final AuthHelperService authHelperService;
  private final AuditService auditService;

  /**
   * The read half this service projects its mutation results through, so every write response is
   * redacted by the identical {@link MaterialExchangeBoardService#detailDto(MaterialExchangeOffer,
   * UUID)} code path the board detail read uses (REQ-MARKET-006) — a one-way write→read dependency,
   * no cycle.
   */
  private final MaterialExchangeBoardService boardService;

  /**
   * Resolves the blueprint product of an item offer, gating it and supplying the display name
   * snapshotted onto the offer (REQ-MARKET-012).
   */
  private final BlueprintProductService blueprintProductService;

  /**
   * Resolves the acting member's active {@link de.greluc.krt.profit.basetool.backend.model.OrgUnit}
   * to stamp on an item offer's squadron badge — the item-offer counterpart of copying {@code
   * inventoryItem.getOwningOrgUnit()} for a material offer (an item offer has no source Lager row
   * to copy from).
   */
  private final OwnerScopeService ownerScopeService;

  /**
   * Publishes the {@link MaterialExchangeInterestRegisteredEvent} that notifies the offer's owner
   * after commit (REQ-MARKET-011).
   */
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Self-reference used only to run {@link #registerInterestInNewTransaction(UUID, UUID)} in a
   * fresh transaction through the Spring proxy, so a concurrent duplicate-registration race
   * surfaces as a {@link DataIntegrityViolationException} the non-transactional orchestrator can
   * catch (CLAUDE.md find-or-create rule) instead of poisoning the caller's transaction.
   */
  private final ObjectProvider<MaterialExchangeService> selfProvider;

  /**
   * Releases one of the caller's own Lager rows to the board as a material or stock-backed item
   * offer (REQ-MARKET-002, REQ-MARKET-014).
   *
   * <p>The offered amount must be positive and at most the item's current stock; an existing active
   * offer for the row is re-released. Owner and org unit are taken from the item.
   *
   * @param request the item id, the offered quantity and the trade remark
   * @return the resulting offer detail, including interessenten names
   * @throws NotFoundException if the item does not exist
   * @throws AccessDeniedException if the item does not belong to the caller
   * @throws BadRequestException if the amount exceeds the stock, or a game-item row is not produced
   *     by any active blueprint
   */
  @Transactional
  public MaterialExchangeOfferDto release(MaterialExchangeReleaseRequest request) {
    UUID viewerId = requireViewerId();
    InventoryItem item =
        Entities.require(
            inventoryItemRepository.findById(request.inventoryItemId()),
            () -> "Inventory item not found: " + request.inventoryItemId());
    if (item.getUser() == null || !viewerId.equals(item.getUser().getId())) {
      throw new AccessDeniedException("Only the item's owner may release it to the Materialbörse.");
    }
    if (item.getGameItem() != null) {
      return releaseFromItemStock(item, request, viewerId);
    }
    double offeredAmount = requireOfferableAmount(request.offeredAmount(), item);

    MaterialExchangeOffer offer =
        offerRepository
            .findByInventoryItemIdAndStatus(item.getId(), MaterialExchangeOfferStatus.ACTIVE)
            .orElse(null);
    final boolean reRelease = offer != null;
    if (offer == null) {
      offer = new MaterialExchangeOffer();
      offer.setKind(MaterialExchangeOfferKind.MATERIAL);
      offer.setInventoryItem(item);
      offer.setOwner(item.getUser());
      offer.setOwningOrgUnit(item.getOwningOrgUnit());
      offer.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    }
    offer.setOfferedAmount(offeredAmount);
    offer.setRemark(request.remark());
    offer.setReleasedAt(Instant.now());
    MaterialExchangeOffer saved = offerRepository.saveAndFlush(offer);

    auditService.record(
        AuditEventType.MARKET_OFFER_RELEASED,
        saved.getId(),
        offerLabel(saved),
        item.getUser().getId(),
        AuditDetails.of("kind", MaterialExchangeOfferKind.MATERIAL)
            .with("item", item.getId())
            .with("q", item.getQuality())
            .with("amt", offeredAmount)
            .with("stock", item.getAmount())
            .with("remarkLen", remarkLength(request.remark()))
            .with("reRelease", reRelease));
    return boardService.detailDto(saved, viewerId);
  }

  /**
   * Releases a game-item Lager row as a stock-backed {@link MaterialExchangeOfferKind#ITEM} offer
   * (REQ-MARKET-014), re-releasing an existing active offer on the row.
   *
   * <p>The product key and name come from {@link BlueprintProductService#resolveByGameItem(UUID)}.
   *
   * @param item the caller's own game-item Lager row (ownership already checked)
   * @param request the release payload; {@code offeredAmount} is the whole-unit quantity
   * @param viewerId the acting owner
   * @return the resulting offer detail, including interessenten names
   * @throws BadRequestException if the quantity is not a positive whole number, exceeds the stock,
   *     or the item is not produced by any active blueprint
   */
  private MaterialExchangeOfferDto releaseFromItemStock(
      InventoryItem item, @NotNull MaterialExchangeReleaseRequest request, UUID viewerId) {
    final int quantity = requireOfferableItemQuantity(request.offeredAmount(), item);
    ResolvedProduct product =
        blueprintProductService
            .resolveByGameItem(item.getGameItem().getId())
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "This item is not produced by any active blueprint and cannot be"
                            + " offered."));

    MaterialExchangeOffer offer =
        offerRepository
            .findByInventoryItemIdAndStatus(item.getId(), MaterialExchangeOfferStatus.ACTIVE)
            .orElse(null);
    final boolean reRelease = offer != null;
    if (offer == null) {
      offer = new MaterialExchangeOffer();
      offer.setKind(MaterialExchangeOfferKind.ITEM);
      offer.setInventoryItem(item);
      offer.setOwner(item.getUser());
      offer.setOwningOrgUnit(item.getOwningOrgUnit());
      offer.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    }
    offer.setItemProductKey(product.productKey());
    offer.setItemName(product.productName());
    offer.setItemQuantity(quantity);
    offer.setRemark(request.remark());
    offer.setReleasedAt(Instant.now());
    MaterialExchangeOffer saved = offerRepository.saveAndFlush(offer);

    auditService.record(
        AuditEventType.MARKET_OFFER_RELEASED,
        saved.getId(),
        offerLabel(saved),
        item.getUser().getId(),
        AuditDetails.of("kind", MaterialExchangeOfferKind.ITEM)
            .with("item", item.getId())
            .with("product", product.productKey())
            .with("qty", quantity)
            .with("stock", item.getAmount())
            .with("remarkLen", remarkLength(request.remark()))
            .with("reRelease", reRelease));
    return boardService.detailDto(saved, viewerId);
  }

  /**
   * Lists a craftable item on the board without a backing Lager row (REQ-MARKET-012); always
   * inserts a new active offer.
   *
   * <p>The product key must resolve to an active blueprint product, whose name is snapshotted.
   *
   * @param request the blueprint product key, the whole-piece quantity and the trade remark
   * @return the resulting offer detail, including interessenten names
   * @throws NotFoundException if the caller is unknown or the product key resolves to no active
   *     blueprint product
   */
  @Transactional
  public MaterialExchangeOfferDto releaseItem(MaterialExchangeItemReleaseRequest request) {
    UUID viewerId = requireViewerId();
    User owner =
        Entities.require(userRepository.findById(viewerId), () -> "User not found: " + viewerId);
    ResolvedProduct product =
        Entities.require(
            blueprintProductService.resolveByProductKey(request.productKey()),
            () -> "No craftable item (blueprint product) for key: " + request.productKey());

    MaterialExchangeOffer offer = new MaterialExchangeOffer();
    offer.setKind(MaterialExchangeOfferKind.ITEM);
    offer.setOwner(owner);
    offer.setOwningOrgUnit(ownerScopeService.currentOrgUnit().orElse(null));
    offer.setItemProductKey(product.productKey());
    offer.setItemName(product.productName());
    offer.setItemQuantity(request.quantity());
    offer.setRemark(request.remark());
    offer.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    offer.setReleasedAt(Instant.now());
    MaterialExchangeOffer saved = offerRepository.saveAndFlush(offer);

    auditService.record(
        AuditEventType.MARKET_OFFER_RELEASED,
        saved.getId(),
        offerLabel(saved),
        owner.getId(),
        AuditDetails.of("kind", MaterialExchangeOfferKind.ITEM)
            .with("product", product.productKey())
            .with("qty", request.quantity())
            .with("remarkLen", remarkLength(request.remark())));
    return boardService.detailDto(saved, viewerId);
  }

  /**
   * Edits an offer's quantity and trade remark; owner only, guarded by the echoed version
   * (REQ-MARKET-007).
   *
   * <p>Material and stock-backed item offers are capped at the backing row's current stock; item
   * quantities must be positive whole numbers.
   *
   * @param offerId the offer to edit
   * @param request the new amount or quantity, remark and last-seen version
   * @return the updated offer detail
   * @throws NotFoundException if the offer does not exist
   * @throws AccessDeniedException if the caller is not the owner
   * @throws BadRequestException if the amount/quantity is invalid or exceeds the current stock
   */
  @Transactional
  public MaterialExchangeOfferDto updateOffer(
      UUID offerId, MaterialExchangeOfferUpdateRequest request) {
    UUID viewerId = requireViewerId();
    MaterialExchangeOffer offer =
        Entities.require(offerRepository.findById(offerId), () -> "Offer not found: " + offerId);
    requireOwner(offer, viewerId);
    OptimisticLock.check(
        offer.getVersion(), request.version(), MaterialExchangeOffer.class, offerId);

    AuditDetails details;
    if (offer.getKind() == MaterialExchangeOfferKind.ITEM) {
      int quantity =
          offer.getInventoryItem() != null
              ? requireOfferableItemQuantity(request.offeredAmount(), offer.getInventoryItem())
              : wholeItemQuantity(request.offeredAmount());
      offer.setItemQuantity(quantity);
      details = AuditDetails.of("kind", MaterialExchangeOfferKind.ITEM).with("qty", quantity);
    } else {
      double offeredAmount =
          requireOfferableAmount(request.offeredAmount(), offer.getInventoryItem());
      offer.setOfferedAmount(offeredAmount);
      details = AuditDetails.of("amt", offeredAmount);
    }
    offer.setRemark(request.remark());
    MaterialExchangeOffer saved = offerRepository.saveAndFlush(offer);

    auditService.record(
        AuditEventType.MARKET_REMARK_UPDATED,
        offerId,
        offerLabel(offer),
        offer.getOwner().getId(),
        details.with("remarkLen", remarkLength(request.remark())));
    return boardService.detailDto(saved, viewerId);
  }

  /**
   * Deactivates an offer by its id ("Angebot deaktivieren" from the board detail).
   *
   * @param offerId the offer to take off the board.
   * @return the resulting offer detail.
   * @throws NotFoundException if the offer does not exist.
   * @throws AccessDeniedException if the caller is not the owner.
   */
  @Transactional
  public MaterialExchangeOfferDto deactivate(UUID offerId) {
    UUID viewerId = requireViewerId();
    MaterialExchangeOffer offer =
        Entities.require(offerRepository.findById(offerId), () -> "Offer not found: " + offerId);
    requireOwner(offer, viewerId);
    return deactivateOffer(offer, viewerId);
  }

  /**
   * Deactivates the active offer backing a Lager row (un-checking "Für Börse freigeben").
   *
   * @param inventoryItemId the Lager row whose active offer to take off the board
   * @return the resulting offer detail
   * @throws NotFoundException if the item has no active offer
   * @throws AccessDeniedException if the caller is not the owner
   */
  @Transactional
  public MaterialExchangeOfferDto deactivateForItem(UUID inventoryItemId) {
    UUID viewerId = requireViewerId();
    MaterialExchangeOffer offer =
        Entities.require(
            offerRepository.findByInventoryItemIdAndStatus(
                inventoryItemId, MaterialExchangeOfferStatus.ACTIVE),
            () -> "No active offer for item: " + inventoryItemId);
    requireOwner(offer, viewerId);
    return deactivateOffer(offer, viewerId);
  }

  /**
   * Registers the caller's interest in an offer (REQ-MARKET-006).
   *
   * <p>Not transactional itself: the insert runs in a new transaction, and a concurrent duplicate
   * registration counts as success.
   *
   * @param offerId the offer to register interest in
   * @return the resulting offer detail (with {@code iAmInterested = true})
   * @throws NotFoundException if the offer does not exist or is not active
   * @throws AccessDeniedException if the caller is the offer's owner
   */
  public MaterialExchangeOfferDto registerInterest(UUID offerId) {
    UUID viewerId = requireViewerId();
    try {
      selfProvider.getObject().registerInterestInNewTransaction(offerId, viewerId);
    } catch (DataIntegrityViolationException alreadyRegistered) {
      log.debug("Concurrent interest registration ignored for offer {}", offerId);
    }
    return boardService.detail(offerId);
  }

  /**
   * Inserts the interest registration behind {@link #registerInterest(UUID)} in a new transaction
   * and, for a new registration, publishes a {@link MaterialExchangeInterestRegisteredEvent}
   * (REQ-MARKET-011).
   *
   * @param offerId the offer to register interest in
   * @param viewerId the registering member
   * @throws NotFoundException if the offer does not exist or is not active
   * @throws AccessDeniedException if the member is the offer's owner
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void registerInterestInNewTransaction(UUID offerId, UUID viewerId) {
    MaterialExchangeOffer offer =
        Entities.require(offerRepository.findById(offerId), () -> "Offer not found: " + offerId);
    if (offer.getStatus() != MaterialExchangeOfferStatus.ACTIVE) {
      throw new NotFoundException("Offer is not active: " + offerId);
    }
    if (offer.getOwner() != null && viewerId.equals(offer.getOwner().getId())) {
      throw new AccessDeniedException("You cannot register interest in your own offer.");
    }
    if (interestRepository.existsByOfferIdAndInterestedUserId(offerId, viewerId)) {
      return;
    }
    User viewer =
        Entities.require(userRepository.findById(viewerId), () -> "User not found: " + viewerId);
    MaterialExchangeInterest interest = new MaterialExchangeInterest();
    interest.setOffer(offer);
    interest.setInterestedUser(viewer);
    interestRepository.save(interest);
    auditService.record(
        AuditEventType.MARKET_INTEREST_REGISTERED,
        offerId,
        offerLabel(offer),
        offer.getOwner() == null ? null : offer.getOwner().getId(),
        AuditDetails.of("offer", offerId));
    if (offer.getOwner() != null) {
      eventPublisher.publishEvent(
          new MaterialExchangeInterestRegisteredEvent(
              offerId,
              offerLabel(offer),
              viewer.getEffectiveName(),
              offer.getOwner().getId(),
              viewerId));
    }
  }

  /**
   * Withdraws the caller's interest from an offer ("Interesse zurückziehen"). Idempotent — removing
   * a non-existent registration is a no-op that records no audit event.
   *
   * @param offerId the offer to withdraw from.
   * @return the resulting offer detail (with {@code iAmInterested = false}).
   * @throws NotFoundException if the offer does not exist.
   */
  @Transactional
  public MaterialExchangeOfferDto withdrawInterest(UUID offerId) {
    UUID viewerId = requireViewerId();
    MaterialExchangeOffer offer =
        Entities.require(offerRepository.findById(offerId), () -> "Offer not found: " + offerId);
    long removed = interestRepository.deleteByOfferIdAndInterestedUserId(offerId, viewerId);
    if (removed > 0) {
      auditService.record(
          AuditEventType.MARKET_INTEREST_WITHDRAWN,
          offerId,
          offerLabel(offer),
          offer.getOwner() == null ? null : offer.getOwner().getId(),
          AuditDetails.of("offer", offerId));
    }
    return boardService.detailDto(offer, viewerId);
  }

  /**
   * Sets an offer to {@code DEACTIVATED} (if it is still active) and records the audit event, then
   * returns its refreshed detail.
   *
   * @param offer the managed offer to deactivate.
   * @param viewerId the acting owner.
   * @return the offer detail after deactivation.
   */
  private MaterialExchangeOfferDto deactivateOffer(MaterialExchangeOffer offer, UUID viewerId) {
    if (offer.getStatus() == MaterialExchangeOfferStatus.ACTIVE) {
      offer.setStatus(MaterialExchangeOfferStatus.DEACTIVATED);
      offerRepository.saveAndFlush(offer);
      auditService.record(
          AuditEventType.MARKET_OFFER_DEACTIVATED,
          offer.getId(),
          offerLabel(offer),
          offer.getOwner() == null ? null : offer.getOwner().getId(),
          offerSubjectDetails(offer));
    }
    return boardService.detailDto(offer, viewerId);
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
   * Asserts the given member owns the offer.
   *
   * @param offer the offer.
   * @param viewerId the member.
   * @throws AccessDeniedException if the member is not the owner.
   */
  private void requireOwner(MaterialExchangeOffer offer, UUID viewerId) {
    if (offer.getOwner() == null || !viewerId.equals(offer.getOwner().getId())) {
      throw new AccessDeniedException("Only the offer's owner may perform this action.");
    }
  }

  /**
   * Validates an offered SCU amount against the item's stock and rounds it to three decimals
   * (REQ-MARKET-002).
   *
   * @param requested the client-supplied offered quantity in SCU
   * @param item the source Lager row whose current amount caps the offer
   * @return the offered quantity rounded to three-decimal SCU precision
   * @throws BadRequestException if the amount is not positive or exceeds the item's current stock
   */
  private static double requireOfferableAmount(@Nullable Double requested, InventoryItem item) {
    if (requested == null || requested <= 0.0) {
      throw new BadRequestException("The offered amount must be a positive quantity.");
    }
    double offered = InventoryItem.roundToScuScale(requested);
    double available = item.getAmount() == null ? 0.0 : item.getAmount();
    if (offered <= 0.0) {
      throw new BadRequestException("The offered amount must be a positive quantity.");
    }
    if (offered > available) {
      throw new BadRequestException(
          "The offered amount exceeds the item's available stock ("
              + offered
              + " > "
              + available
              + ").");
    }
    return offered;
  }

  /**
   * Validates an item-offer quantity as a positive whole number.
   *
   * @param requested the client-supplied whole-unit quantity
   * @return the quantity as a whole int
   * @throws BadRequestException if the quantity is absent, below one, or not a whole number
   */
  private static int wholeItemQuantity(@Nullable Double requested) {
    if (requested == null || requested < 1.0) {
      throw new BadRequestException("The offered item quantity must be at least one whole unit.");
    }
    long rounded = Math.round(requested);
    if (Math.abs(requested - rounded) > 1e-6) {
      throw new BadRequestException("The offered item quantity must be a whole number.");
    }
    return (int) rounded;
  }

  /**
   * Validates a stock-backed item-offer quantity as a positive whole number no greater than the
   * backing row's stock (REQ-MARKET-014).
   *
   * @param requested the client-supplied whole-unit quantity
   * @param item the backing game-item Lager row whose current amount caps the offer
   * @return the offered quantity as a whole int
   * @throws BadRequestException if the quantity is not a positive whole number or exceeds the row's
   *     current stock
   */
  private static int requireOfferableItemQuantity(@Nullable Double requested, InventoryItem item) {
    int quantity = wholeItemQuantity(requested);
    double available = item.getAmount() == null ? 0.0 : item.getAmount();
    if (quantity > available) {
      throw new BadRequestException(
          "The offered quantity exceeds the item's available stock ("
              + quantity
              + " > "
              + available
              + ").");
    }
    return quantity;
  }

  /**
   * Returns the PII-free audit subject label of an offer: the material name or the snapshotted item
   * name.
   *
   * @param offer the offer
   * @return the audit subject label
   */
  private static String offerLabel(MaterialExchangeOffer offer) {
    if (offer.getKind() == MaterialExchangeOfferKind.ITEM) {
      return offer.getItemName();
    }
    return offer.getInventoryItem().getMaterial().getName();
  }

  /**
   * Builds the PII-free {@code kind=… subject=…} audit details of an offer: the Lager row id for a
   * material offer, the blueprint product key for an item offer.
   *
   * @param offer the offer
   * @return the composed audit details
   */
  private static AuditDetails offerSubjectDetails(MaterialExchangeOffer offer) {
    if (offer.getKind() == MaterialExchangeOfferKind.ITEM) {
      return AuditDetails.of("kind", MaterialExchangeOfferKind.ITEM)
          .with("product", offer.getItemProductKey());
    }
    return AuditDetails.of("kind", MaterialExchangeOfferKind.MATERIAL)
        .with("item", offer.getInventoryItem().getId());
  }

  /**
   * The character length of a remark, treating {@code null} as 0 — the only remark fact ever
   * recorded in an audit detail (never the body).
   *
   * @param remark the raw remark, possibly {@code null}.
   * @return the length, or 0.
   */
  private static int remarkLength(@Nullable String remark) {
    return remark == null ? 0 : remark.length();
  }
}
