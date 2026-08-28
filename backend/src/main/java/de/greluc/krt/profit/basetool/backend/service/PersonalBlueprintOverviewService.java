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

import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintOverviewOwnerDto;
import de.greluc.krt.profit.basetool.backend.model.projection.BlueprintOwnerProduct;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates, for the leadership oversight view (#364), which crafting blueprints are available
 * among the members of the caller's oversight org units, and which members own a given blueprint.
 *
 * <p>{@link PersonalBlueprint} carries no org-unit column — it is a pure per-user aggregate keyed
 * by the Keycloak {@code sub}. This service bridges to org units in two steps, entirely in Java (no
 * cross-type SQL join between the {@code String owner_sub} and the {@code UUID} membership key): it
 * resolves the in-scope member user ids from {@link OrgUnitMembershipRepository} using the
 * oversight {@link ScopePredicate} from {@link OwnerScopeService#currentOversightScope()}, then
 * loads and groups those members' owned-blueprint rows by <em>variant family</em> (via {@link
 * BlueprintVariantFamilyResolver}, so a base item and its cosmetic variants collapse onto one row
 * whose count spans the whole family). The lazy owner drill-down expands a family back to its
 * product keys through the cached {@link BlueprintVariantFamilyCatalog} to stay bounded.
 *
 * <p>Owner identity never leaves the service except as a display name in the drill-down — the list
 * view exposes only product + owner count, and the drill-down exposes only {@link
 * de.greluc.krt.profit.basetool.backend.model.User#getEffectiveName()} (never the {@code sub} or
 * e-mail), preserving the project's data-isolation rule.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalBlueprintOverviewService {

  /** Whitelisted sort fields for the availability list — only the product display name. */
  public static final Set<String> SORTABLE_FIELDS = Set.of("productName");

  /** Default sort field applied when the request supplies none. */
  public static final String DEFAULT_SORT_FIELD = "productName";

  private final OwnerScopeService ownerScopeService;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final PersonalBlueprintRepository personalBlueprintRepository;
  private final UserRepository userRepository;
  private final BlueprintVariantFamilyResolver familyResolver;
  private final BlueprintVariantFamilyCatalog familyCatalog;

  /**
   * Lists the blueprints available among the members of the caller's oversight org units, one row
   * per variant family with the count of distinct in-scope members that own the base item or any of
   * its cosmetic variants. Returns an empty page when the caller oversees no org unit (the {@link
   * OwnerScopeService#canAccessBlueprintOverview()} gate already keeps non-leadership callers out).
   *
   * <p>The optional {@code search} narrows the result to products whose display name contains the
   * fragment case-insensitively. It is applied to the aggregated entries <em>before</em> sorting
   * and pagination, so the returned page numbers always describe the filtered set — the
   * availability page paginates server-side (REQ-INV-013) and the filter must span every entry, not
   * just the visible page.
   *
   * @param pageable page request whose sort is restricted to {@link #SORTABLE_FIELDS}
   * @param search optional case-insensitive product-name fragment; {@code null} or blank matches
   *     everything
   * @return a page of {@link BlueprintOverviewEntryDto}, sorted by product name then product key
   */
  @NotNull
  public Page<BlueprintOverviewEntryDto> listAvailableBlueprints(
      @NotNull Pageable pageable, @Nullable String search) {
    Set<UUID> ownerSubs = inScopeOwnerSubs();
    if (ownerSubs.isEmpty()) {
      return new PageImpl<>(List.of(), pageable, 0);
    }
    // Group owned rows by variant family (not raw product key), so a base item and its cosmetic
    // variants collapse onto one availability row whose count spans the whole family; magazines
    // stay
    // atomic. The row's display label is the case-preserving base name derived from the first-seen
    // member's blueprint, so a family owned only via a variant still reads as its base.
    Map<String, ProductAggregate> byKey = new LinkedHashMap<>();
    for (BlueprintOwnerProduct bp :
        personalBlueprintRepository.findOwnerProductByOwnerSubIn(ownerSubs)) {
      String familyKey = familyResolver.familyKey(bp.productName());
      if (familyKey.isEmpty()) {
        continue;
      }
      byKey
          .computeIfAbsent(
              familyKey,
              key -> new ProductAggregate(familyResolver.displayBaseName(bp.productName())))
          .owners
          .add(bp.ownerSub());
    }
    String needle =
        search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
    List<BlueprintOverviewEntryDto> all =
        byKey.entrySet().stream()
            .filter(
                entry ->
                    needle == null
                        || entry.getValue().productName.toLowerCase(Locale.ROOT).contains(needle))
            .map(
                entry ->
                    new BlueprintOverviewEntryDto(
                        entry.getKey(),
                        entry.getValue().productName,
                        entry.getValue().owners.size()))
            .sorted(entryComparator(pageable))
            .toList();
    return paginate(all, pageable);
  }

  /**
   * Lists the in-scope members that own the given variant family, by display name. Re-resolves the
   * oversight scope server-side so a client cannot widen it through the query parameter. Returns an
   * empty list when the caller oversees no org unit, the family is unknown to the active master, or
   * nobody in scope owns any product in the family.
   *
   * <p>This is the hot path behind every expand click on the availability page, so it stays bounded
   * (REQ-INV-012): the family key is expanded to its concrete product keys once via the cached
   * {@link BlueprintVariantFamilyCatalog} (a base plus its cosmetic variants — usually a handful),
   * and the admin "all org units" scope then fetches owners by that product-key set alone ({@link
   * PersonalBlueprintRepository#findAllByProductKeyIn}), never enumerating all owners. Scoped
   * callers keep the owner-restricted lookup so the isolation contract is untouched.
   *
   * @param familyKey the variant family key (the availability row's {@code productKey}) whose
   *     owners to resolve
   * @return the owning in-scope members' display names, sorted case-insensitively; never {@code
   *     null}
   */
  @NotNull
  public List<BlueprintOverviewOwnerDto> listOwnersForProduct(String familyKey) {
    Set<String> productKeys = familyCatalog.familyIndex().getOrDefault(familyKey, Set.of());
    if (productKeys.isEmpty()) {
      return List.of();
    }
    ScopePredicate scope = ownerScopeService.currentOversightScope();
    boolean adminAll = scope.adminAllScope();
    List<PersonalBlueprint> owned;
    Set<UUID> memberSubs;
    if (adminAll) {
      // Admin "all org units" has no single unit to be a member of, so no owner is flagged
      // external (every owner is in scope by definition).
      memberSubs = Set.of();
      owned = personalBlueprintRepository.findAllByProductKeyIn(productKeys);
    } else {
      // Union the global sharers (REQ-INV-018) into the oversight member set so an opted-in owner
      // shows in the drill-down, keeping the owner names consistent with the bumped count. The
      // oversight members are kept separate so each owner can be flagged member vs global sharer.
      memberSubs = oversightMemberSubs(scope);
      Set<UUID> ownerSubs = new LinkedHashSet<>(memberSubs);
      ownerSubs.addAll(globalSharerSubs());
      if (ownerSubs.isEmpty()) {
        return List.of();
      }
      owned =
          personalBlueprintRepository.findAllByProductKeyInAndOwnerSubIn(productKeys, ownerSubs);
    }
    Set<UUID> ownerIds =
        owned.stream()
            .map(PersonalBlueprint::getOwnerSub)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (ownerIds.isEmpty()) {
      return List.of();
    }
    return userRepository.findAllById(ownerIds).stream()
        .map(
            user ->
                new BlueprintOverviewOwnerDto(
                    user.getEffectiveName(), adminAll || memberSubs.contains(user.getId())))
        .sorted(
            Comparator.comparing(
                BlueprintOverviewOwnerDto::ownerName, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * Resolves the {@code app_user.id}s of every user in the caller's oversight scope. For the admin
   * "all org units" scope this is every blueprint owner in the system (via {@link
   * PersonalBlueprintRepository#findAllDistinctOwnerSubs()}) — including owners with no org-unit
   * membership (e.g. a squadron-less admin), which the previous member-list resolution dropped so
   * the admin's own blueprints went missing (#371 fix). For a pinned or member-union scope it is
   * the in-scope org units' member ids (the {@code owner_sub} stored on {@link PersonalBlueprint}
   * is a foreign key to {@code app_user(id)} since V235).
   *
   * @return the in-scope owner ids; empty when a non-admin caller oversees no org unit
   */
  @NotNull
  private Set<UUID> inScopeOwnerSubs() {
    ScopePredicate scope = ownerScopeService.currentOversightScope();
    if (scope.adminAllScope()) {
      // Admin all-scope already spans every owner, so the global-share opt-in adds nothing here.
      return personalBlueprintRepository.findAllDistinctOwnerSubs();
    }
    // Union the global sharers (REQ-INV-018) into the oversight member set so an opted-in user is
    // counted even when no oversight org unit contains them — including the sharer-only case where
    // the caller's oversight membership set is otherwise empty.
    Set<UUID> subs = new LinkedHashSet<>(oversightMemberSubs(scope));
    subs.addAll(globalSharerSubs());
    return subs;
  }

  /**
   * Resolves the {@code owner_sub}s of every user who opted into global blueprint sharing
   * (REQ-INV-018). These are unioned into the oversight member set so an opted-in user's blueprints
   * surface in the availability overview for every leadership viewer, regardless of org-unit
   * membership. The id stored on {@link PersonalBlueprint#getOwnerSub()} is {@code app_user.id}.
   *
   * @return the global sharers' owner ids; never {@code null}, possibly empty
   */
  @NotNull
  private Set<UUID> globalSharerSubs() {
    return new LinkedHashSet<>(userRepository.findIdsBySharingBlueprintsGlobally());
  }

  /**
   * Resolves the member ids of a non-admin oversight scope: the pinned org unit's members when a
   * valid pin is active, otherwise the union over all oversight org units (the {@code owner_sub}
   * stored on {@link PersonalBlueprint} is a foreign key to {@code app_user(id)} since V235).
   *
   * @param scope the caller's non-admin oversight scope
   * @return the in-scope member ids; empty when the caller oversees no org unit
   */
  @NotNull
  private Set<UUID> oversightMemberSubs(@NotNull ScopePredicate scope) {
    Set<UUID> userIds;
    if (scope.activeOrgUnitId() != null) {
      userIds =
          orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(
              Set.of(scope.activeOrgUnitId()));
    } else if (!scope.memberOrgUnitIds().isEmpty()) {
      userIds =
          orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(scope.memberOrgUnitIds());
    } else {
      return Set.of();
    }
    return new LinkedHashSet<>(userIds);
  }

  /**
   * Builds the in-memory comparator for the availability list: product name (honouring the request
   * direction, case-insensitive), then product key as a stable tiebreaker.
   *
   * @param pageable the page request carrying the (whitelisted) sort
   * @return the comparator to order the aggregated entries by
   */
  @NotNull
  private static Comparator<BlueprintOverviewEntryDto> entryComparator(@NotNull Pageable pageable) {
    Sort.Order order = pageable.getSort().getOrderFor("productName");
    Comparator<BlueprintOverviewEntryDto> byName =
        Comparator.comparing(BlueprintOverviewEntryDto::productName, String.CASE_INSENSITIVE_ORDER);
    if (order != null && order.isDescending()) {
      byName = byName.reversed();
    }
    return byName.thenComparing(BlueprintOverviewEntryDto::productKey);
  }

  /**
   * Cuts the requested page out of the fully-sorted entry list and wraps it as a {@link Page} so
   * the controller can echo the standard {@code PageResponse} envelope.
   *
   * @param all the complete, sorted entry list
   * @param pageable the page request
   * @return the requested slice as a {@link Page}
   */
  @NotNull
  private static Page<BlueprintOverviewEntryDto> paginate(
      @NotNull List<BlueprintOverviewEntryDto> all, @NotNull Pageable pageable) {
    int total = all.size();
    int from = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), total);
    int to = (int) Math.min((long) from + pageable.getPageSize(), total);
    return new PageImpl<>(List.copyOf(all.subList(from, to)), pageable, total);
  }

  /**
   * Mutable per-family accumulator used while grouping owned-blueprint rows: holds the family's
   * display label and the set of distinct owner ids seen across the family.
   */
  private static final class ProductAggregate {
    private final String productName;
    private final Set<UUID> owners = new LinkedHashSet<>();

    /**
     * Starts an accumulator for one variant family.
     *
     * @param productName the family's display label (case-preserving base name)
     */
    ProductAggregate(String productName) {
      this.productName = productName;
    }
  }
}
