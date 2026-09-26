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

import de.greluc.krt.profit.basetool.backend.event.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.PartialRoleScopeProperties;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the local {@code app_user} mirror with Keycloak.
 *
 * <p>{@link #syncUser(Jwt)} runs on every authentication via {@link
 * CustomJwtGrantedAuthoritiesConverter}; {@link #syncUser(KeycloakUserDto)} and {@link
 * #markMissingUsers} are driven by {@link UserSyncTask} from the Admin API. New registrations are
 * stamped PENDING through {@link UserRegistrationService#stampNewPendingRegistration}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserReconciliationService {

  /**
   * Number of role-less accounts at the end of one sync run above which the run summary escalates
   * from INFO to WARN; many at once indicate a realm-side role rename, which locks the affected
   * members out with {@code 403 NO_ROLE} (REQ-SEC-053).
   */
  private static final int ROLE_LESS_WARN_THRESHOLD = 3;

  /** Accounts whose persisted role set actually changed in the current Admin-API sync run. */
  private final AtomicInteger roleChangedAccounts = new AtomicInteger();

  /**
   * Accounts whose role change in the current run left them with NO local role — i.e. none of their
   * Keycloak role names resolved.
   */
  private final AtomicInteger roleLessAccounts = new AtomicInteger();

  /**
   * Keycloak role names seen in the current run that matched no local role and were dropped
   * (counted across accounts, so a renamed role contributes one per holder).
   */
  private final AtomicInteger droppedRoleNames = new AtomicInteger();

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final DefaultBlueprintProvisioningService defaultBlueprintProvisioningService;
  private final ApplicationEventPublisher eventPublisher;
  private final UserRegistrationService userRegistrationService;
  private final UserService userService;
  private final PartialRoleScopeProperties partialRoleScopeProperties;

  /** Counts callsign collisions between a new subject and an existing account. */
  private final MeterRegistry meterRegistry;

  /**
   * Reconciles the local user record with the supplied JWT on every authentication: creates the row
   * on first login and updates username, e-mail, display name and roles when they changed.
   *
   * <p>The lookup is by id only; a row holding the same username under another id is logged and
   * counted as a collision (ADR-0142). A token of a partial-scope client (REQ-SEC-036) leaves the
   * stored roles untouched, and {@link ReconciledUser#effectiveRoles()} then carries the token's
   * roles.
   *
   * @param jwt validated JWT from the resource server
   * @return the managed (created or updated) user together with the roles authorising this request
   */
  @Transactional
  @NotNull
  public ReconciledUser syncUser(@NotNull Jwt jwt) {
    final UUID finalUserId = userService.getUserIdFromJwt(jwt);
    String username = jwt.getClaimAsString("preferred_username");

    final String discordUserId = jwt.getClaimAsString("discord_user_id");
    final boolean viaDiscord = discordUserId != null && !discordUserId.isBlank();

    Optional<User> existingUser = userRepository.findById(finalUserId);
    if (existingUser.isEmpty() && username != null) {
      List<UUID> sameCallsign = userRepository.findIdsByUsername(username);
      if (!sameCallsign.isEmpty()) {
        meterRegistry.counter(MetricNames.USER_CALLSIGN_COLLISIONS).increment();
        log.warn(
            "Callsign collision: subject {} is unknown, but {} existing account(s) hold the same"
                + " preferred_username (value omitted, PII), e.g. {}. Provisioning a NEW pending"
                + " registration; nothing is inherited. An admin merges the two explicitly.",
            finalUserId,
            sameCallsign.size(),
            sameCallsign.getFirst());
      }
    }

    final boolean created = existingUser.isEmpty();

    User user =
        existingUser.orElseGet(
            () -> {
              User u = new User();
              u.setId(finalUserId);
              return u;
            });

    boolean changed = false;

    if (!user.isEnabledInKeycloak()) {
      user.setEnabledInKeycloak(true);
      changed = true;
    }

    if (!Objects.equals(user.getUsername(), username)) {
      user.setUsername(username);
      changed = true;
    }

    String email = jwt.getClaimAsString("email");
    if (!Objects.equals(user.getEmail(), email)) {
      user.setEmail(email);
      changed = true;
    }

    Set<String> keycloakRoles = extractRolesFromJwt(jwt);
    Set<Role> localRoles = mapRoles(keycloakRoles);
    final boolean roleClaimIsPartial =
        partialRoleScopeProperties.isPartialRoleScopeClient(jwt.getClaimAsString("azp"));

    final boolean mayPersistRoles = !roleClaimIsPartial || created;

    if (mayPersistRoles) {
      if (!user.getRoles().equals(localRoles)) {
        user.setRoles(localRoles);
        changed = true;
      }
    } else if (!user.getRoles().equals(localRoles)) {
      log.debug(
          "Role claim from partial-scope client not persisted for user {} (stored {} roles, token"
              + " carried {})",
          user.getId(),
          user.getRoles().size(),
          localRoles.size());
    }

    if (viaDiscord && applyDiscordLink(user, discordUserId)) {
      changed = true;
    }

    String guildNickname = normalizeGuildNickname(jwt.getClaimAsString("discord_guild_nickname"));
    if (!Objects.equals(user.getDiscordGuildNickname(), guildNickname)) {
      user.setDiscordGuildNickname(guildNickname);
      changed = true;
    }

    boolean isAdmin = localRoles.stream().anyMatch(r -> Roles.ADMIN.equalsIgnoreCase(r.getCode()));
    boolean newPendingRegistration =
        userRegistrationService.stampNewPendingRegistration(user, created, localRoles);
    if (newPendingRegistration) {
      changed = true;
    } else if (!created && isAdmin && user.getApprovalStatus() != ApprovalStatus.ACTIVE) {
      user.setApprovalStatus(ApprovalStatus.ACTIVE);
      user.setApprovedAt(Instant.now());
      changed = true;
    }
    if (created && isAdmin) {
      meterRegistry.counter(MetricNames.ADMIN_REGISTRATION_AUTO_ACTIVATED).increment();
    }

    if (changed || user.isNew()) {
      User saved = userRepository.save(user);
      if (created) {
        defaultBlueprintProvisioningService.grantDefaultsToUser(user.getId());
      }
      if (newPendingRegistration) {
        eventPublisher.publishEvent(
            new DiscordRegistrationPendingEvent(saved.getId(), saved.getUsername()));
      }
      return new ReconciledUser(saved, localRoles);
    }

    return new ReconciledUser(user, localRoles);
  }

  /**
   * Reconciles the local user record from a Keycloak Admin API response (scheduled sync path).
   * Mirrors {@link #syncUser(Jwt)} but reads the data from a {@code KeycloakUserDto} instead of a
   * decoded JWT. Used by {@link UserSyncTask}.
   *
   * @param dto Keycloak admin DTO
   */
  @Transactional
  public void syncUser(@NotNull KeycloakUserDto dto) {
    if (dto.id() == null) {
      return;
    }

    Optional<User> existingUser = userRepository.findById(dto.id());
    final boolean created = existingUser.isEmpty();
    User user =
        existingUser.orElseGet(
            () -> {
              User u = new User();
              u.setId(dto.id());
              return u;
            });

    boolean changed = false;

    if (!user.isInKeycloak()) {
      user.setInKeycloak(true);
      user.setKeycloakAbsentSince(null);
      changed = true;
    }

    boolean enabled = !Boolean.FALSE.equals(dto.enabled());
    if (user.isEnabledInKeycloak() != enabled) {
      user.setEnabledInKeycloak(enabled);
      changed = true;
    }

    if (!Objects.equals(user.getUsername(), dto.username())) {
      user.setUsername(dto.username());
      changed = true;
    }

    if (!Objects.equals(user.getEmail(), dto.email())) {
      user.setEmail(dto.email());
      changed = true;
    }

    RoleMapping mapping = mapRolesTracked(dto.roles());
    droppedRoleNames.addAndGet(mapping.droppedNames());
    Set<Role> localRoles = mapping.roles();
    if (mapping.roleLess()) {
      roleLessAccounts.incrementAndGet();
    }
    if (!user.getRoles().equals(localRoles)) {
      user.setRoles(localRoles);
      changed = true;
      roleChangedAccounts.incrementAndGet();
      log.debug(
          "Role set changed for user {} (roleLess={}, unmatchedKeycloakRoleNames={})",
          user.getId(),
          mapping.roleLess(),
          mapping.droppedNames());
    }

    String discordUserId = dto.discordUserId();
    if (discordUserId != null
        && !discordUserId.isBlank()
        && applyDiscordLink(user, discordUserId)) {
      changed = true;
    }

    boolean newPendingRegistration =
        userRegistrationService.stampNewPendingRegistration(user, created, localRoles);
    if (newPendingRegistration) {
      changed = true;
    } else if (created
        && localRoles.stream().anyMatch(r -> Roles.ADMIN.equalsIgnoreCase(r.getCode()))) {
      meterRegistry.counter(MetricNames.ADMIN_REGISTRATION_AUTO_ACTIVATED).increment();
    }

    if (changed || user.isNew()) {
      userRepository.save(user);
      if (created) {
        defaultBlueprintProvisioningService.grantDefaultsToUser(user.getId());
      }
      if (newPendingRegistration) {
        eventPublisher.publishEvent(
            new DiscordRegistrationPendingEvent(user.getId(), user.getUsername()));
      }
    }
  }

  /**
   * Writes the Discord snowflake onto {@code user} unless a different account already holds it, in
   * which case the write is skipped, counted and logged.
   *
   * <p>{@code app_user.discord_user_id} is unique, so a blind write would fail the whole
   * reconciliation of that user. The log names both account ids, never the snowflake (REQ-OBS-004).
   *
   * @param user the managed row to link; never {@code null}
   * @param discordUserId the snowflake to write; never {@code null} or blank
   * @return {@code true} when the link was written and the caller must persist, {@code false} when
   *     it was already present or another account holds it
   */
  private boolean applyDiscordLink(@NotNull User user, @NotNull String discordUserId) {
    if (Objects.equals(user.getDiscordUserId(), discordUserId)) {
      return false;
    }
    Optional<UUID> holder = userRepository.findIdByDiscordUserId(discordUserId);
    if (holder.isPresent() && !holder.get().equals(user.getId())) {
      meterRegistry.counter(MetricNames.USER_DISCORD_LINK_COLLISIONS).increment();
      log.warn(
          "Discord link not written for user {}: account {} already holds the same identity."
              + " Consolidate the two accounts; the link follows once the duplicate row is gone.",
          user.getId(),
          holder.get());
      return false;
    }
    user.setDiscordUserId(discordUserId);
    return true;
  }

  /**
   * Soft-deletes every local user whose id is not in {@code currentIds}; an empty set marks nobody,
   * so a Keycloak outage cannot soft-delete everyone.
   *
   * <p>{@code currentIds} must hold every id the Keycloak fetch reported, including users whose own
   * sync failed, or a transient failure soft-deletes a present member (REQ-SEC-043).
   *
   * @param currentIds the set of user ids currently present in Keycloak
   * @return the number of users newly flagged as missing; {@code 0} when {@code currentIds} is
   *     empty or nobody disappeared
   */
  @Transactional
  public int markMissingUsers(Collection<UUID> currentIds) {
    if (currentIds.isEmpty()) {
      return 0;
    }
    return userRepository.markMissingUsers(currentIds, Instant.now());
  }

  /**
   * Returns the display names of every local role, which the scheduled Keycloak sync matches
   * case-insensitively against the realm's roles to fetch memberships.
   *
   * @return the mappable realm role names; never {@code null}, possibly empty
   */
  @NotNull
  public Set<String> getMappableRoleNames() {
    return roleRepository.findAllNames();
  }

  /**
   * Returns the ids of local users that already carry a Discord link, so the scheduled sync can
   * skip the per-user federated-identity read for them (incremental back-fill). Read-only.
   *
   * @return the ids of users with a non-null Discord link; never {@code null}, possibly empty.
   */
  @NotNull
  public Set<UUID> getKnownDiscordLinkedUserIds() {
    return userRepository.findIdsWithDiscordLink();
  }

  /**
   * Logs the per-run role-mapping summary and resets the tallies for the next run.
   *
   * <p>Counts only, never handles (REQ-OBS-004). INFO for an ordinary run, WARN once more than
   * {@link #ROLE_LESS_WARN_THRESHOLD} accounts ended role-less. Only the Admin-API path feeds the
   * tallies, so logins do not inflate them.
   */
  public void logRoleSyncSummary() {
    int changed = roleChangedAccounts.getAndSet(0);
    int roleLess = roleLessAccounts.getAndSet(0);
    int dropped = droppedRoleNames.getAndSet(0);
    if (roleLess > ROLE_LESS_WARN_THRESHOLD) {
      log.warn(
          "User sync role mapping: {} accounts changed roles; {} accounts resolve to NO role at all"
              + " and are refused with NO_ROLE; {} Keycloak role names matched no local role —"
              + " check for a renamed or deleted realm role.",
          changed,
          roleLess,
          dropped);
      return;
    }
    log.info(
        "User sync role mapping: {} accounts changed roles; {} accounts resolve to no role; {}"
            + " Keycloak role names matched no local role.",
        changed,
        roleLess,
        dropped);
  }

  /**
   * Maps Keycloak realm-role names to the local {@link Role} catalog, case-insensitively; unmatched
   * names are dropped and nothing is substituted when none match. Thin wrapper over {@link
   * #mapRolesTracked(Collection)} for the per-login JWT path, which deliberately does not feed the
   * sync-run tallies.
   *
   * @param roleNames the Keycloak role names to map, possibly {@code null}
   * @return the matched local roles, possibly empty; never {@code null}
   */
  private Set<Role> mapRoles(Collection<String> roleNames) {
    return mapRolesTracked(roleNames).roles();
  }

  /**
   * The role catalogue keyed by lower-cased name, read once per account with the permissions
   * fetched.
   *
   * <p>Deliberately not cached across calls: {@link Role} entities from an earlier transaction
   * would be detached in the caller's persistence context and fail the flush.
   *
   * @return role by lower-cased name, managed by the caller's persistence context; never {@code
   *     null}
   */
  @NotNull
  private Map<String, Role> roleCatalogue() {
    return roleRepository.findAllWithPermissions().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                role -> role.getName().toLowerCase(Locale.ROOT), role -> role, (a, b) -> a));
  }

  /**
   * Maps Keycloak realm-role names onto the local catalog exactly like {@link #mapRoles} but also
   * reports HOW the mapping went, so the caller can tell a legitimate role change apart from a
   * total failure to resolve: a name that matches nothing is dropped, and an account for which
   * nothing matched at all comes back with an empty set rather than a substitute role.
   *
   * @param roleNames the Keycloak role names to map, possibly {@code null}
   * @return the mapped roles plus the role-less flag and the number of dropped names
   */
  @NotNull
  private RoleMapping mapRolesTracked(@Nullable Collection<String> roleNames) {
    Set<Role> localRoles = new HashSet<>();
    int dropped = 0;
    if (roleNames != null) {
      Map<String, Role> catalogue = roleCatalogue();
      for (String roleName : roleNames) {
        Role match = catalogue.get(roleName.toLowerCase(Locale.ROOT));
        if (match != null) {
          localRoles.add(match);
        } else {
          dropped++;
        }
      }
    }

    return new RoleMapping(localRoles, localRoles.isEmpty(), dropped);
  }

  /**
   * Outcome of one role-name mapping: the resolved local roles plus the two facts the raw set
   * cannot express — whether nothing matched at all and how many supplied names were dropped.
   *
   * @param roles the resolved local roles, possibly empty
   * @param roleLess {@code true} when no supplied name resolved to a local role
   * @param droppedNames how many supplied Keycloak role names matched no local role
   */
  private record RoleMapping(@NotNull Set<Role> roles, boolean roleLess, int droppedNames) {}

  /**
   * What one authentication reconciled: the managed row, and the roles that authorise this request.
   *
   * <p>The two differ for a partial-scope client (REQ-SEC-036): the row keeps its roles and {@link
   * #effectiveRoles()} carries only what the token presented.
   *
   * @param user the created or updated {@code app_user} row; never {@code null}
   * @param effectiveRoles the token's roles mapped onto the local catalogue; possibly empty, which
   *     {@code assembleFor} refuses (REQ-SEC-053); never {@code null}
   */
  public record ReconciledUser(@NotNull User user, @NotNull Set<Role> effectiveRoles) {

    /**
     * A reconciliation whose effective roles are simply the stored ones -- the shape every
     * complete-claim client produces.
     *
     * @param user the row whose stored roles are also the effective ones
     * @return the pair, with {@code effectiveRoles} taken from {@code user}
     */
    @NotNull
    public static ReconciledUser of(@NotNull User user) {
      return new ReconciledUser(user, user.getRoles());
    }
  }

  /**
   * Normalises a raw Discord guild-nickname claim for storage: trims it, maps blank/empty to {@code
   * null}, and bounds it to the {@code discord_guild_nickname} column width (255 chars) so a
   * pathologically long attribute can never fail the login save. The Keycloak SPI already caps the
   * captured value, so this length bound is only a defensive backstop (REQ-DATA-018).
   *
   * @param raw the raw {@code discord_guild_nickname} claim value, possibly {@code null}
   * @return the trimmed, length-bounded nickname, or {@code null} when the claim is absent or blank
   */
  @Contract("null -> null")
  @Nullable
  private static String normalizeGuildNickname(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
  }

  /**
   * Extracts the set of realm-role names from a JWT. Reads the {@code realm_access.roles} claim —
   * Keycloak's standard location. Resource-access roles are intentionally NOT included because this
   * project's authorization model is realm-scoped.
   *
   * @param jwt validated JWT
   * @return realm role names, possibly empty
   */
  @SuppressWarnings("unchecked")
  @NotNull
  public Set<String> extractRolesFromJwt(@NotNull Jwt jwt) {
    Set<String> roles = new HashSet<>();
    Map<String, Object> realmAccess = jwt.getClaim("realm_access");
    if (realmAccess != null && realmAccess.containsKey("roles")) {
      roles.addAll((List<String>) realmAccess.get("roles"));
    }
    return roles;
  }
}
