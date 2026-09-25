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
import de.greluc.krt.profit.basetool.backend.support.RequestMemo;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Maps {@link User} entities to DTOs, deriving {@code squadron}, {@code squadrons}, {@code
 * isLogistician} and {@code isMissionManager} from the user's Staffel memberships.
 *
 * <p>{@code squadrons} holds all Staffeln (REQ-ORG-017), {@code squadron} the first by name, and
 * the two flags are the OR across all Staffel rows (REQ-SEC-005). A user without a Staffel maps to
 * {@code null}, an empty list and {@code false}.
 */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {SquadronMapper.class})
public abstract class UserMapper {

  @Autowired protected OrgUnitMembershipRepository membershipRepository;

  @Autowired protected StaffelMembershipResolver staffelMembershipResolver;

  /**
   * Request-memo key for the per-user Staffel memberships loaded by {@link
   * #loadStaffelMemberships(User)}, keyed by user id.
   */
  private static final RequestMemo.Key<Map<UUID, List<OrgUnitMembership>>> MEMBERSHIP_CACHE_ATTR =
      RequestMemo.Key.of(UserMapper.class, "staffelMembershipByUserId");

  /**
   * Request-memo key for the name-sorted Staffel references resolved by {@link
   * #resolveSquadrons(User)}, keyed by user id.
   */
  private static final RequestMemo.Key<Map<UUID, List<SquadronReferenceDto>>> SQUADRONS_CACHE_ATTR =
      RequestMemo.Key.of(UserMapper.class, "squadronReferencesByUserId");

  /**
   * Maps a {@link User} to its DTO, with the Staffel-derived fields from the user's memberships.
   *
   * <p>{@code email} is never mapped, so no peer ever sees it; the only caller that needs it, the
   * user's own {@code /api/v1/users/me*} view, adds it explicitly. Keep this the single {@code User
   * → UserDto} method so nested mappers stay unambiguous.
   *
   * @param user the user to project; {@code null} maps to {@code null}
   * @return the DTO with {@code email == null}, or {@code null} for a {@code null} user
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
   * Resolves all of the user's Staffeln (REQ-ORG-017) as name-sorted references via {@link
   * StaffelMembershipResolver#resolveNameSortedStaffeln(List)}.
   *
   * @param user the user being projected; may be {@code null}
   * @return the Staffel references, name-sorted; never {@code null}, possibly empty
   */
  protected List<SquadronReferenceDto> resolveSquadrons(User user) {
    if (user == null || user.getId() == null) {
      return List.of();
    }
    Map<UUID, List<SquadronReferenceDto>> memo = requestMemo(SQUADRONS_CACHE_ATTR);
    if (memo == null) {
      return toSquadronReferences(
          staffelMembershipResolver.resolveNameSortedStaffeln(loadStaffelMemberships(user)));
    }
    List<SquadronReferenceDto> cached = memo.get(user.getId());
    if (cached == null) {
      cached =
          toSquadronReferences(
              staffelMembershipResolver.resolveNameSortedStaffeln(loadStaffelMemberships(user)));
      memo.put(user.getId(), cached);
    }
    return cached;
  }

  /**
   * Seeds both request memos for a batch of users in two queries (REQ-DATA-003), so the following
   * {@link #toDto(User)} calls read from the memo.
   *
   * <p>Already-memoised users are skipped; outside an HTTP request it is a no-op. Call it right
   * before mapping, never before a membership write in the same request.
   *
   * @param users the users about to be mapped; {@code null} elements and users without an id are
   *     ignored. Never {@code null}.
   */
  public void primeStaffelMemberships(@NotNull Collection<User> users) {
    Map<UUID, List<OrgUnitMembership>> membershipMemo = requestMemo(MEMBERSHIP_CACHE_ATTR);
    Map<UUID, List<SquadronReferenceDto>> squadronMemo = requestMemo(SQUADRONS_CACHE_ATTR);
    if (membershipMemo == null || squadronMemo == null) {
      return;
    }
    Set<UUID> missing = new HashSet<>();
    for (User user : users) {
      if (user != null && user.getId() != null && !squadronMemo.containsKey(user.getId())) {
        missing.add(user.getId());
      }
    }
    if (missing.isEmpty()) {
      return;
    }
    Map<UUID, List<OrgUnitMembership>> rowsByUser = new HashMap<>();
    for (UUID id : missing) {
      rowsByUser.put(id, membershipMemo.getOrDefault(id, List.of()));
    }
    Set<UUID> toQuery = new HashSet<>(missing);
    toQuery.removeAll(membershipMemo.keySet());
    if (!toQuery.isEmpty()) {
      Map<UUID, List<OrgUnitMembership>> loaded =
          membershipRepository
              .findAllByIdUserIdInAndKindIn(toQuery, EnumSet.of(OrgUnitKind.SQUADRON))
              .stream()
              .collect(Collectors.groupingBy(m -> m.getId().getUserId()));
      for (UUID id : toQuery) {
        List<OrgUnitMembership> rows = List.copyOf(loaded.getOrDefault(id, List.of()));
        membershipMemo.put(id, rows);
        rowsByUser.put(id, rows);
      }
    }
    staffelMembershipResolver
        .resolveNameSortedStaffelnByUser(rowsByUser)
        .forEach((id, staffeln) -> squadronMemo.put(id, toSquadronReferences(staffeln)));
  }

  /**
   * Projects resolved squadrons to their slim reference DTOs, keeping the primary-first order.
   *
   * @param staffeln the name-sorted squadrons; never {@code null}.
   * @return the reference DTOs in the same order; never {@code null}.
   */
  private static List<SquadronReferenceDto> toSquadronReferences(@NotNull List<Squadron> staffeln) {
    return staffeln.stream()
        .map(s -> new SquadronReferenceDto(s.getId(), s.getName(), s.getShorthand()))
        .toList();
  }

  /**
   * Returns the request-scoped memo map stored under {@code key}, creating it on first use.
   *
   * @param key the memo's key
   * @param <V> the memoised value type
   * @return the memo keyed by user id, or {@code null} outside an HTTP request
   */
  @Nullable
  private static <V> Map<UUID, V> requestMemo(@NotNull RequestMemo.Key<Map<UUID, V>> key) {
    return RequestMemo.getIfBound(key, HashMap::new);
  }

  /**
   * Resolves the user's primary Staffel, the first of {@link #resolveSquadrons(User)}.
   *
   * @param user the user being projected; may be {@code null}
   * @return the primary Staffel reference, or {@code null} without a Staffel membership
   */
  protected SquadronReferenceDto resolveSquadron(User user) {
    return resolveSquadrons(user).stream().findFirst().orElse(null);
  }

  /**
   * Resolves the effective {@code isLogistician} flag as the OR across the user's Staffel
   * memberships (REQ-SEC-005).
   *
   * @param user the user being projected; may be {@code null}
   * @return {@code true} iff any Staffel membership carries {@code isLogistician = true}
   */
  protected Boolean resolveLogistician(User user) {
    if (user == null || user.getId() == null) {
      return Boolean.FALSE;
    }
    return loadStaffelMemberships(user).stream().anyMatch(OrgUnitMembership::isLogistician);
  }

  /**
   * Resolves the effective {@code isMissionManager} flag as the OR across the user's Staffel
   * memberships.
   *
   * @param user the user being projected; may be {@code null}
   * @return {@code true} iff any Staffel membership carries {@code isMissionManager = true}
   */
  protected Boolean resolveMissionManager(User user) {
    if (user == null || user.getId() == null) {
      return Boolean.FALSE;
    }
    return loadStaffelMemberships(user).stream().anyMatch(OrgUnitMembership::isMissionManager);
  }

  /**
   * Resolves whether a Discord account is linked, i.e. {@code discord_user_id} is non-blank
   * (REQ-DATA-006); the id itself never leaves the backend.
   *
   * @param user the user being projected; may be {@code null}
   * @return {@code true} iff a Discord account is linked
   */
  protected Boolean resolveDiscordLinked(User user) {
    if (user == null) {
      return Boolean.FALSE;
    }
    String discordUserId = user.getDiscordUserId();
    return discordUserId != null && !discordUserId.isBlank();
  }

  /**
   * Loads every Staffel membership of the user (REQ-ORG-017), memoised per request by user id;
   * outside a request it queries directly.
   *
   * <p>The memo assumes the membership set does not change within the request; a flow that mutates
   * and then re-maps the same user must evict {@link #MEMBERSHIP_CACHE_ATTR}.
   *
   * @param user the user whose memberships to load; never {@code null}
   * @return the Staffel membership rows; never {@code null}, possibly empty
   */
  private List<OrgUnitMembership> loadStaffelMemberships(User user) {
    Map<UUID, List<OrgUnitMembership>> cache = requestMemo(MEMBERSHIP_CACHE_ATTR);
    if (cache == null) {
      return queryStaffelMemberships(user);
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
  private List<OrgUnitMembership> queryStaffelMemberships(@NotNull User user) {
    return membershipRepository.findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON);
  }
}
