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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeCountsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeInterestCount;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeReleasableItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeInterestRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.MaterialExchangeQueryParams;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read half of the Materialbörse offer board (REQ-MARKET-001): the paged board, tab counts, offer
 * detail, Lager flags and release picker, plus the interessenten-anonymity redaction.
 *
 * <p>The board is org-wide with no OrgUnit scope filter. Interessenten names are disclosed only to
 * the offer's owner (REQ-MARKET-006). {@link MaterialExchangeService} projects its mutation results
 * through this service, joining its write transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialExchangeBoardService {

  /** Cap on the number of rows the "Material anbieten" item picker returns. */
  private static final int PICKER_LIMIT = 50;

  /**
   * Org-unit kinds surfaced as an Anbieter's affiliation badges (REQ-MARKET-001): the member's
   * Staffeln, Spezialkommandos and Bereiche. The Organisationsleitung is deliberately excluded — a
   * leadership-only affiliation carries no board badge.
   */
  private static final List<OrgUnitKind> BADGE_KINDS =
      List.of(OrgUnitKind.SQUADRON, OrgUnitKind.SPECIAL_COMMAND, OrgUnitKind.BEREICH);

  /**
   * Display order of an Anbieter's affiliation badges: Staffel(n) first, then Spezialkommando(s),
   * then Bereich(e) (see {@link #badgeRank(OrgUnitKind)}), each group name-sorted
   * case-insensitively — so the same member's badges read in a stable order across the board.
   */
  private static final Comparator<OrgUnit> ORG_UNIT_BADGE_ORDER =
      Comparator.<OrgUnit>comparingInt(ou -> badgeRank(ou.getKind()))
          .thenComparing(
              ou -> ou.getName() == null ? "" : ou.getName(), String.CASE_INSENSITIVE_ORDER);

  private final MaterialExchangeOfferRepository offerRepository;
  private final MaterialExchangeInterestRepository interestRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final AuthHelperService authHelperService;
  private final UserMapper userMapper;

  /**
   * Batch-loads each offering member's badge-kind memberships so the board shows all of the
   * Anbieter's affiliation badges without N+1.
   */
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;

  /**
   * Resolves the membership rows' org-unit ids to their concrete {@link OrgUnit} entities (id,
   * name, shorthand, kind) for the anbieter affiliation badges — one batch {@code findAllById} per
   * board page rather than a per-offer lookup.
   */
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Returns a page of the board, either all offers or the caller's own, with the toolbar filters
   * and sort applied; list DTOs never carry interessenten names.
   *
   * @param tab {@code "mein"} for the caller's own offers, anything else (incl. {@code null}) for
   *     all offers
   * @param query text matched against material name and owner handle, or {@code null}/blank for
   *     none
   * @param minQuality inclusive minimum quality 0–1000; 0/{@code null} disables the filter
   * @param minAmount inclusive minimum amount in SCU, or {@code null} for none
   * @param sort the sort key: {@code qual} (default) / {@code menge} / {@code mat} / {@code neu}
   * @param page the zero-based page index
   * @param size the page size (clamped to {@value MaterialExchangeQueryParams#MAX_PAGE_SIZE})
   * @return the matching page of board offers
   */
  public PageResponse<MaterialExchangeOfferDto> board(
      @Nullable String tab,
      @Nullable String query,
      @Nullable Integer minQuality,
      @Nullable Double minAmount,
      @Nullable String sort,
      @Nullable Integer page,
      @Nullable Integer size) {
    UUID viewerId = authHelperService.currentUserId().orElse(null);
    boolean onlyMine = "mein".equalsIgnoreCase(tab);
    Pageable pageable =
        PageRequest.of(
            MaterialExchangeQueryParams.clampPage(page),
            MaterialExchangeQueryParams.clampSize(size));
    Page<MaterialExchangeOffer> offers =
        offerRepository.findBoard(
            viewerId,
            onlyMine,
            MaterialExchangeQueryParams.normalizeQuery(query),
            MaterialExchangeQueryParams.clampQuality(minQuality),
            minAmount,
            MaterialExchangeQueryParams.normalizeSort(sort),
            pageable);

    List<UUID> offerIds = offers.getContent().stream().map(MaterialExchangeOffer::getId).toList();
    Map<UUID, Long> counts = interestCounts(offerIds);
    Set<UUID> interested =
        viewerId == null || offerIds.isEmpty()
            ? Set.of()
            : interestRepository.findOfferIdsInterestedByViewer(viewerId, offerIds);
    Map<UUID, List<OrgUnitReferenceDto>> badges =
        ownerOrgUnitBadges(ownerIdsOf(offers.getContent()));

    Page<MaterialExchangeOfferDto> dtos =
        offers.map(
            offer ->
                toDto(
                    offer,
                    viewerId,
                    badges.getOrDefault(ownerIdOf(offer), List.of()),
                    counts.getOrDefault(offer.getId(), 0L).intValue(),
                    interested.contains(offer.getId()),
                    null));
    return PageResponse.of(dtos);
  }

  /**
   * Returns the board tab counts (all active offers and the caller's own), unaffected by the board
   * filters.
   *
   * @return the tab counts
   */
  @NotNull
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
  @NotNull
  public MaterialExchangeOfferDto detail(UUID offerId) {
    UUID viewerId = authHelperService.currentUserId().orElse(null);
    MaterialExchangeOffer offer = loadWithDetail(offerId);
    return detailDto(offer, viewerId);
  }

  /**
   * Returns the subset of the given Lager rows that currently carry an active board offer — the
   * "Auf Börse" status flag for a batch of leaf entries (no N+1).
   *
   * @param inventoryItemIds the Lager rows being rendered.
   * @return the ids of the rows with an active offer, never {@code null}.
   */
  public Set<UUID> releasedInventoryItemIds(Collection<UUID> inventoryItemIds) {
    if (inventoryItemIds.isEmpty()) {
      return Set.of();
    }
    return offerRepository.findInventoryItemIdsWithStatus(
        MaterialExchangeOfferStatus.ACTIVE, inventoryItemIds);
  }

  /**
   * Returns the caller's own Lager rows eligible for release in the Materialbörse picker, flagged
   * when already offered and capped at {@value #PICKER_LIMIT} rows.
   *
   * <p>The {@code kind} filter applies before the cap (REQ-MARKET-002, REQ-MARKET-014).
   *
   * @param query a material or item name fragment, or {@code null}/blank for the whole stock
   * @param kind the row kind to return, or {@code null} for both material and item rows
   * @return the caller's releasable rows; never {@code null}
   */
  public List<MaterialExchangeReleasableItemDto> myReleasableItems(
      @Nullable String query, @Nullable MaterialExchangeOfferKind kind) {
    UUID viewerId = requireViewerId();
    boolean includeMaterial = kind == null || kind == MaterialExchangeOfferKind.MATERIAL;
    boolean includeItem = kind == null || kind == MaterialExchangeOfferKind.ITEM;
    List<InventoryItem> items =
        inventoryItemRepository.findReleasableForUser(
            viewerId,
            MaterialExchangeQueryParams.normalizeQuery(query),
            includeMaterial,
            includeItem,
            PageRequest.of(0, PICKER_LIMIT));
    Set<UUID> released =
        releasedInventoryItemIds(items.stream().map(InventoryItem::getId).toList());
    return items.stream()
        .map(item -> toReleasableDto(item, released.contains(item.getId())))
        .toList();
  }

  /**
   * Maps one of the caller's Lager rows to a release-picker entry: an {@code ITEM} entry for a
   * game-item row (PIECE unit, no quality), a {@code MATERIAL} entry otherwise.
   *
   * @param item the Lager row with material / game item and location loaded
   * @param alreadyReleased whether an active offer already backs this row
   * @return the picker entry
   */
  @NotNull
  private static MaterialExchangeReleasableItemDto toReleasableDto(
      @NotNull InventoryItem item, boolean alreadyReleased) {
    boolean isItem = item.getGameItem() != null;
    String locationName = item.getLocation() == null ? null : item.getLocation().getName();
    if (isItem) {
      return new MaterialExchangeReleasableItemDto(
          item.getId(),
          MaterialExchangeOfferKind.ITEM,
          item.getGameItem().getName(),
          QuantityType.PIECE,
          null,
          item.getAmount(),
          locationName,
          alreadyReleased);
    }
    return new MaterialExchangeReleasableItemDto(
        item.getId(),
        MaterialExchangeOfferKind.MATERIAL,
        item.getMaterial().getName(),
        item.getMaterial().getQuantityType(),
        item.getQuality(),
        item.getAmount(),
        locationName,
        alreadyReleased);
  }

  /**
   * Builds the viewer-relative offer detail, loading interessenten names only when the viewer owns
   * the offer (REQ-MARKET-006).
   *
   * @param offer the offer to project
   * @param viewerId the requesting member, or {@code null} if unresolved
   * @return the offer detail
   */
  @NotNull
  public MaterialExchangeOfferDto detailDto(MaterialExchangeOffer offer, @Nullable UUID viewerId) {
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
    UUID ownerId = ownerIdOf(offer);
    List<OrgUnitReferenceDto> badges =
        ownerId == null
            ? List.of()
            : ownerOrgUnitBadges(Set.of(ownerId)).getOrDefault(ownerId, List.of());
    return toDto(offer, viewerId, badges, count, viewerInterested, names);
  }

  /**
   * Assembles the offer DTO from the entity and the viewer-relative facts.
   *
   * @param offer the offer.
   * @param viewerId the requesting member, or {@code null}.
   * @param ownerOrgUnits the Anbieter's affiliation badges (Staffel(n) then SK(s)), never {@code
   *     null}.
   * @param interestCount the interessenten count.
   * @param viewerInterested whether the viewer has registered interest.
   * @param interestedHandles the interessenten handles (owner-only), or {@code null}.
   * @return the assembled DTO.
   */
  @NotNull
  private MaterialExchangeOfferDto toDto(
      MaterialExchangeOffer offer,
      @Nullable UUID viewerId,
      List<OrgUnitReferenceDto> ownerOrgUnits,
      int interestCount,
      boolean viewerInterested,
      @Nullable List<String> interestedHandles) {
    boolean mine = isMine(offer, viewerId);

    MaterialReferenceDto material = null;
    String itemName = null;
    Integer itemQuantity = null;
    Integer quality = null;
    Double amount = null;
    Double availableAmount = null;
    if (offer.getKind() == MaterialExchangeOfferKind.ITEM) {
      itemName = offer.getItemName();
      itemQuantity = effectiveItemQuantity(offer);
      availableAmount =
          mine && offer.getInventoryItem() != null ? offer.getInventoryItem().getAmount() : null;
    } else {
      InventoryItem item = offer.getInventoryItem();
      Material mat = item.getMaterial();
      material = new MaterialReferenceDto(mat.getId(), mat.getName(), mat.getQuantityType());
      quality = item.getQuality();
      amount = effectiveOfferedAmount(offer);
      availableAmount = mine ? item.getAmount() : null;
    }
    return new MaterialExchangeOfferDto(
        offer.getId(),
        offer.getKind(),
        material,
        itemName,
        itemQuantity,
        userMapper.toReferenceDto(offer.getOwner()),
        ownerOrgUnits,
        mine,
        quality,
        amount,
        availableAmount,
        offer.getReleasedAt(),
        offer.getRemark(),
        interestCount,
        interestedHandles,
        viewerInterested,
        offer.getStatus(),
        offer.getVersion());
  }

  /**
   * The distinct, non-null owner ids across a page of offers — the input set for the batched
   * anbieter-affiliation badge resolution.
   *
   * @param offers the board page's offers.
   * @return the distinct owner ids (a defensively null-owner offer contributes nothing).
   */
  private static Set<UUID> ownerIdsOf(@NotNull Collection<MaterialExchangeOffer> offers) {
    return offers.stream()
        .map(MaterialExchangeBoardService::ownerIdOf)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  /**
   * The owner id of an offer, or {@code null} for the defensive no-owner case.
   *
   * @param offer the offer.
   * @return the owner's user id, or {@code null}.
   */
  @Nullable
  private static UUID ownerIdOf(@NotNull MaterialExchangeOffer offer) {
    return offer.getOwner() == null ? null : offer.getOwner().getId();
  }

  /**
   * Batch-resolves the given members' Staffel, Spezialkommando and Bereich badges in two queries,
   * ordered by {@link #ORG_UNIT_BADGE_ORDER}; memberships whose org unit no longer resolves are
   * dropped.
   *
   * @param ownerIds the offering members; an empty set yields an empty map
   * @return owner id to ordered badges; members without memberships are absent
   */
  @NotNull
  private Map<UUID, List<OrgUnitReferenceDto>> ownerOrgUnitBadges(Set<UUID> ownerIds) {
    if (ownerIds.isEmpty()) {
      return Map.of();
    }
    List<OrgUnitMembership> rows =
        orgUnitMembershipRepository.findAllByIdUserIdInAndKindIn(ownerIds, BADGE_KINDS);
    if (rows.isEmpty()) {
      return Map.of();
    }
    Set<UUID> orgUnitIds =
        rows.stream().map(row -> row.getId().getOrgUnitId()).collect(Collectors.toSet());
    Map<UUID, OrgUnit> orgUnitsById =
        orgUnitRepository.findAllById(orgUnitIds).stream()
            .collect(Collectors.toMap(OrgUnit::getId, ou -> ou));
    Map<UUID, List<OrgUnitReferenceDto>> byOwner = new HashMap<>();
    rows.stream()
        .collect(Collectors.groupingBy(row -> row.getId().getUserId()))
        .forEach(
            (ownerId, ownerRows) ->
                byOwner.put(
                    ownerId,
                    ownerRows.stream()
                        .map(row -> orgUnitsById.get(row.getId().getOrgUnitId()))
                        .filter(Objects::nonNull)
                        .sorted(ORG_UNIT_BADGE_ORDER)
                        .map(
                            ou ->
                                new OrgUnitReferenceDto(
                                    ou.getId(), ou.getName(), ou.getShorthand(), ou.getKind()))
                        .toList()));
    return byOwner;
  }

  /**
   * Sort rank of an org-unit kind for badges: Staffel, Spezialkommando, Bereich, then
   * Organisationsleitung.
   *
   * @param kind the org-unit kind
   * @return the badge sort rank (lower sorts first)
   */
  private static int badgeRank(OrgUnitKind kind) {
    return switch (kind) {
      case SQUADRON -> 0;
      case SPECIAL_COMMAND -> 1;
      case BEREICH -> 2;
      case ORGANISATIONSLEITUNG -> 3;
    };
  }

  /**
   * Loads an offer with its board associations eager-fetched (item / material / owner / org unit).
   *
   * @param offerId the offer id.
   * @return the loaded offer.
   * @throws NotFoundException if no such offer exists.
   */
  private MaterialExchangeOffer loadWithDetail(UUID offerId) {
    return Entities.require(
        offerRepository.findWithDetailById(offerId), () -> "Offer not found: " + offerId);
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
   * Returns the offered amount clamped to the item's current stock, matching the board query's
   * {@code LEAST(offeredAmount, item.amount)}; never negative.
   *
   * @param offer the offer, with its item loaded
   * @return the clamped offered quantity in SCU
   */
  private static double effectiveOfferedAmount(@NotNull MaterialExchangeOffer offer) {
    Double offered = offer.getOfferedAmount();
    Double stock = offer.getInventoryItem().getAmount();
    double offeredValue = offered == null ? 0.0 : offered;
    double stockValue = stock == null ? 0.0 : stock;
    return Math.max(0.0, Math.min(offeredValue, stockValue));
  }

  /**
   * Returns the item quantity of an {@link MaterialExchangeOfferKind#ITEM} offer, clamped to the
   * backing row's stock for a stock-backed offer; never negative.
   *
   * @param offer the item offer
   * @return the clamped whole-unit quantity, or {@code null} if the offer states none
   */
  @Nullable
  private static Integer effectiveItemQuantity(@NotNull MaterialExchangeOffer offer) {
    Integer quantity = offer.getItemQuantity();
    if (quantity == null) {
      return null;
    }
    InventoryItem item = offer.getInventoryItem();
    if (item == null || item.getAmount() == null) {
      return quantity;
    }
    int stock = (int) Math.floor(item.getAmount());
    return Math.max(0, Math.min(quantity, stock));
  }

  /**
   * Resolves the current authenticated member's id or fails — used by the owner-scoped release
   * picker.
   *
   * @return the caller's user id.
   * @throws AccessDeniedException if there is no authenticated member.
   */
  private UUID requireViewerId() {
    return authHelperService
        .currentUserId()
        .orElseThrow(() -> new AccessDeniedException("Authentication required."));
  }
}
