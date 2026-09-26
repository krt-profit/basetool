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

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.RequestMemo;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes which org units a set of memberships reaches through the leadership cascade
 * (REQ-ORG-015), shared by {@link OwnerScopeService} and {@link
 * de.greluc.krt.profit.basetool.backend.service.CustomJwtGrantedAuthoritiesConverter}.
 *
 * <ul>
 *   <li>A Bereichsleitung membership ({@link MembershipRole#isAreaRank()}) reaches the Bereich and
 *       its direct children.
 *   <li>An Organisationsleitung membership ({@link MembershipRole#OL_MEMBER}) reaches every org
 *       unit.
 *   <li>Every other membership reaches only its own org unit.
 * </ul>
 *
 * <p>The reach is always a concrete set of ids, never an admin-all marker, so the cascade never
 * grants admin rights. Results are memoised per request in a {@link RequestMemo}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgUnitCascadeService {

  /**
   * Request memo for {@link #cascadedOfficerReach(Collection)}, keyed by the {@linkplain
   * #membershipKey(Collection) membership-id set} so one principal's reach is never served to
   * another.
   */
  private static final RequestMemo.Key<Map<Set<OrgUnitMembershipId>, Set<UUID>>>
      CACHE_KEY_CASCADED_REACH =
          RequestMemo.Key.of(OrgUnitCascadeService.class, "cascadedOfficerReach");

  private final OrgUnitRepository orgUnitRepository;

  /**
   * Resolves every org-unit id the holder of {@code memberships} reaches: the direct memberships
   * plus the {@linkplain #cascadedOfficerReach(Collection) cascaded leadership reach}. Feeds {@link
   * ScopePredicate#memberOrgUnitIds()}.
   *
   * @param memberships the caller's membership rows; never {@code null}, may be empty
   * @return the union of direct and cascaded org-unit ids; never {@code null}, insertion-ordered
   */
  @NotNull
  public Set<UUID> expandWithDescendants(@NotNull Collection<OrgUnitMembership> memberships) {
    Set<UUID> reach = new LinkedHashSet<>();
    for (OrgUnitMembership m : memberships) {
      reach.add(m.getId().getOrgUnitId());
    }
    reach.addAll(cascadedOfficerReach(memberships));
    return reach;
  }

  /**
   * Resolves the org units the caller reaches through a Bereich or OL leadership membership, for
   * which the JWT converter mints contextual {@code LOGISTICIAN@<id>} / {@code
   * MISSION_MANAGER@<id>} authorities.
   *
   * <p>An OL membership yields every org-unit id; a Bereich leadership membership yields the
   * Bereich and its direct children; no leadership yields the empty set. Memoised per request.
   *
   * @param memberships the caller's membership rows; never {@code null}.
   * @return the cascaded officer-equivalent reach; never {@code null}, possibly empty,
   *     insertion-ordered for determinism.
   */
  @NotNull
  public Set<UUID> cascadedOfficerReach(@NotNull Collection<OrgUnitMembership> memberships) {
    if (memberships.isEmpty()) {
      return new LinkedHashSet<>();
    }
    Map<Set<OrgUnitMembershipId>, Set<UUID>> memo =
        RequestMemo.getIfBound(CACHE_KEY_CASCADED_REACH, HashMap::new);
    if (memo == null) {
      return computeCascadedOfficerReach(memberships);
    }
    Set<UUID> reach =
        memo.computeIfAbsent(
            membershipKey(memberships), key -> computeCascadedOfficerReach(memberships));
    return new LinkedHashSet<>(reach);
  }

  /**
   * Computes the cascaded reach backing {@link #cascadedOfficerReach(Collection)} without caching.
   *
   * @param memberships the caller's membership rows; never {@code null} or empty
   * @return a freshly-allocated reach set; never {@code null}, insertion-ordered.
   */
  @NotNull
  private Set<UUID> computeCascadedOfficerReach(
      @NotNull Collection<OrgUnitMembership> memberships) {
    boolean olReach = memberships.stream().anyMatch(m -> m.getRole() == MembershipRole.OL_MEMBER);
    if (olReach) {
      return new LinkedHashSet<>(orgUnitRepository.findAllOrgUnitIds());
    }
    Set<UUID> reach = new LinkedHashSet<>();
    for (OrgUnitMembership m : memberships) {
      if (m.getRole().isAreaRank()) {
        UUID bereichId = m.getId().getOrgUnitId();
        reach.add(bereichId);
        reach.addAll(orgUnitRepository.findChildOrgUnitIds(bereichId));
      }
    }
    return reach;
  }

  /**
   * Builds the memoisation key for {@code memberships}: the set of their composite ids.
   *
   * <p>The key omits the role flags, which is sound only while a request never changes the caller's
   * own membership flags and then re-resolves scope.
   *
   * @param memberships the caller's membership rows; never {@code null}.
   * @return the set of membership ids identifying this input; never {@code null}.
   */
  @NotNull
  private static Set<OrgUnitMembershipId> membershipKey(
      @NotNull Collection<OrgUnitMembership> memberships) {
    Set<OrgUnitMembershipId> key = new LinkedHashSet<>();
    for (OrgUnitMembership m : memberships) {
      key.add(m.getId());
    }
    return key;
  }
}
