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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/projection half of the Materialbörse — the org-wide material-exchange trade board of Flotte
 * &amp; Logistik (REQ-MARKET-001…) — split out of {@link MaterialExchangeService} (audit Thema 7,
 * #14). It owns every caller-visible read (the paged board, the tab counts, the single-offer
 * detail, the Lager "Auf Börse" flags and the release-picker list) plus the interessenten-anonymity
 * redaction and the offer→DTO mapping.
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
 *
 * <p><b>Write→read seam:</b> {@link #detailDto(MaterialExchangeOffer, UUID)} and {@link
 * #detail(UUID)} are public because the write half ({@link MaterialExchangeService}) injects this
 * service to project its own mutation results through the very same redaction — a one-way
 * write→read dependency, so no cycle. Called from inside a write transaction the projection joins
 * that transaction (propagation {@code REQUIRED}), so the class-level {@code readOnly} flag only
 * takes effect for the standalone reads that start their own transaction.
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
   * Reads each offering member's badge-kind memberships ({@link #BADGE_KINDS}: {@code SQUADRON} /
   * {@code SPECIAL_COMMAND} / {@code BEREICH}) so the board can render <b>all</b> of the Anbieter's
   * affiliation badges after the username (REQ-MARKET-001) — batch-loaded via {@link
   * OrgUnitMembershipRepository#findAllByIdUserIdInAndKindIn} to stay N+1-free across a board page.
   */
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;

  /**
   * Resolves the membership rows' org-unit ids to their concrete {@link OrgUnit} entities (id,
   * name, shorthand, kind) for the anbieter affiliation badges — one batch {@code findAllById} per
   * board page rather than a per-offer lookup.
   */
  private final OrgUnitRepository orgUnitRepository;

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
   * @param size the page size (clamped to {@value MaterialExchangeQueryParams#MAX_PAGE_SIZE}).
   * @return the matching page of board offers.
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
   * Returns the board tab counts (all active offers, and the caller's own active offers). These are
   * board totals — deliberately unaffected by the search/quality/amount filters. No stock guard is
   * needed: a fully-booked-out row is deleted and its offer cascade-deleted (ADR-0086), so every
   * {@code ACTIVE} offer is on the board and the badge matches the visible list.
   *
   * @return the tab counts.
   */
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
   * Returns the caller's own Lager rows eligible for release, for the Materialbörse release picker
   * — <b>both</b> material rows ("Material anbieten") and game-item rows (stock-backed item offers,
   * REQ-MARKET-014, design §8) — optionally filtered by a name fragment and by row {@code kind},
   * and capped at {@value #PICKER_LIMIT} rows. Each entry flags whether it already carries an
   * active offer, and is discriminated by kind so the release modal knows whether picking it
   * releases a material or a stock-backed item offer. A game-item row renders its item name, {@code
   * PIECE} unit and no quality; a material row keeps its material name, unit and quality.
   *
   * <p>The optional {@code kind} narrows the picker to one row kind for the dialog's Material/Item
   * radio (REQ-MARKET-002): {@code MATERIAL} returns only material rows, {@code ITEM} only
   * game-item rows, {@code null} both. The kind gate is pushed into the repository query so it
   * applies <em>before</em> the {@value #PICKER_LIMIT} cap — filtering the returned list would hide
   * every row of the wanted kind past the cap.
   *
   * @param query a name fragment (material or item), or {@code null}/blank for the caller's whole
   *     stock.
   * @param kind the row kind to return, or {@code null} for both material and item rows.
   * @return the caller's releasable rows of the selected kind(s), owner-scoped, never {@code null}.
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
   * Maps one of the caller's own Lager rows to a release-picker entry, branching on the row kind: a
   * game-item row (REQ-INV-029) becomes an {@code ITEM} entry with the item name, {@code PIECE}
   * unit and no quality (a stock-backed item offer, REQ-MARKET-014); a material row becomes a
   * {@code MATERIAL} entry with the material name, its own unit and its quality.
   *
   * @param item the Lager row (its material / game item and location are eager-loaded).
   * @param alreadyReleased whether an active offer already backs this row.
   * @return the picker entry.
   */
  private static MaterialExchangeReleasableItemDto toReleasableDto(
      InventoryItem item, boolean alreadyReleased) {
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
   * Builds the viewer-relative detail DTO, loading the interessenten names only when the viewer
   * owns the offer (anonymity gate, REQ-MARKET-006). Public so the write half ({@link
   * MaterialExchangeService}) can project a just-mutated offer through the identical redaction
   * without duplicating it (write→read, no cycle).
   *
   * @param offer the offer to project.
   * @param viewerId the requesting member, or {@code null} if unresolved.
   * @return the offer detail.
   */
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
      // Item offer: the display name + quantity live on the offer itself (REQ-MARKET-012). A
      // free-stated offer has no Lager row; a stock-backed one (REQ-MARKET-014) does, so its
      // quantity is clamped to the row's current stock on read (mirroring the material clamp,
      // ADR-0086) and the owner additionally sees the row's total stock to bound the edit dialog.
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
  private static Set<UUID> ownerIdsOf(Collection<MaterialExchangeOffer> offers) {
    return offers.stream()
        .map(MaterialExchangeBoardService::ownerIdOf)
        .filter(id -> id != null)
        .collect(Collectors.toSet());
  }

  /**
   * The owner id of an offer, or {@code null} for the defensive no-owner case.
   *
   * @param offer the offer.
   * @return the owner's user id, or {@code null}.
   */
  @Nullable
  private static UUID ownerIdOf(MaterialExchangeOffer offer) {
    return offer.getOwner() == null ? null : offer.getOwner().getId();
  }

  /**
   * Batch-resolves each given member's org-unit affiliation badges for the board — every {@link
   * #BADGE_KINDS} ({@code SQUADRON} / {@code SPECIAL_COMMAND} / {@code BEREICH}) membership the
   * member holds, ordered by {@link #ORG_UNIT_BADGE_ORDER} (Staffel(n), then Spezialkommando(s),
   * then Bereich(e), each name-sorted). There is deliberately no "primary" Staffel: a member in
   * several Staffeln and/or SKs and/or Bereiche surfaces <b>all</b> of their badges
   * (REQ-MARKET-001). Two queries total regardless of page size — one membership batch, one
   * org-unit batch — so the board stays free of the per-offer N+1 (REQ-DATA-003). A dangling
   * membership whose org unit no longer resolves is dropped.
   *
   * @param ownerIds the offering members whose affiliations to resolve; an empty set yields an
   *     empty map.
   * @return owner id → their ordered affiliation badges; members with no membership are absent.
   */
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
                        .filter(ou -> ou != null)
                        .sorted(ORG_UNIT_BADGE_ORDER)
                        .map(
                            ou ->
                                new OrgUnitReferenceDto(
                                    ou.getId(), ou.getName(), ou.getShorthand(), ou.getKind()))
                        .toList()));
    return byOwner;
  }

  /**
   * Sort rank of an org-unit kind for the affiliation-badge order: Staffel (0) before
   * Spezialkommando (1) before Bereich (2). The Organisationsleitung (3) is never queried into a
   * badge (it is absent from {@link #BADGE_KINDS}); it is ranked last only to keep the switch
   * exhaustive.
   *
   * @param kind the org-unit kind.
   * @return the badge sort rank (lower sorts first).
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
   * The effective item quantity served to the board for an {@link MaterialExchangeOfferKind#ITEM}
   * offer — the stored {@link MaterialExchangeOffer#getItemQuantity() itemQuantity}, clamped to the
   * backing row's current whole-unit stock for a <b>stock-backed</b> offer so the board never
   * advertises more than is in stock (the item sibling of {@link
   * #effectiveOfferedAmount(MaterialExchangeOffer)}, ADR-0086/0108, REQ-MARKET-014). A
   * <b>free-stated</b> offer (no backing row) returns its stated quantity unchanged. Never
   * negative.
   *
   * @param offer the item offer.
   * @return the clamped whole-unit quantity, or {@code null} if the offer states none.
   */
  @Nullable
  private static Integer effectiveItemQuantity(MaterialExchangeOffer offer) {
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
