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

package de.greluc.krt.profit.basetool.backend.mapper;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * MapStruct mapper between {@link User} entities and DTOs.
 *
 * <p>After R9 Steps 4+5 the {@link User} entity no longer carries the legacy {@code squadron},
 * {@code isLogistician}, {@code isMissionManager} columns — the {@code org_unit_membership} rows
 * are now the single source of truth. The DTO contract exposes the membership-derived fields (so
 * the frontend mirror and existing API consumers stay stable); the mapper derives them by reading
 * the caller's Staffel memberships through {@link OrgUnitMembershipRepository}. Since REQ-ORG-017
 * allows up to two Staffeln, {@code squadrons} carries the complete set, {@code squadron} the
 * primary (first by name), and the {@code isLogistician} / {@code isMissionManager} indicators are
 * the OR across all Staffel rows (the flag grants flat authority regardless of which row holds it,
 * REQ-SEC-005). A user without a Staffel membership row (an admin, or any member of no Staffel)
 * maps to {@code squadron = null}, {@code squadrons = []}, {@code isLogistician = false}, {@code
 * isMissionManager = false}.
 *
 * <p>Implemented as an abstract class so MapStruct can field-inject the helper repositories. The
 * generated subclass overrides {@link #toDto(User)} with the field copy and forwards through {@link
 * #resolveSquadrons(User)} / {@link #resolveSquadron(User)} / {@link #resolveLogistician(User)} /
 * {@link #resolveMissionManager(User)} for the membership-derived projections.
 */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {SquadronMapper.class})
public abstract class UserMapper {

  // Field injection mirrors MissionMapper / RefineryOrderMapper — the MapStruct annotation
  // processor generates the subclass with a default constructor, so the helper repositories must
  // come in via field-level @Autowired.
  @Autowired protected OrgUnitMembershipRepository membershipRepository;

  @Autowired protected StaffelMembershipResolver staffelMembershipResolver;

  /**
   * Request-attribute key under which {@link #loadStaffelMemberships(User)} memoises the per-user
   * Staffel-membership lookup for the duration of the current HTTP request. Without it, every
   * {@link #toDto(User)} call fires the same {@code findAllByIdUserIdAndKind} derived query four
   * times (once each from {@link #resolveSquadron(User)} / {@link #resolveSquadrons(User)} / {@link
   * #resolveLogistician(User)} / {@link #resolveMissionManager(User)}) because a JPQL derived query
   * is not served from Hibernate's L1 cache — four real round-trips per user, multiplied across a
   * whole page on the member-facing user list / search endpoints. The memo collapses that to one
   * lookup per distinct user per request and is keyed by user id.
   */
  private static final String MEMBERSHIP_CACHE_ATTR =
      UserMapper.class.getName() + ".staffelMembershipByUserId";

  /**
   * Projects a {@link User} entity to its outbound DTO. The {@code squadron}, {@code squadrons},
   * {@code isLogistician}, {@code isMissionManager} fields are sourced from the user's Staffel
   * membership rows (post-R9 D3) — see the class-level Javadoc for the unassigned-user fallback.
   *
   * <p><strong>{@code email} is deliberately NOT mapped</strong> ({@code ignore = true}) so it is
   * {@code null} on every DTO this method produces. Email is a profile-only field: it may be shown
   * only to the user themselves in their own profile, never to any other user. Because every nested
   * mapper ({@code MissionMapper}, {@code ShipMapper}, {@code JobOrderMapper}) and every list /
   * detail / admin endpoint funnels its {@code User → UserDto} conversion through this single
   * method, omitting email here closes all peer-exposure paths at once and keeps new endpoints safe
   * by default. The only caller that needs the email — the user's own {@code /api/v1/users/me*}
   * view — re-adds it explicitly (see {@code UserController}); do NOT add a second {@code User →
   * UserDto} mapping method here, as that would make the nested-mapper delegation ambiguous.
   *
   * @param user the user entity to project; {@code null} maps to {@code null}.
   * @return the populated DTO with {@code email == null}, or {@code null} when {@code user} is
   *     {@code null}.
   */
  @Mapping(target = "email", ignore = true)
  @Mapping(target = "roles", expression = "java(roleNames(user.getRoles()))")
  @Mapping(target = "permissions", expression = "java(permissions(user.getRoles()))")
  @Mapping(target = "isLogistician", expression = "java(resolveLogistician(user))")
  @Mapping(target = "isMissionManager", expression = "java(resolveMissionManager(user))")
  @Mapping(target = "squadron", expression = "java(resolveSquadron(user))")
  @Mapping(target = "squadrons", expression = "java(resolveSquadrons(user))")
  @Mapping(target = "discordLinked", expression = "java(resolveDiscordLinked(user))")
  public abstract UserDto toDto(User user);

  /** Narrow reference DTO (id + display name) used wherever the full user payload is overkill. */
  public abstract UserReferenceDto toReferenceDto(User user);

  /** MapStruct default - flattens the user's roles to a set of role-name strings. */
  protected Set<String> roleNames(Set<Role> roles) {
    if (roles == null) {
      return Collections.emptySet();
    }
    return roles.stream().map(Role::getName).collect(Collectors.toSet());
  }

  /** MapStruct default - flattens the permissions of every role the user owns into one set. */
  protected Set<String> permissions(Set<Role> roles) {
    if (roles == null) {
      return Collections.emptySet();
    }
    return roles.stream().flatMap(r -> r.getPermissions().stream()).collect(Collectors.toSet());
  }

  /**
   * Resolves the user's <em>complete</em> Staffel membership set for the DTO's {@code squadrons}
   * projection (REQ-ORG-017 — up to two). Reads every {@code SQUADRON}-kind membership row (via the
   * request-memoised {@link #loadStaffelMemberships(User)}) and hands them to {@link
   * StaffelMembershipResolver#resolveNameSortedStaffeln(List)}, the single owner of the name-sort,
   * so this projection's order agrees with {@code OwnerScopeService} and {@code
   * OrgUnitMembershipService} by construction. Each resolved {@link Squadron} becomes a {@link
   * SquadronReferenceDto}; a row whose squadron no longer resolves is dropped by the resolver.
   * Returns an empty list when the user has no Staffel membership (admins, members of no Staffel)
   * or is itself {@code null}.
   *
   * @param user the user being projected; may be {@code null}.
   * @return the user's Staffel reference DTOs, name-sorted; never {@code null}, possibly empty.
   */
  protected List<SquadronReferenceDto> resolveSquadrons(User user) {
    if (user == null || user.getId() == null) {
      return List.of();
    }
    return staffelMembershipResolver
        .resolveNameSortedStaffeln(loadStaffelMemberships(user))
        .stream()
        .map(s -> new SquadronReferenceDto(s.getId(), s.getName(), s.getShorthand()))
        .toList();
  }

  /**
   * Resolves the user's <em>primary</em> Staffel for the DTO's {@code squadron} projection — the
   * first of {@link #resolveSquadrons(User)} (name-sorted), retained for API stability for callers
   * that only render a single Staffel. Returns {@code null} when the user has no Staffel
   * membership.
   *
   * @param user the user being projected; may be {@code null}.
   * @return the user's primary Staffel reference DTO, or {@code null} when no Staffel membership
   *     exists.
   */
  protected SquadronReferenceDto resolveSquadron(User user) {
    return resolveSquadrons(user).stream().findFirst().orElse(null);
  }

  /**
   * Resolves the user's effective {@code isLogistician} flag — {@code true} iff <em>any</em> of the
   * user's Staffel memberships carries {@code isLogistician = true}. The flag grants flat authority
   * regardless of which membership holds it (REQ-SEC-005), so the DTO-level indicator is the OR
   * across the (up to two) Staffel rows. A user without a Staffel membership returns {@code false}.
   *
   * @param user the user being projected; may be {@code null}.
   * @return {@code true} iff any Staffel membership carries {@code isLogistician = true}.
   */
  protected Boolean resolveLogistician(User user) {
    if (user == null || user.getId() == null) {
      return Boolean.FALSE;
    }
    return loadStaffelMemberships(user).stream().anyMatch(OrgUnitMembership::isLogistician);
  }

  /**
   * Resolves the user's effective {@code isMissionManager} flag — the OR across the user's Staffel
   * memberships. Mirrors {@link #resolveLogistician(User)} semantics — no membership row means
   * {@code false}.
   *
   * @param user the user being projected; may be {@code null}.
   * @return {@code true} iff any Staffel membership carries {@code isMissionManager = true}.
   */
  protected Boolean resolveMissionManager(User user) {
    if (user == null || user.getId() == null) {
      return Boolean.FALSE;
    }
    return loadStaffelMemberships(user).stream().anyMatch(OrgUnitMembership::isMissionManager);
  }

  /**
   * Resolves the {@code discordLinked} indicator for the DTO. Returns {@code true} iff the user has
   * a non-blank {@code discord_user_id} — i.e. a Discord account is federated to this Basetool
   * account (REQ-DATA-006). The raw snowflake itself is never copied into the DTO; only this
   * boolean fact leaves the backend, and the admin member-management page renders it as the Discord
   * column (REQ-SEC-019). Independent of the Staffel membership, so it needs no membership lookup.
   *
   * @param user the user being projected; may be {@code null}.
   * @return {@code true} iff a Discord account is linked, {@code false} otherwise.
   */
  protected Boolean resolveDiscordLinked(User user) {
    if (user == null) {
      return Boolean.FALSE;
    }
    String discordUserId = user.getDiscordUserId();
    return discordUserId != null && !discordUserId.isBlank();
  }

  /**
   * Reads every Staffel membership of the user (REQ-ORG-017 — up to two). The list backs all of the
   * {@code squadron(s)} / {@code isLogistician} / {@code isMissionManager} projections.
   *
   * <p>Memoised per request: the four derived-field resolvers each call this for the same user, so
   * without caching the underlying derived query runs once per resolver per {@code toDto}. The
   * result is cached on the {@link RequestAttributes} keyed by user id ({@link
   * #MEMBERSHIP_CACHE_ATTR}), so it runs at most once per distinct user per request. Outside an
   * HTTP request (e.g. a scheduled task that maps a user) there is no request scope, so it falls
   * back to the direct query with no memo. Only eagerly-loaded scalar fields of the returned
   * memberships are read by the callers, so a value surviving into a later transaction within the
   * same request is still safe to read.
   *
   * <p>The memo assumes a user's Staffel membership set is <em>immutable for the duration of the
   * request</em>: it is populated lazily on first read, so a write endpoint that mutates the
   * memberships before mapping the affected user sees the fresh value (the memo is still empty at
   * that point). A hypothetical endpoint that maps a user, mutates that same user's memberships,
   * then re-maps the same user in the same request would observe the pre-mutation snapshot — no
   * such flow exists today; one that needs it must evict {@link #MEMBERSHIP_CACHE_ATTR} after the
   * mutation.
   *
   * @param user the user whose Staffel memberships to load; never {@code null}.
   * @return the user's Staffel membership rows; never {@code null}, possibly empty.
   */
  private List<OrgUnitMembership> loadStaffelMemberships(User user) {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return queryStaffelMemberships(user);
    }
    @SuppressWarnings("unchecked")
    Map<UUID, List<OrgUnitMembership>> cache =
        (Map<UUID, List<OrgUnitMembership>>)
            attrs.getAttribute(MEMBERSHIP_CACHE_ATTR, RequestAttributes.SCOPE_REQUEST);
    if (cache == null) {
      cache = new HashMap<>();
      attrs.setAttribute(MEMBERSHIP_CACHE_ATTR, cache, RequestAttributes.SCOPE_REQUEST);
    }
    return cache.computeIfAbsent(user.getId(), id -> queryStaffelMemberships(user));
  }

  /**
   * Executes the actual Staffel-membership lookup behind {@link #loadStaffelMemberships(User)},
   * without the request-scoped memo.
   *
   * @param user the user whose Staffel memberships to query; never {@code null}.
   * @return the user's Staffel membership rows; never {@code null}, possibly empty.
   */
  private List<OrgUnitMembership> queryStaffelMemberships(User user) {
    return membershipRepository.findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON);
  }
}
