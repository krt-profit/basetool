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
   * Releases one of the caller's own Lager rows to the board (REQ-MARKET-002). The caller chooses
   * the offered quantity, which may be the whole row or only a part of it (ADR-0086) but must be
   * positive and at most the item's current stock. If an active offer already exists for the item,
   * its offered amount, remark and release instant are updated (re-release); otherwise a new active
   * offer is created. Owner, org unit, material and quality are all derived from the item — the
   * caller never sets them.
   *
   * @param request the item id, the offered quantity and the trade remark.
   * @return the resulting offer detail (the caller is the owner, so names are included).
   * @throws NotFoundException if the item does not exist.
   * @throws AccessDeniedException if the item does not belong to the caller.
   * @throws BadRequestException if the offered amount exceeds the item's current stock.
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
   * REQ-MARKET-007). Only the owner may edit; the echoed version guards against a concurrent edit.
   * The new offered amount must be positive and at most the linked item's current stock (ADR-0086).
   *
   * @param offerId the offer to edit.
   * @param request the new offered amount, remark and the client's last-seen version.
   * @return the updated offer detail.
   * @throws NotFoundException if the offer does not exist.
   * @throws AccessDeniedException if the caller is not the owner.
   * @throws BadRequestException if the offered amount exceeds the item's current stock.
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
    double offeredAmount =
        requireOfferableAmount(request.offeredAmount(), offer.getInventoryItem());
    offer.setOfferedAmount(offeredAmount);
    offer.setRemark(request.remark());
    MaterialExchangeOffer saved = offerRepository.saveAndFlush(offer);

    auditService.record(
        AuditEventType.MARKET_REMARK_UPDATED,
        offerId,
        offerLabel(offer),
        offer.getOwner().getId(),
        AuditDetails.of("amt", offeredAmount).with("remarkLen", remarkLength(request.remark())));
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
