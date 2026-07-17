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
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write/lifecycle half of the Materialbörse — the org-wide material-exchange trade board of Flotte
 * &amp; Logistik (REQ-MARKET-001…) — split out from the reads into {@link
 * MaterialExchangeBoardService} (audit Thema 7, #14). It owns the release / re-release / deactivate
 * / edit lifecycle of an offer, the interest register / withdraw signals, and the audit trail for
 * every mutation.
 *
 * <p>Every mutation projects its result through {@link MaterialExchangeBoardService} — the injected
 * read half — so the anonymity redaction (REQ-MARKET-006: interessenten names for the owner only)
 * is applied by the exact same code that serves {@link MaterialExchangeBoardService#detail(UUID)}.
 * The dependency is one-way (write→read), so the split introduces no cycle. The projection runs
 * inside the caller's write transaction (propagation {@code REQUIRED}), so it observes the
 * just-flushed offer.
 *
 * <p><b>Facts (decision D1, amended by ADR-0086):</b> material and quality are read live from the
 * linked {@link InventoryItem}; the offered amount is the owner's stored choice (a whole row or a
 * part of it), validated against the item's current stock on every write.
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
   * Resolves and validates a blueprint product for an item offer (#1185, REQ-MARKET-012): {@code
   * resolveByProductKey(...)} is both the "an item for which a blueprint exists" gate and the
   * source of the canonical display name snapshotted onto the offer.
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
   * Publishes the {@link MaterialExchangeInterestRegisteredEvent} that drives the owner's
   * interest-registered notification (#1187, REQ-MARKET-011). The after-commit notification
   * listener consumes it, so the publish stays a side-effect-free scalar hand-off inside the
   * registration transaction (REQ-NOTIF-002).
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
   * Releases one of the caller's own Lager rows to the board (REQ-MARKET-002/014). The row may be a
   * <b>material</b> row — releasing a {@link MaterialExchangeOfferKind#MATERIAL} offer whose
   * material and quality are read live from the item and whose offered quantity may be the whole
   * row or only a part of it (ADR-0086) — or a <b>game-item</b> row — releasing a stock-backed
   * {@link MaterialExchangeOfferKind#ITEM} offer (design §8), delegated to {@link
   * #releaseFromItemStock(InventoryItem, MaterialExchangeReleaseRequest, UUID)}. Either way the
   * caller-supplied {@link MaterialExchangeReleaseRequest#offeredAmount()} must be positive and at
   * most the item's current stock, and an existing active offer for the row is re-released rather
   * than duplicated. Owner and org unit are derived from the item — the caller never sets them.
   *
   * @param request the item id, the offered quantity and the trade remark.
   * @return the resulting offer detail (the caller is the owner, so names are included).
   * @throws NotFoundException if the item does not exist.
   * @throws AccessDeniedException if the item does not belong to the caller.
   * @throws BadRequestException if the offered amount exceeds the item's current stock, or a
   *     game-item row is not produced by any active blueprint.
   */
  @Transactional
  public MaterialExchangeOfferDto release(MaterialExchangeReleaseRequest request) {
    UUID viewerId = requireViewerId();
    InventoryItem item =
        inventoryItemRepository
            .findById(request.inventoryItemId())
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Inventory item not found: " + request.inventoryItemId()));
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
   * Releases a <b>game-item</b> Lager row as a stock-backed {@link MaterialExchangeOfferKind#ITEM}
   * offer (design §8, REQ-MARKET-014, ADR-0108) — the item sibling of the material release branch
   * of {@link #release(MaterialExchangeReleaseRequest)}. Unlike a free-stated item offer ({@link
   * #releaseItem(MaterialExchangeItemReleaseRequest)}, craft-on-demand), a stock-backed offer is
   * bound to the physical stock row: its offered quantity (carried in the request's {@code
   * offeredAmount} field, interpreted as whole units) must be positive and at most the row's
   * current stock, and its blueprint {@code productKey} + display name are derived from the row's
   * game item via {@link BlueprintProductService#resolveByGameItem(UUID)} (the same identity a
   * free-stated offer of the same item carries, ADR-0087). An existing active offer on the row is
   * re-released (the V210 one-active-offer-per-row index governs stock-backed item offers too),
   * otherwise a new one is created.
   *
   * @param item the caller's own game-item Lager row (ownership already checked).
   * @param request the release payload — {@code offeredAmount} is the whole-unit quantity to offer.
   * @param viewerId the acting owner.
   * @return the resulting offer detail (the caller is the owner, so names are included).
   * @throws BadRequestException if the quantity is not a positive whole number, exceeds the row's
   *     current stock, or the game item is not produced by any active blueprint.
   */
  private MaterialExchangeOfferDto releaseFromItemStock(
      InventoryItem item, MaterialExchangeReleaseRequest request, UUID viewerId) {
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
   * Lists a craftable item on the board (#1185, REQ-MARKET-012) — the "Item anbieten" counterpart
   * to {@link #release(MaterialExchangeReleaseRequest)}. Unlike a material release, an item offer
   * has no backing Lager row: the caller supplies the blueprint {@code productKey} and the
   * quantity, the product is validated against {@link
   * BlueprintProductService#resolveByProductKey(String)} (only items an active blueprint produces
   * can be listed) and its canonical display name is snapshotted, and owner + squadron are stamped
   * from the acting member. Item offers are not de-duplicated — a member may list the same item
   * several times — so this always inserts a fresh active offer.
   *
   * @param request the blueprint product key, the whole-piece quantity, and the trade remark.
   * @return the resulting offer detail (the caller is the owner, so names are included).
   * @throws NotFoundException if the caller is unknown or the product key resolves to no active
   *     blueprint product.
   */
  @Transactional
  public MaterialExchangeOfferDto releaseItem(MaterialExchangeItemReleaseRequest request) {
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
   * Edits an existing offer's offered quantity and trade remark ("Angebot bearbeiten",
   * REQ-MARKET-007/014). Only the owner may edit; the echoed version guards against a concurrent
   * edit. The edit is <b>kind-aware</b> (the item sibling of {@link
   * #release(MaterialExchangeReleaseRequest)}), so it never dereferences a {@code null}
   * inventoryItem on an item offer (the pre-existing defect this fixes):
   *
   * <ul>
   *   <li>a {@link MaterialExchangeOfferKind#MATERIAL} offer validates the new offered amount to be
   *       positive and at most the linked item's current stock (ADR-0086) and stores it in {@code
   *       offeredAmount};
   *   <li>a <b>stock-backed</b> {@link MaterialExchangeOfferKind#ITEM} offer validates the new
   *       quantity to be a positive whole number at most the backing row's current stock
   *       (REQ-MARKET-014) and stores it in {@code itemQuantity};
   *   <li>a <b>free-stated</b> item offer validates only that the new quantity is a positive whole
   *       number (it has no backing stock to cap against, REQ-MARKET-012) and stores it in {@code
   *       itemQuantity}.
   * </ul>
   *
   * <p>The request's {@code offeredAmount} field carries the SCU amount for a material offer and
   * the whole-unit quantity for an item offer.
   *
   * @param offerId the offer to edit.
   * @param request the new offered amount/quantity, remark and the client's last-seen version.
   * @return the updated offer detail.
   * @throws NotFoundException if the offer does not exist.
   * @throws AccessDeniedException if the caller is not the owner.
   * @throws BadRequestException if the amount/quantity is invalid or exceeds the item's current
   *     stock.
   */
  @Transactional
  public MaterialExchangeOfferDto updateOffer(
      UUID offerId, MaterialExchangeOfferUpdateRequest request) {
    UUID viewerId = requireViewerId();
    MaterialExchangeOffer offer =
        offerRepository
            .findById(offerId)
            .orElseThrow(() -> new NotFoundException("Offer not found: " + offerId));
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
        offerRepository
            .findById(offerId)
            .orElseThrow(() -> new NotFoundException("Offer not found: " + offerId));
    requireOwner(offer, viewerId);
    return deactivateOffer(offer, viewerId);
  }

  /**
   * Deactivates the active offer for a Lager row (un-checking "Für Börse freigeben" on the Lager
   * leaf). A no-op-safe entry point keyed by the item, since the Lager row knows its item id rather
   * than the offer id.
   *
   * @param inventoryItemId the Lager row whose active offer to take off the board.
   * @return the resulting offer detail.
   * @throws NotFoundException if the item has no active offer.
   * @throws AccessDeniedException if the caller is not the owner.
   */
  @Transactional
  public MaterialExchangeOfferDto deactivateForItem(UUID inventoryItemId) {
    UUID viewerId = requireViewerId();
    MaterialExchangeOffer offer =
        offerRepository
            .findByInventoryItemIdAndStatus(inventoryItemId, MaterialExchangeOfferStatus.ACTIVE)
            .orElseThrow(
                () -> new NotFoundException("No active offer for item: " + inventoryItemId));
    requireOwner(offer, viewerId);
    return deactivateOffer(offer, viewerId);
  }

  /**
   * Registers the caller's interest in an offer ("Interesse anmelden", REQ-MARKET-006). A
   * non-transactional orchestrator: it runs the insert in a fresh transaction through the proxy and
   * treats a concurrent duplicate-registration race (the unique {@code (offer, user)} constraint)
   * as an idempotent success (CLAUDE.md find-or-create rule), then re-reads the offer for the
   * response.
   *
   * @param offerId the offer to register interest in.
   * @return the resulting offer detail (with {@code iAmInterested = true}).
   * @throws NotFoundException if the offer does not exist or is not active.
   * @throws AccessDeniedException if the caller is the offer's owner.
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
   * The transactional insert behind {@link #registerInterest(UUID)}, run in a fresh transaction so
   * a unique-constraint violation aborts only this transaction (the orchestrator catches it).
   * Public so the Spring proxy applies the {@code REQUIRES_NEW} propagation. On a genuinely new
   * registration it publishes a {@link MaterialExchangeInterestRegisteredEvent} so the after-commit
   * notification listener alerts the offer's owner (#1187, REQ-MARKET-011); a duplicate
   * registration returns early and publishes nothing.
   *
   * @param offerId the offer to register interest in.
   * @param viewerId the registering member.
   * @throws NotFoundException if the offer does not exist or is not active.
   * @throws AccessDeniedException if the member is the offer's owner.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void registerInterestInNewTransaction(UUID offerId, UUID viewerId) {
    MaterialExchangeOffer offer =
        offerRepository
            .findById(offerId)
            .orElseThrow(() -> new NotFoundException("Offer not found: " + offerId));
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
        userRepository
            .findById(viewerId)
            .orElseThrow(() -> new NotFoundException("User not found: " + viewerId));
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
    // Notify the owner about the new interested party (#1187, REQ-MARKET-011). Published only on a
    // genuinely new registration (the idempotent-duplicate return above skips it), inside this
    // transaction so the after-commit listener never fires for a rolled-back registration.
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
        offerRepository
            .findById(offerId)
            .orElseThrow(() -> new NotFoundException("Offer not found: " + offerId));
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
   * Validates and normalises a client-supplied offered quantity against the item's current stock
   * (partial offers, REQ-MARKET-002 / ADR-0086): it must be a positive number no greater than the
   * item's amount, and is rounded to three-decimal SCU storage precision so the stored value never
   * carries floating-point noise. The {@code @Positive}/{@code @NotNull} DTO constraints already
   * reject the null/non-positive input on the controller path; this re-check keeps the service safe
   * when called directly and enforces the cross-field ceiling {@code @Valid} cannot express.
   *
   * @param requested the client-supplied offered quantity in SCU.
   * @param item the source Lager row whose current amount caps the offer.
   * @return the offered quantity rounded to three-decimal SCU precision.
   * @throws BadRequestException if the amount is not positive or exceeds the item's current stock.
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
   * Validates a client-supplied item-offer quantity as a positive whole number — the item sibling
   * of the positive/whole part of {@link #requireOfferableAmount(Double, InventoryItem)}
   * (REQ-MARKET-012/014). Used directly for a free-stated item offer (no backing stock to cap
   * against) and as the first step of {@link #requireOfferableItemQuantity(Double, InventoryItem)}.
   * The request carries the quantity as a {@link Double}; it must round to a whole number.
   *
   * @param requested the client-supplied whole-unit quantity.
   * @return the quantity as a whole int.
   * @throws BadRequestException if the quantity is absent, below one, or not a whole number.
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
   * Validates and normalises a client-supplied item-offer quantity against a <b>stock-backed</b>
   * item offer's backing row (design §8, REQ-MARKET-014) — the whole-unit item sibling of {@link
   * #requireOfferableAmount(Double, InventoryItem)}: the quantity must be a positive whole number
   * no greater than the game-item row's current stock. Enforced at release <em>and</em> edit,
   * mirroring the material rule.
   *
   * @param requested the client-supplied whole-unit quantity.
   * @param item the backing game-item Lager row whose current amount caps the offer.
   * @return the offered quantity as a whole int.
   * @throws BadRequestException if the quantity is not a positive whole number or exceeds the row's
   *     current stock.
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
   * The non-personal audit subject label of an offer — the material name for a {@link
   * MaterialExchangeOfferKind#MATERIAL} offer, the snapshotted item name for a {@link
   * MaterialExchangeOfferKind#ITEM} offer. Kind-aware so it never dereferences a {@code null}
   * inventoryItem for an item offer (both are game asset names, never PII).
   *
   * @param offer the offer.
   * @return the audit subject label.
   */
  private static String offerLabel(MaterialExchangeOffer offer) {
    if (offer.getKind() == MaterialExchangeOfferKind.ITEM) {
      return offer.getItemName();
    }
    return offer.getInventoryItem().getMaterial().getName();
  }

  /**
   * The PII-free {@code kind=… subject=…} audit details identifying an offer's subject — the Lager
   * row id for a material offer, the blueprint product key for an item offer. Kind-aware so it
   * never dereferences a {@code null} inventoryItem for an item offer.
   *
   * @param offer the offer.
   * @return the composed audit details.
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
