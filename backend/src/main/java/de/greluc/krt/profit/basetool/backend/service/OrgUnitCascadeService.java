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
 * Computes the cascading-scope expansion of the org hierarchy (epic #692, REQ-ORG-015) in one
 * place, so the two consumers that need it — the request-scope resolver ({@link OwnerScopeService})
 * and the JWT authority converter ({@link
 * de.greluc.krt.profit.basetool.backend.service.CustomJwtGrantedAuthoritiesConverter}) — share a
 * single, independently-tested definition of "who reaches which org units".
 *
 * <p><b>The cascade rule.</b> A leadership membership confers officer-equivalent reach over the
 * units <em>below</em> it, mirroring the {@code ADMIN > OFFICER > LOGISTICIAN/MISSION_MANAGER}
 * chain one level higher in the tree:
 *
 * <ul>
 *   <li>A <b>Bereichsleitung</b> membership (any area rank — {@link MembershipRole#isAreaRank()}:
 *       {@code BEREICHSLEITER} / {@code BEREICHSKOORDINATOR} / {@code BEREICHSOPERATOR}) reaches
 *       the Bereich itself plus every direct child (its Staffeln + SKs). The three-level hierarchy
 *       (OL &gt; Bereich &gt; Staffel/SK) means the direct children are the whole subtree below a
 *       Bereich.
 *   <li>An <b>Organisationsleitung</b> membership ({@link MembershipRole#OL_MEMBER}) reaches
 *       <em>every</em> org unit — OL is the only level that crosses Bereiche.
 *   <li>Every other membership (a plain Staffel/SK membership, a per-membership Logistician /
 *       MissionManager flag, an {@code SK_LEAD}, a squadron rank, or a rank-less {@code MEMBER}
 *       seat) confers reach over its own org unit only — exactly today's behaviour. A squadron rank
 *       and an SK-Lead are own-unit only by design (REQ-ROLE-002, REQ-ORG-017, owner decision Q1:
 *       SK-Leiter = SK-only reach); their own unit's reach is contributed by the per-row authority
 *       loop / direct-membership union, not by this cascade.
 * </ul>
 *
 * <p><b>HARD INVARIANT (REQ-ORG-015).</b> The expansion is always a concrete, materialised set of
 * org-unit ids — even for an OL member, whose reach is the literal union of every org-unit id, not
 * an admin-all marker. OL/Bereich leadership therefore never inherits the admin carve-outs
 * (SK-lifecycle / promotion / ownerless-row access / admin-pin semantics keyed on {@code
 * adminAllScope} / {@code isAdmin()}). The cascade widens membership scope; it never grants admin
 * rights. The methods here are pure functions of their {@code memberships} argument plus the
 * persisted hierarchy, with no dependency on the security context, so they behave identically
 * whether invoked at authentication time (converter) or request-handling time (scope resolver).
 *
 * <p><b>Per-request memoisation.</b> Within a single HTTP request both consumers run for the same
 * authenticated principal — the converter computes the reach at authentication time and {@link
 * OwnerScopeService} re-derives it at query time — so {@link #cascadedOfficerReach(Collection)}
 * caches its result in a {@link RequestMemo} on the bound request, keyed by the membership-id set
 * it was computed from. This collapses the two otherwise-identical hierarchy reads ({@code
 * findAllOrgUnitIds()} for an OL member, {@code findChildOrgUnitIds(...)} per Bereich seat) into
 * one per request. The cache is transparent: it never changes the result (same inputs ⇒ same set),
 * it hands out defensive copies so a caller cannot corrupt it, and when no request is bound (unit
 * tests, scheduled jobs) the value is computed directly. The pure-function contract above therefore
 * still holds.
 *
 * <p>Class-level {@code @Transactional(readOnly = true)} matches the other scope-resolution beans:
 * every repository call here is a read.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgUnitCascadeService {

  /**
   * Request memo under which {@link #cascadedOfficerReach(Collection)} keeps its results for the
   * duration of one request (see the class-level <i>Per-request memoisation</i> note), keyed by the
   * {@linkplain #membershipKey(Collection) membership-id set} each reach was computed from — so the
   * cache can never serve one principal's reach to another.
   */
  private static final RequestMemo.Key<Map<Set<OrgUnitMembershipId>, Set<UUID>>>
      CACHE_KEY_CASCADED_REACH =
          RequestMemo.Key.of(OrgUnitCascadeService.class, "cascadedOfficerReach");

  private final OrgUnitRepository orgUnitRepository;

  /**
   * Resolves the full set of org-unit ids the holder of {@code memberships} has effective reach
   * over: every direct membership id, unioned with the {@linkplain
   * #cascadedOfficerReach(Collection) cascaded leadership reach}. This is the set the request-scope
   * resolver feeds into {@link ScopePredicate#memberOrgUnitIds()} so that list queries, per-row
   * {@code canSee*}/{@code canEdit*} gates and the Job-Order profit gate all cascade uniformly.
   *
   * <p>For a caller with no leadership flag this returns exactly the direct membership ids —
   * byte-for-byte today's behaviour — because {@link #cascadedOfficerReach(Collection)} is empty.
   *
   * @param memberships the caller's membership rows (Staffel / SK / Bereich / OL); never {@code
   *     null}, may be empty (anonymous / memberless caller → empty result).
   * @return the union of direct-membership ids and cascaded descendant ids; never {@code null},
   *     insertion-ordered for determinism.
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
   * Resolves only the org units the caller reaches <em>through a Bereich/OL leadership flag</em> —
   * excluding plain Staffel/SK memberships, which confer reach over their own org unit but no
   * cascade. This is the set the JWT converter mints contextual {@code LOGISTICIAN@<id>} / {@code
   * MISSION_MANAGER@<id>} authorities for, so a Bereichsleitung / OL member acts with
   * officer-equivalent authority in every subordinate unit (and in the Bereich itself, for the
   * Bereich-owned aggregates introduced in a later phase).
   *
   * <ul>
   *   <li>An OL membership short-circuits to <em>every</em> org-unit id (OL crosses Bereiche).
   *   <li>Otherwise each Bereich-leadership membership contributes the Bereich id plus its direct
   *       children (Staffeln + SKs).
   *   <li>A caller with no leadership flag yields the empty set.
   * </ul>
   *
   * <p>Memoised per request (see the class-level <i>Per-request memoisation</i> note): the first
   * call materialises the reach and the rest reuse it, so the hierarchy is read once per request
   * regardless of how many times the converter and {@link OwnerScopeService} consult it.
   *
   * @param memberships the caller's membership rows; never {@code null}.
   * @return the cascaded officer-equivalent reach; never {@code null}, possibly empty,
   *     insertion-ordered for determinism.
   */
  @NotNull
  public Set<UUID> cascadedOfficerReach(@NotNull Collection<OrgUnitMembership> memberships) {
    if (memberships.isEmpty()) {
      // No memberships → empty reach with no DB read; nothing worth caching.
      return new LinkedHashSet<>();
    }
    Map<Set<OrgUnitMembershipId>, Set<UUID>> memo =
        RequestMemo.getIfBound(CACHE_KEY_CASCADED_REACH, HashMap::new);
    if (memo == null) {
      // No HTTP request bound (unit tests, scheduled jobs): compute directly — identical result.
      return computeCascadedOfficerReach(memberships);
    }
    // Same principal within the same request → reuse the materialised reach, skipping the
    // findAllOrgUnitIds() / findChildOrgUnitIds() round-trips the converter already paid. A
    // defensive copy is returned so a caller can never corrupt the cached set.
    Set<UUID> reach =
        memo.computeIfAbsent(
            membershipKey(memberships), key -> computeCascadedOfficerReach(memberships));
    return new LinkedHashSet<>(reach);
  }

  /**
   * The uncached cascade computation backing {@link #cascadedOfficerReach(Collection)}: an OL
   * membership short-circuits to the materialised union of every org-unit id (never an admin-all
   * marker, REQ-ORG-015), otherwise each Bereich-leadership membership contributes its Bereich id
   * plus its direct children.
   *
   * @param memberships the caller's membership rows; never {@code null} and never empty (the public
   *     method handles the empty case before delegating here).
   * @return a freshly-allocated reach set; never {@code null}, insertion-ordered.
   */
  @NotNull
  private Set<UUID> computeCascadedOfficerReach(
      @NotNull Collection<OrgUnitMembership> memberships) {
    boolean olReach = memberships.stream().anyMatch(m -> m.getRole() == MembershipRole.OL_MEMBER);
    if (olReach) {
      // OL reach is the literal union of every org unit — materialised, never an admin-all marker.
      return new LinkedHashSet<>(orgUnitRepository.findAllOrgUnitIds());
    }
    Set<UUID> reach = new LinkedHashSet<>();
    for (OrgUnitMembership m : memberships) {
      // Only the three area ranks cascade (Bereich → its Staffeln/SKs). Squadron ranks and SK_LEAD
      // confer own-unit reach only (REQ-ROLE-002) and fall through here.
      if (m.getRole().isAreaRank()) {
        UUID bereichId = m.getId().getOrgUnitId();
        reach.add(bereichId);
        reach.addAll(orgUnitRepository.findChildOrgUnitIds(bereichId));
      }
    }
    return reach;
  }

  /**
   * Builds the memoisation key for {@code memberships}: the set of their composite ids. Within a
   * request both consumers pass the authenticated principal's full membership list, so identical
   * rows yield an equal key (cache hit) while a different principal's rows yield a different key
   * (cache miss, recompute) — the cache can never serve one caller's reach to another.
   *
   * <p>The key deliberately carries only the {@code (userId, orgUnitId)} ids, not the leadership
   * role flags the reach actually depends on. This is sound because a single request never mutates
   * the calling principal's own membership flags and then re-resolves scope: both consumers read
   * the same rows via {@code findAllByIdUserId(userId)}, so within one request a given id-set fully
   * determines its flags. A future caller that breaks that assumption would need to key on the
   * flags too.
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
