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
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeCountsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeRequestInterestCount;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeRequestInterestRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeRequestRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/projection half of the Materialbörse Gesuche (wanted-listings) board — the request-side
 * sibling of {@link MaterialExchangeBoardService} (REQ-MARKET-015…, ADR-0116). It owns every
 * caller-visible read (the paged board, the tab counts, the single-request detail) plus the
 * supplier-anonymity redaction and the request→DTO mapping.
 *
 * <p><b>Board scope:</b> the board is org-wide — every {@code ACTIVE} request is visible to every
 * member regardless of the request's owning org unit; there is no OrgUnit scope filter. Only real
 * members reach this service (the controller gates reads on {@code KRT_MEMBER}).
 *
 * <p><b>Anonymity (REQ-MARKET-019):</b> the supplier names ("Ich kann liefern") are disclosed only
 * to the request's owner; every other viewer sees only the count. The redaction lives here, in
 * {@link #detailDto(MaterialExchangeRequest, UUID)} — a name list is loaded only when the viewer is
 * the owner.
 *
 * <p><b>Write→read seam:</b> {@link #detailDto(MaterialExchangeRequest, UUID)} and {@link
 * #detail(UUID)} are public because the write half ({@link MaterialRequestService}) injects this
 * service to project its own mutation results through the very same redaction — a one-way
 * write→read dependency, so no cycle. Called from inside a write transaction the projection joins
 * that transaction (propagation {@code REQUIRED}), so the class-level {@code readOnly} flag only
 * takes effect for the standalone reads that start their own transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialRequestBoardService {

  /**
   * Org-unit kinds surfaced as a requester's affiliation badges (REQ-MARKET-015, mirroring
   * REQ-MARKET-001): the member's Staffeln, Spezialkommandos and Bereiche. The Organisationsleitung
   * is deliberately excluded — a leadership-only affiliation carries no board badge.
   */
  private static final List<OrgUnitKind> BADGE_KINDS =
      List.of(OrgUnitKind.SQUADRON, OrgUnitKind.SPECIAL_COMMAND, OrgUnitKind.BEREICH);

  /**
   * Display order of a requester's affiliation badges: Staffel(n) first, then Spezialkommando(s),
   * then Bereich(e) (see {@link #badgeRank(OrgUnitKind)}), each group name-sorted
   * case-insensitively — so the same member's badges read in a stable order across the board.
   */
  private static final Comparator<OrgUnit> ORG_UNIT_BADGE_ORDER =
      Comparator.<OrgUnit>comparingInt(ou -> badgeRank(ou.getKind()))
          .thenComparing(
              ou -> ou.getName() == null ? "" : ou.getName(), String.CASE_INSENSITIVE_ORDER);

  private final MaterialExchangeRequestRepository requestRepository;
  private final MaterialExchangeRequestInterestRepository interestRepository;
  private final AuthHelperService authHelperService;
  private final UserMapper userMapper;

  /**
   * Reads each requesting member's badge-kind memberships ({@link #BADGE_KINDS}: {@code SQUADRON} /
   * {@code SPECIAL_COMMAND} / {@code BEREICH}) so the board can render <b>all</b> of the
   * requester's affiliation badges after the username — batch-loaded to stay N+1-free across a
   * board page.
   */
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;

  /**
   * Resolves the membership rows' org-unit ids to their concrete {@link OrgUnit} entities (id,
   * name, shorthand, kind) for the requester affiliation badges — one batch {@code findAllById} per
   * board page rather than a per-request lookup.
   */
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Returns a page of the board — the "Alle Gesuche" tab, or the caller's own requests for the
   * "Meine Gesuche" tab — applying the toolbar filters and sort. Supplier counts and the viewer's
   * own signals are batch-loaded so the list has no N+1; supplier names are never included in a
   * list DTO.
   *
   * @param tab {@code "mein"} for the caller's own requests, anything else (incl. {@code null}) for
   *     all requests.
   * @param query a free-text fragment matched against the material/item name and the owner's
   *     handle, or {@code null}/blank for no text filter.
   * @param minQuality the inclusive minimum quality floor (0/{@code null} disables the filter).
   * @param minAmount the inclusive minimum desired quantity, or {@code null} for no amount filter.
   * @param sort the sort key — {@code qual} (default) / {@code menge} / {@code mat} / {@code neu}.
   * @param page the zero-based page index.
   * @param size the page size (clamped to {@value MaterialExchangeQueryParams#MAX_PAGE_SIZE}).
   * @return the matching page of board requests.
   */
  public PageResponse<MaterialRequestDto> board(
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
    Page<MaterialExchangeRequest> requests =
        requestRepository.findBoard(
            viewerId,
            onlyMine,
            MaterialExchangeQueryParams.normalizeQuery(query),
            MaterialExchangeQueryParams.clampQuality(minQuality),
            minAmount,
            MaterialExchangeQueryParams.normalizeSort(sort),
            pageable);

    List<UUID> requestIds =
        requests.getContent().stream().map(MaterialExchangeRequest::getId).toList();
    Map<UUID, Long> counts = interestCounts(requestIds);
    Set<UUID> interested =
        viewerId == null || requestIds.isEmpty()
            ? Set.of()
            : interestRepository.findRequestIdsInterestedByViewer(viewerId, requestIds);
    Map<UUID, List<OrgUnitReferenceDto>> badges =
        ownerOrgUnitBadges(ownerIdsOf(requests.getContent()));

    Page<MaterialRequestDto> dtos =
        requests.map(
            request ->
                toDto(
                    request,
                    viewerId,
                    badges.getOrDefault(ownerIdOf(request), List.of()),
                    counts.getOrDefault(request.getId(), 0L).intValue(),
                    interested.contains(request.getId()),
                    null));
    return PageResponse.of(dtos);
  }

  /**
   * Returns the board tab counts (all active requests, and the caller's own active requests). These
   * are board totals — deliberately unaffected by the search/quality/amount filters.
   *
   * @return the tab counts.
   */
  public MaterialExchangeCountsDto counts() {
    UUID viewerId = authHelperService.currentUserId().orElse(null);
    long all = requestRepository.countByStatus(MaterialExchangeRequestStatus.ACTIVE);
    long mine =
        viewerId == null
            ? 0
            : requestRepository.countByStatusAndOwnerId(
                MaterialExchangeRequestStatus.ACTIVE, viewerId);
    return new MaterialExchangeCountsDto(all, mine);
  }

  /**
   * Loads a single request for the detail pane, including the supplier names when the caller is the
   * owner.
   *
   * @param requestId the request to load.
   * @return the viewer-relative request detail.
   * @throws NotFoundException if no request with that id exists.
   */
  public MaterialRequestDto detail(UUID requestId) {
    UUID viewerId = authHelperService.currentUserId().orElse(null);
    MaterialExchangeRequest request = loadWithDetail(requestId);
    return detailDto(request, viewerId);
  }

  /**
   * Builds the viewer-relative detail DTO, loading the supplier names only when the viewer owns the
   * request (anonymity gate, REQ-MARKET-019). Public so the write half ({@link
   * MaterialRequestService}) can project a just-mutated request through the identical redaction
   * without duplicating it (write→read, no cycle).
   *
   * @param request the request to project.
   * @param viewerId the requesting member, or {@code null} if unresolved.
   * @return the request detail.
   */
  public MaterialRequestDto detailDto(MaterialExchangeRequest request, @Nullable UUID viewerId) {
    boolean mine = isMine(request, viewerId);
    int count = (int) interestRepository.countByRequestId(request.getId());
    boolean viewerInterested =
        viewerId != null
            && interestRepository.existsByRequestIdAndInterestedUserId(request.getId(), viewerId);
    List<String> names =
        mine
            ? interestRepository.findByRequestIdOrderByCreatedAtDesc(request.getId()).stream()
                .map(interest -> interest.getInterestedUser().getEffectiveName())
                .toList()
            : null;
    UUID ownerId = ownerIdOf(request);
    List<OrgUnitReferenceDto> badges =
        ownerId == null
            ? List.of()
            : ownerOrgUnitBadges(Set.of(ownerId)).getOrDefault(ownerId, List.of());
    return toDto(request, viewerId, badges, count, viewerInterested, names);
  }

  /**
   * Assembles the request DTO from the entity and the viewer-relative facts.
   *
   * @param request the request.
   * @param viewerId the requesting member, or {@code null}.
   * @param ownerOrgUnits the requester's affiliation badges (Staffel(n) then SK(s)), never {@code
   *     null}.
   * @param interestCount the supplier count.
   * @param viewerInterested whether the viewer has signalled they can supply it.
   * @param interestedHandles the supplier handles (owner-only), or {@code null}.
   * @return the assembled DTO.
   */
  private MaterialRequestDto toDto(
      MaterialExchangeRequest request,
      @Nullable UUID viewerId,
      List<OrgUnitReferenceDto> ownerOrgUnits,
      int interestCount,
      boolean viewerInterested,
      @Nullable List<String> interestedHandles) {
    boolean mine = isMine(request, viewerId);

    MaterialReferenceDto material = null;
    String itemName = null;
    Integer itemQuantity = null;
    Double requestedAmount = null;
    if (request.getKind() == MaterialExchangeRequestKind.ITEM) {
      itemName = request.getItemName();
      itemQuantity = request.getItemQuantity();
    } else {
      Material mat = request.getRequestedMaterial();
      material = new MaterialReferenceDto(mat.getId(), mat.getName(), mat.getQuantityType());
      requestedAmount = request.getRequestedAmount();
    }
    return new MaterialRequestDto(
        request.getId(),
        request.getKind(),
        material,
        itemName,
        itemQuantity,
        requestedAmount,
        request.getMinQuality(),
        userMapper.toReferenceDto(request.getOwner()),
        ownerOrgUnits,
        mine,
        request.getPostedAt(),
        request.getRemark(),
        interestCount,
        interestedHandles,
        viewerInterested,
        request.getStatus(),
        request.getVersion());
  }

  /**
   * The distinct, non-null owner ids across a page of requests — the input set for the batched
   * requester-affiliation badge resolution.
   *
   * @param requests the board page's requests.
   * @return the distinct owner ids (a defensively null-owner request contributes nothing).
   */
  private static Set<UUID> ownerIdsOf(Collection<MaterialExchangeRequest> requests) {
    return requests.stream()
        .map(MaterialRequestBoardService::ownerIdOf)
        .filter(id -> id != null)
        .collect(Collectors.toSet());
  }

  /**
   * The owner id of a request, or {@code null} for the defensive no-owner case.
   *
   * @param request the request.
   * @return the owner's user id, or {@code null}.
   */
  @Nullable
  private static UUID ownerIdOf(MaterialExchangeRequest request) {
    return request.getOwner() == null ? null : request.getOwner().getId();
  }

  /**
   * Batch-resolves each given member's org-unit affiliation badges for the board — every {@link
   * #BADGE_KINDS} ({@code SQUADRON} / {@code SPECIAL_COMMAND} / {@code BEREICH}) membership the
   * member holds, ordered by {@link #ORG_UNIT_BADGE_ORDER} (Staffel(n), then Spezialkommando(s),
   * then Bereich(e), each name-sorted). Two queries total regardless of page size — one membership
   * batch, one org-unit batch — so the board stays free of the per-request N+1 (REQ-DATA-003). A
   * dangling membership whose org unit no longer resolves is dropped.
   *
   * @param ownerIds the requesting members whose affiliations to resolve; an empty set yields an
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
   * Loads a request with its board associations eager-fetched (material / owner / org unit).
   *
   * @param requestId the request id.
   * @return the loaded request.
   * @throws NotFoundException if no such request exists.
   */
  private MaterialExchangeRequest loadWithDetail(UUID requestId) {
    return requestRepository
        .findWithDetailById(requestId)
        .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
  }

  /**
   * Batch-loads the supplier counts for the given requests into a lookup map (requests with no
   * signals are simply absent).
   *
   * @param requestIds the requests to count.
   * @return request id → count.
   */
  private Map<UUID, Long> interestCounts(List<UUID> requestIds) {
    if (requestIds.isEmpty()) {
      return Map.of();
    }
    return interestRepository.countByRequestIdIn(requestIds).stream()
        .collect(
            Collectors.toMap(
                MaterialExchangeRequestInterestCount::requestId,
                MaterialExchangeRequestInterestCount::count));
  }

  /**
   * Whether the given member owns the request.
   *
   * @param request the request.
   * @param viewerId the member, or {@code null}.
   * @return {@code true} if the member is the request's owner.
   */
  private boolean isMine(MaterialExchangeRequest request, @Nullable UUID viewerId) {
    return viewerId != null
        && request.getOwner() != null
        && viewerId.equals(request.getOwner().getId());
  }
}
