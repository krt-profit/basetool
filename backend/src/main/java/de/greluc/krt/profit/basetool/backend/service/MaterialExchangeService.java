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
import de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeInterest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeCountsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeInterestCount;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeOfferUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeReleasableItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeReleaseRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeInterestRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the Materialbörse — the org-wide material-exchange trade board of Flotte
 * &amp; Logistik (REQ-MARKET-001…). Owns the board read model, the release / deactivate / edit
 * lifecycle of an offer, and the interest register / withdraw signals, plus the anonymity redaction
 * and the audit trail for every mutation.
 *
 * <p><b>Board scope (decision D3):</b> the board is org-wide — every {@code ACTIVE} offer is
 * visible to every member regardless of the offer's owning org unit; there is no OrgUnit scope
 * filter. Only real members reach this service (the controller gates reads on {@code KRT_MEMBER}).
 *
 * <p><b>Facts (decision D1, amended by ADR-0086):</b> material and quality are read live from the
 * linked {@link InventoryItem}; the offered amount is the owner's stored choice (a whole row or a
 * part of it); the item's location is never read, so the Standort stays private.
 *
 * <p><b>Anonymity (REQ-MARKET-006):</b> the interessenten names are disclosed only to the offer's
 * owner; every other viewer sees only the count. The redaction lives here, in {@link
 * #detailDto(MaterialExchangeOffer, UUID)} — a name list is loaded only when the viewer is the
 * owner.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaterialExchangeService {

  /** Default board page size when the caller does not specify one. */
  private static final int DEFAULT_PAGE_SIZE = 50;

  /** Upper bound on the board page size — the list is scrollable, not deeply paginated. */
  private static final int MAX_PAGE_SIZE = 500;

  /** Cap on the number of rows the "Material anbieten" item picker returns. */
  private static final int PICKER_LIMIT = 50;

  private final MaterialExchangeOfferRepository offerRepository;
  private final MaterialExchangeInterestRepository interestRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final UserRepository userRepository;
  private final AuthHelperService authHelperService;
  private final AuditService auditService;
  private final UserMapper userMapper;
  private final SquadronMapper squadronMapper;

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
   * Returns a page of the board — the "Alle Angebote" tab, or the caller's own offers for the
   * "Meine Angebote" tab — applying the toolbar filters and sort. Interest counts and the viewer's
   * own registrations are batch-loaded so the list has no N+1; interessenten names are never
   * included in a list DTO.
   *
   * @param tab {@code "mein"} for the caller's own offers, anything else (incl. {@code null}) for
   *     all offers.
   * @param query a free-text fragment matched against the material name and the owner's handle, or
   *     {@code null}/blank for no text filter.
   * @param minQuality the inclusive minimum quality 0–1000 (0/{@code null} disables the filter).
   * @param minAmount the inclusive minimum amount in SCU, or {@code null} for no amount filter.
   * @param sort the sort key — {@code qual} (default) / {@code menge} / {@code mat} / {@code neu}.
   * @param page the zero-based page index.
   * @param size the page size (clamped to {@value #MAX_PAGE_SIZE}).
   * @return the matching page of board offers.
   */
  @Transactional(readOnly = true)
  public PageResponse<MaterialExchangeOfferDto> board(
      @Nullable String tab,
      @Nullable String query,
      @Nullable Integer minQuality,
      @Nullable Double minAmount,
      @Nullable String sort,
      @Nullable Integer page,
      @Nullable Integer size) {
    UUID viewerId = authHelperService.currentUserId().orElse(null);
    UUID viewerSquadronId = authHelperService.currentSquadronId().orElse(null);
    boolean onlyMine = "mein".equalsIgnoreCase(tab);
    Pageable pageable = PageRequest.of(clampPage(page), clampSize(size), sortFor(sort));
    Page<MaterialExchangeOffer> offers =
        offerRepository.findBoard(
            viewerId,
            onlyMine,
            normalizeQuery(query),
            clampQuality(minQuality),
            minAmount,
            pageable);

    List<UUID> offerIds = offers.getContent().stream().map(MaterialExchangeOffer::getId).toList();
    Map<UUID, Long> counts = interestCounts(offerIds);
    Set<UUID> interested =
        viewerId == null || offerIds.isEmpty()
            ? Set.of()
            : interestRepository.findOfferIdsInterestedByViewer(viewerId, offerIds);

    Page<MaterialExchangeOfferDto> dtos =
        offers.map(
            offer ->
                toDto(
                    offer,
                    viewerId,
                    viewerSquadronId,
                    counts.getOrDefault(offer.getId(), 0L).intValue(),
                    interested.contains(offer.getId()),
                    null));
    return PageResponse.of(dtos);
  }

  /**
   * Returns the board tab counts (all active offers, and the caller's own active offers). These are
   * board totals — deliberately unaffected by the search/quality/amount filters. No stock guard is
   * needed: a fully-booked-out row is deleted and its offer cascade-deleted (ADR-0086), so every
   * {@code ACTIVE} offer is on the board and the badge matches the visible list.
   *
   * @return the tab counts.
   */
  @Transactional(readOnly = true)
  public MaterialExchangeCountsDto counts() {
    UUID viewerId = authHelperService.currentUserId().orElse(null);
    long all = offerRepository.countByStatus(MaterialExchangeOfferStatus.ACTIVE);
    long mine =
        viewerId == null
            ? 0
            : offerRepository.countByStatusAndOwnerId(MaterialExchangeOfferStatus.ACTIVE, viewerId);
    return new MaterialExchangeCountsDto(all, mine);
  }

  /**
   * Loads a single offer for the detail pane, including the interessenten names when the caller is
   * the owner.
   *
   * @param offerId the offer to load.
   * @return the viewer-relative offer detail.
   * @throws NotFoundException if no offer with that id exists.
   */
  @Transactional(readOnly = true)
  public MaterialExchangeOfferDto detail(UUID offerId) {
    UUID viewerId = authHelperService.currentUserId().orElse(null);
    MaterialExchangeOffer offer = loadWithDetail(offerId);
    return detailDto(offer, viewerId);
  }

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
        materialLabel(item),
        item.getUser().getId(),
        AuditDetails.of("item", item.getId())
            .with("q", item.getQuality())
            .with("amt", offeredAmount)
            .with("stock", item.getAmount())
            .with("remarkLen", remarkLength(request.remark()))
            .with("reRelease", reRelease));
    return detailDto(saved, viewerId);
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
        materialLabel(offer.getInventoryItem()),
        offer.getOwner().getId(),
        AuditDetails.of("amt", offeredAmount).with("remarkLen", remarkLength(request.remark())));
    return detailDto(saved, viewerId);
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
    return selfProvider.getObject().detail(offerId);
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
        materialLabel(offer.getInventoryItem()),
        offer.getOwner() == null ? null : offer.getOwner().getId(),
        AuditDetails.of("offer", offerId));
    // Notify the owner about the new interested party (#1187, REQ-MARKET-011). Published only on a
    // genuinely new registration (the idempotent-duplicate return above skips it), inside this
    // transaction so the after-commit listener never fires for a rolled-back registration.
    if (offer.getOwner() != null) {
      eventPublisher.publishEvent(
          new MaterialExchangeInterestRegisteredEvent(
              offerId,
              materialLabel(offer.getInventoryItem()),
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
          materialLabel(offer.getInventoryItem()),
          offer.getOwner() == null ? null : offer.getOwner().getId(),
          AuditDetails.of("offer", offerId));
    }
    return detailDto(offer, viewerId);
  }

  /**
   * Returns the subset of the given Lager rows that currently carry an active board offer — the
   * "Auf Börse" status flag for a batch of leaf entries (no N+1).
   *
   * @param inventoryItemIds the Lager rows being rendered.
   * @return the ids of the rows with an active offer, never {@code null}.
   */
  @Transactional(readOnly = true)
  public Set<UUID> releasedInventoryItemIds(Collection<UUID> inventoryItemIds) {
    if (inventoryItemIds.isEmpty()) {
      return Set.of();
    }
    return offerRepository.findInventoryItemIdsWithStatus(
        MaterialExchangeOfferStatus.ACTIVE, inventoryItemIds);
  }

  /**
   * Returns the caller's own Lager rows eligible for release, for the "Material anbieten" item
   * picker — optionally filtered by a material-name fragment and capped at {@value #PICKER_LIMIT}
   * rows. Each entry flags whether it already carries an active offer.
   *
   * @param query a material-name fragment, or {@code null}/blank for the caller's whole stock.
   * @return the caller's releasable items (owner-scoped), never {@code null}.
   */
  @Transactional(readOnly = true)
  public List<MaterialExchangeReleasableItemDto> myReleasableItems(@Nullable String query) {
    UUID viewerId = requireViewerId();
    List<InventoryItem> items =
        inventoryItemRepository.findReleasableForUser(
            viewerId, normalizeQuery(query), PageRequest.of(0, PICKER_LIMIT));
    Set<UUID> released =
        releasedInventoryItemIds(items.stream().map(InventoryItem::getId).toList());
    return items.stream()
        .map(
            item ->
                new MaterialExchangeReleasableItemDto(
                    item.getId(),
                    item.getMaterial().getName(),
                    item.getMaterial().getQuantityType(),
                    item.getQuality(),
                    item.getAmount(),
                    item.getLocation() == null ? null : item.getLocation().getName(),
                    released.contains(item.getId())))
        .toList();
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
          materialLabel(offer.getInventoryItem()),
          offer.getOwner() == null ? null : offer.getOwner().getId(),
          AuditDetails.of("item", offer.getInventoryItem().getId()));
    }
    return detailDto(offer, viewerId);
  }

  /**
   * Builds the viewer-relative detail DTO, loading the interessenten names only when the viewer
   * owns the offer (anonymity gate, REQ-MARKET-006).
   *
   * @param offer the offer to project.
   * @param viewerId the requesting member, or {@code null} if unresolved.
   * @return the offer detail.
   */
  private MaterialExchangeOfferDto detailDto(MaterialExchangeOffer offer, @Nullable UUID viewerId) {
    UUID viewerSquadronId = authHelperService.currentSquadronId().orElse(null);
    boolean mine = isMine(offer, viewerId);
    int count = (int) interestRepository.countByOfferId(offer.getId());
    boolean viewerInterested =
        viewerId != null
            && interestRepository.existsByOfferIdAndInterestedUserId(offer.getId(), viewerId);
    List<String> names =
        mine
            ? interestRepository.findByOfferIdOrderByCreatedAtDesc(offer.getId()).stream()
                .map(interest -> interest.getInterestedUser().getEffectiveName())
                .toList()
            : null;
    return toDto(offer, viewerId, viewerSquadronId, count, viewerInterested, names);
  }

  /**
   * Assembles the offer DTO from the entity and the viewer-relative facts.
   *
   * @param offer the offer.
   * @param viewerId the requesting member, or {@code null}.
   * @param viewerSquadronId the requesting member's squadron, or {@code null}.
   * @param interestCount the interessenten count.
   * @param viewerInterested whether the viewer has registered interest.
   * @param interestedHandles the interessenten handles (owner-only), or {@code null}.
   * @return the assembled DTO.
   */
  private MaterialExchangeOfferDto toDto(
      MaterialExchangeOffer offer,
      @Nullable UUID viewerId,
      @Nullable UUID viewerSquadronId,
      int interestCount,
      boolean viewerInterested,
      @Nullable List<String> interestedHandles) {
    InventoryItem item = offer.getInventoryItem();
    Material material = item.getMaterial();
    SquadronReferenceDto squadron = squadronMapper.orgUnitToReferenceDto(offer.getOwningOrgUnit());
    boolean foreign =
        squadron != null && viewerSquadronId != null && !viewerSquadronId.equals(squadron.id());
    boolean mine = isMine(offer, viewerId);
    return new MaterialExchangeOfferDto(
        offer.getId(),
        new MaterialReferenceDto(material.getId(), material.getName(), material.getQuantityType()),
        userMapper.toReferenceDto(offer.getOwner()),
        squadron,
        foreign,
        mine,
        item.getQuality(),
        effectiveOfferedAmount(offer),
        mine ? item.getAmount() : null,
        offer.getReleasedAt(),
        offer.getRemark(),
        interestCount,
        interestedHandles,
        viewerInterested,
        offer.getStatus(),
        offer.getVersion());
  }

  /**
   * Loads an offer with its board associations eager-fetched (item / material / owner / org unit).
   *
   * @param offerId the offer id.
   * @return the loaded offer.
   * @throws NotFoundException if no such offer exists.
   */
  private MaterialExchangeOffer loadWithDetail(UUID offerId) {
    return offerRepository
        .findWithDetailById(offerId)
        .orElseThrow(() -> new NotFoundException("Offer not found: " + offerId));
  }

  /**
   * Batch-loads the interessenten counts for the given offers into a lookup map (offers with no
   * registrations are simply absent).
   *
   * @param offerIds the offers to count.
   * @return offer id → count.
   */
  private Map<UUID, Long> interestCounts(List<UUID> offerIds) {
    if (offerIds.isEmpty()) {
      return Map.of();
    }
    return interestRepository.countByOfferIdIn(offerIds).stream()
        .collect(
            Collectors.toMap(
                MaterialExchangeInterestCount::offerId, MaterialExchangeInterestCount::count));
  }

  /**
   * Whether the given member owns the offer.
   *
   * @param offer the offer.
   * @param viewerId the member, or {@code null}.
   * @return {@code true} if the member is the offer's owner.
   */
  private boolean isMine(MaterialExchangeOffer offer, @Nullable UUID viewerId) {
    return viewerId != null
        && offer.getOwner() != null
        && viewerId.equals(offer.getOwner().getId());
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
   * The effective offered quantity served to the board — the stored {@link
   * MaterialExchangeOffer#getOfferedAmount() offeredAmount} clamped to the item's <em>current</em>
   * stock ({@code min(offered, item.amount)}), so the board never advertises more than is in stock
   * and the offer shrinks as the row is booked out (ADR-0086). This mirrors the {@code
   * LEAST(offeredAmount, item.amount)} the board query filters and sorts on. Never negative.
   *
   * @param offer the offer, with its item loaded.
   * @return the clamped offered quantity in SCU.
   */
  private static double effectiveOfferedAmount(MaterialExchangeOffer offer) {
    Double offered = offer.getOfferedAmount();
    Double stock = offer.getInventoryItem().getAmount();
    double offeredValue = offered == null ? 0.0 : offered;
    double stockValue = stock == null ? 0.0 : stock;
    return Math.max(0.0, Math.min(offeredValue, stockValue));
  }

  /**
   * The material name of an offer's item — a non-personal audit subject label.
   *
   * @param item the offer's Lager item.
   * @return the material name.
   */
  private String materialLabel(InventoryItem item) {
    return item.getMaterial().getName();
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

  /**
   * Normalises a search term into a lowercased {@code %fragment%} LIKE pattern, or {@code null}
   * when blank.
   *
   * @param query the raw search term.
   * @return the LIKE pattern, or {@code null}.
   */
  private static @Nullable String normalizeQuery(@Nullable String query) {
    if (query == null || query.isBlank()) {
      return null;
    }
    return "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
  }

  /**
   * Clamps a client minimum-quality filter to a non-negative int (0 disables the filter).
   *
   * @param minQuality the raw value.
   * @return the clamped value.
   */
  private static int clampQuality(@Nullable Integer minQuality) {
    return minQuality == null ? 0 : Math.max(0, minQuality);
  }

  /**
   * Clamps a client page index to a non-negative int.
   *
   * @param page the raw value.
   * @return the clamped value.
   */
  private static int clampPage(@Nullable Integer page) {
    return page == null || page < 0 ? 0 : page;
  }

  /**
   * Clamps a client page size into {@code [1, MAX_PAGE_SIZE]}, defaulting when absent.
   *
   * @param size the raw value.
   * @return the clamped value.
   */
  private static int clampSize(@Nullable Integer size) {
    if (size == null || size <= 0) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  /**
   * Maps a board sort key to a Spring Data {@link Sort} over the live item facts, always with a
   * stable {@code releasedAt desc, id desc} tiebreaker so pagination is deterministic. The {@code
   * menge} key sorts on the <b>effective</b> offered quantity {@code LEAST(offeredAmount,
   * item.amount)} (via {@link JpaSort#unsafe}), so an offer whose stock has been booked out below
   * its stated amount ranks by what is actually on offer, matching the clamped board display
   * (ADR-0086).
   *
   * @param key the sort key — {@code menge} / {@code mat} / {@code neu}, else quality (the
   *     default).
   * @return the resolved sort.
   */
  private static Sort sortFor(@Nullable String key) {
    Sort primary =
        switch (key == null ? "qual" : key) {
          case "menge" ->
              JpaSort.unsafe(Sort.Direction.DESC, "LEAST(o.offeredAmount, o.inventoryItem.amount)");
          case "mat" -> Sort.by(Sort.Order.asc("inventoryItem.material.name").ignoreCase());
          case "neu" -> Sort.by(Sort.Direction.DESC, "releasedAt");
          default -> Sort.by(Sort.Direction.DESC, "inventoryItem.quality");
        };
    return primary
        .and(Sort.by(Sort.Direction.DESC, "releasedAt"))
        .and(Sort.by(Sort.Direction.DESC, "id"));
  }
}
