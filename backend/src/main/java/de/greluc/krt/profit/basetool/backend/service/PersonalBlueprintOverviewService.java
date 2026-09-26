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
 * Aggregates, for the leadership oversight view, which crafting blueprints the members of the
 * caller's oversight org units own, and who owns a given blueprint.
 *
 * <p>Resolves the in-scope member ids from the oversight {@link ScopePredicate} and groups their
 * {@link PersonalBlueprint} rows by variant family ({@link BlueprintVariantFamilyResolver}).
 * Exposes owners only as display names, never their {@code sub} or e-mail.
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
   * per variant family with its count of distinct owning members. Empty when the caller oversees no
   * org unit.
   *
   * <p>{@code search} is applied before sorting and pagination (REQ-INV-013).
   *
   * @param pageable page request whose sort is restricted to {@link #SORTABLE_FIELDS}
   * @param search optional case-insensitive product-name fragment; {@code null} or blank matches
   *     everything
   * @return a page of {@link BlueprintOverviewEntryDto}, sorted by product name then product key
   */
  @NotNull
  public Page<BlueprintOverviewEntryDto> listAvailableBlueprints(
      @NotNull Pageable pageable, @Nullable String search) {
    Set<UUID> ownerUserIds = inScopeOwnerUserIds();
    if (ownerUserIds.isEmpty()) {
      return new PageImpl<>(List.of(), pageable, 0);
    }
    Map<String, ProductAggregate> byKey = new LinkedHashMap<>();
    for (BlueprintOwnerProduct bp :
        personalBlueprintRepository.findOwnerProductByOwnerUserIdIn(ownerUserIds)) {
      String familyKey = familyResolver.familyKey(bp.productName());
      if (familyKey.isEmpty()) {
        continue;
      }
      byKey
          .computeIfAbsent(
              familyKey,
              key -> new ProductAggregate(familyResolver.displayBaseName(bp.productName())))
          .owners
          .add(bp.ownerUserId());
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
   * Lists the display names of the in-scope members owning any product of the given variant family,
   * re-resolving the oversight scope server-side (REQ-INV-012).
   *
   * @param familyKey the variant family key (the availability row's {@code productKey})
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
      memberSubs = Set.of();
      owned = personalBlueprintRepository.findAllByProductKeyIn(productKeys);
    } else {
      memberSubs = oversightMemberSubs(scope);
      Set<UUID> ownerUserIds = new LinkedHashSet<>(memberSubs);
      ownerUserIds.addAll(globalSharerSubs());
      if (ownerUserIds.isEmpty()) {
        return List.of();
      }
      owned =
          personalBlueprintRepository.findAllByProductKeyInAndOwnerUserIdIn(
              productKeys, ownerUserIds);
    }
    Set<UUID> ownerIds =
        owned.stream()
            .map(PersonalBlueprint::getOwnerUserId)
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
   * Resolves the {@code app_user.id}s in the caller's oversight scope: every blueprint owner for
   * the admin "all org units" scope, otherwise the members of the in-scope org units.
   *
   * @return the in-scope owner ids; empty when a non-admin caller oversees no org unit
   */
  @NotNull
  private Set<UUID> inScopeOwnerUserIds() {
    ScopePredicate scope = ownerScopeService.currentOversightScope();
    if (scope.adminAllScope()) {
      return personalBlueprintRepository.findAllDistinctOwnerUserIds();
    }
    Set<UUID> subs = new LinkedHashSet<>(oversightMemberSubs(scope));
    subs.addAll(globalSharerSubs());
    return subs;
  }

  /**
   * Resolves the {@code app_user.id}s of every user who opted into global blueprint sharing
   * (REQ-INV-018); they are visible to every leadership viewer.
   *
   * @return the global sharers' owner ids; never {@code null}, possibly empty
   */
  @NotNull
  private Set<UUID> globalSharerSubs() {
    return new LinkedHashSet<>(userRepository.findIdsBySharingBlueprintsGlobally());
  }

  /**
   * Resolves the member ids of a non-admin oversight scope: the pinned org unit's members when a
   * valid pin is active, otherwise the union over all oversight org units.
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
