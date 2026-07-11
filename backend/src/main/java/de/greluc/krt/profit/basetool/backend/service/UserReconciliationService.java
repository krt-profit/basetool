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
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the local {@code app_user} mirror with Keycloak — extracted out of {@link UserService}
 * (audit Thema&nbsp;7, #1252) so the auth hot-path lives on its own seam. Two feeds keep the mirror
 * in sync: {@link #syncUser(Jwt)} runs on every authentication (via {@link
 * CustomJwtGrantedAuthoritiesConverter}) and creates/updates the row from the decoded token, and
 * {@link #syncUser(KeycloakUserDto)} + {@link #markMissingUsers} are driven by {@link UserSyncTask}
 * to mirror the Keycloak Admin API and soft-delete departed accounts. Role mapping ({@link
 * #mapRoles}, {@link #extractRolesFromJwt}, {@link #getMappableRoleNames}) and the incremental
 * Discord back-fill skip-set ({@link #getKnownDiscordLinkedUserIds}) live here too.
 *
 * <p>The JWT subject resolution stays in {@link UserService#getUserIdFromJwt(Jwt)} (the identity
 * seam), and the fail-safe PENDING stamping is delegated to {@link
 * UserRegistrationService#stampNewPendingRegistration} so both sync feeds evaluate the {@code
 * app.registration.require-approval} gate through the one shared helper and cannot drift.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserReconciliationService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final DefaultBlueprintProvisioningService defaultBlueprintProvisioningService;
  private final ApplicationEventPublisher eventPublisher;
  private final UserRegistrationService userRegistrationService;
  private final UserService userService;

  /**
   * Reconciles the local user record with the supplied JWT — creates the row on first login,
   * updates username / email / displayName / roles when they have changed. Called by {@link
   * CustomJwtGrantedAuthoritiesConverter} on every authentication.
   *
   * <p>Two-step lookup: first by id (the UUID-shaped subject), then by {@code preferred_username} —
   * handles legacy rows where the local id pre-dated the Keycloak migration to UUID subjects. Roles
   * default to "Guest" when the JWT carries none so an unconfigured Keycloak doesn't lock everyone
   * out.
   *
   * @param jwt validated JWT from the resource server
   * @return the managed (created or updated) user
   */
  @Transactional
  @NotNull
  public User syncUser(@NotNull Jwt jwt) {
    final UUID finalUserId = userService.getUserIdFromJwt(jwt);
    String username = jwt.getClaimAsString("preferred_username");

    // A Discord federated login is recognised ONLY by the Keycloak subject / discord_user_id link,
    // never by username. Reading the claim up-front lets us suppress the legacy preferred_username
    // fallback below for a Discord login (REQ-SEC-017 / REQ-DATA-006 hardening): the brokered
    // Discord
    // username is attacker-influenced, so matching a fresh Discord identity onto a pre-existing
    // (possibly privileged, already-ACTIVE) row by username would both bypass the PENDING approval
    // gate and silently link the attacker's Discord account to someone else's user. Track 1 does no
    // auto-linking of an existing account to a Discord identity (spec open decision #2), so a
    // Discord
    // login that finds no row by subject is always treated as a brand-new registration.
    final String discordUserId = jwt.getClaimAsString("discord_user_id");
    final boolean viaDiscord = discordUserId != null && !discordUserId.isBlank();

    Optional<User> existingUser = userRepository.findById(finalUserId);
    if (existingUser.isEmpty() && username != null && !viaDiscord) {
      existingUser = userRepository.findByUsername(username);
      if (existingUser.isPresent()) {
        // REQ-OBS-004: never log names/handles. preferred_username can be a real callsign that the
        // PiiMasker cannot scrub (it only matches JWTs, e-mails and token keywords), so log the
        // matched row's UUID instead — same non-PII fix already applied to UserSyncTask (M-4).
        log.warn(
            "User lookup by ID {} failed; matched an existing row by preferred_username (value"
                + " omitted, PII). Associating session with existing user id {}.",
            finalUserId,
            existingUser.get().getId());
      }
    }

    // A truly new user (no row by id and none by username) is provisioned for the first time; the
    // post-save event grants their default blueprints after commit (REQ-INV-016).
    final boolean created = existingUser.isEmpty();

    User user =
        existingUser.orElseGet(
            () -> {
              User u = new User();
              u.setId(finalUserId);
              return u;
            });

    boolean changed = false;

    if (!Objects.equals(user.getUsername(), username)) {
      user.setUsername(username);
      changed = true;
    }

    // Privacy / data minimisation: given_name / family_name are intentionally NOT read or stored
    // anymore (the columns were dropped from the entity). Only username, email and roles are
    // mirrored locally.
    String email = jwt.getClaimAsString("email");
    if (!Objects.equals(user.getEmail(), email)) {
      user.setEmail(email);
      changed = true;
    }

    // Sync Roles
    Set<String> keycloakRoles = extractRolesFromJwt(jwt);
    Set<Role> localRoles = mapRoles(keycloakRoles);

    if (!user.getRoles().equals(localRoles)) {
      user.setRoles(localRoles);
      changed = true;
    }

    // Persist the Discord account link (auto-link, REQ-DATA-006) from the IdP-mapped token claim.
    // discordUserId / viaDiscord were resolved up-front (see the subject-only lookup above).
    if (viaDiscord && !Objects.equals(user.getDiscordUserId(), discordUserId)) {
      user.setDiscordUserId(discordUserId);
      changed = true;
    }

    // Persist the per-guild Discord server nickname (REQ-DATA-008) from the optional IdP-mapped
    // claim. Display-only — shown to admins in the registration-approval queue so a decision can be
    // tied to a recognisable in-server identity. Captured best-effort, so it may be absent (no
    // nickname set, non-Discord login, or capture mappers not configured); refreshes on every
    // login.
    String guildNickname = normalizeGuildNickname(jwt.getClaimAsString("discord_guild_nickname"));
    if (!Objects.equals(user.getDiscordGuildNickname(), guildNickname)) {
      user.setDiscordGuildNickname(guildNickname);
      changed = true;
    }

    // Approval lifecycle (epic #720, Track 1 / REQ-SEC-017 — fail-safe default). EVERY brand-new
    // non-admin registration lands PENDING and receives no authorities until an admin approves,
    // regardless of whether the login arrived via Discord or credentials. The PENDING stamping is
    // delegated to the shared UserRegistrationService#stampNewPendingRegistration so this JWT path
    // and the scheduled Admin-API path evaluate the approval gate identically — deliberately
    // decoupled from Discord detection: the PENDING decision must NOT depend on the optional
    // discord_user_id claim/mapper, otherwise a misconfigured Keycloak (attribute/protocol mapper
    // absent) would let a federated login inherit the ACTIVE entity-default and silently skip
    // approval. Keycloak ADMIN-realm-role holders are auto-ACTIVE (bootstrap safety — the first
    // admin can never be locked out), and the carve-out below also promotes an existing PENDING
    // admin to ACTIVE (specific to this interactive path). The admin notification (REQ-NOTIF-012)
    // fires for EVERY such new PENDING registration, keyed off the PENDING transition itself and
    // NOT off the discord_user_id claim: a missing claim mapper must never silence an approval
    // notification any more than it may skip the gate. A credential registration therefore notifies
    // too — at first login there is no reliable Discord signal without the claim, and the event
    // carries no Discord id/PII anyway (only id + username).
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

    if (changed || user.isNew()) {
      User saved = userRepository.save(user);
      if (created) {
        // Grant the default blueprints in THIS transaction so a brand-new user has them committed
        // before the request returns (REQ-INV-016). The grant is an idempotent bulk INSERT … ON
        // CONFLICT touching only personal_blueprint (never the app_user row), so it neither bumps
        // the user's @Version nor collides with the converter's retry. The id is the Keycloak sub,
        // set before the first save.
        defaultBlueprintProvisioningService.grantDefaultsToUser(user.getId().toString());
      }
      if (newPendingRegistration) {
        // After-commit listener notifies every admin of the new pending registration
        // (REQ-NOTIF-012); the event carries no Discord id/PII (only user id + username).
        eventPublisher.publishEvent(
            new DiscordRegistrationPendingEvent(saved.getId(), saved.getUsername()));
      }
      return saved;
    }

    return user;
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
      changed = true;
    }

    if (!Objects.equals(user.getUsername(), dto.username())) {
      user.setUsername(dto.username());
      changed = true;
    }

    // Privacy / data minimisation: first / last name are intentionally not mirrored (see
    // syncUser(Jwt)).
    if (!Objects.equals(user.getEmail(), dto.email())) {
      user.setEmail(dto.email());
      changed = true;
    }

    // Sync Roles
    Set<Role> localRoles = mapRoles(dto.roles());
    if (!user.getRoles().equals(localRoles)) {
      user.setRoles(localRoles);
      changed = true;
    }

    // Persist the Discord account link discovered out-of-band via the Keycloak Admin API
    // (/federated-identity, see KeycloakService#fetchDiscordFederatedId). This is what surfaces the
    // link for accounts that linked Discord AFTER creation — the import-time token claim only
    // covers
    // accounts that registered via Discord (REQ-DATA-006). Like syncUser(Jwt) this only SETS the
    // link, never clears it: the federated-identity fetch is best-effort and returns null on any
    // failure, so clearing on a null would wrongly wipe a real link on a transient Admin-API
    // hiccup.
    String discordUserId = dto.discordUserId();
    if (discordUserId != null
        && !discordUserId.isBlank()
        && !Objects.equals(user.getDiscordUserId(), discordUserId)) {
      user.setDiscordUserId(discordUserId);
      changed = true;
    }

    // Fail-safe approval default (REQ-SEC-017), mirroring syncUser(Jwt): a brand-new non-admin user
    // first discovered by the scheduled reconciliation lands PENDING via the shared
    // UserRegistrationService#stampNewPendingRegistration, so the scheduler can never pre-create an
    // ACTIVE row that a later interactive login would inherit (created == false) and thereby skip
    // the approval gate. Admins stay ACTIVE (entity default, bootstrap safety). Only brand-new rows
    // are touched — an existing user's approval state is never changed here.
    boolean newPendingRegistration =
        userRegistrationService.stampNewPendingRegistration(user, created, localRoles);
    if (newPendingRegistration) {
      changed = true;
    }

    if (changed || user.isNew()) {
      userRepository.save(user);
      if (created) {
        // Grant the default blueprints synchronously on first creation (REQ-INV-016); idempotent.
        defaultBlueprintProvisioningService.grantDefaultsToUser(user.getId().toString());
      }
      if (newPendingRegistration) {
        // A registration first materialised by the scheduled reconciler (rather than by the
        // interactive login) must still notify the admins (REQ-NOTIF-012). Gating on `created`
        // keeps it exactly-once across both paths: whichever path inserts the row has created ==
        // true and publishes; every later call in either path sees created == false and stays
        // silent, so no persisted "announced" flag is needed. The event carries no Discord id/PII.
        eventPublisher.publishEvent(
            new DiscordRegistrationPendingEvent(user.getId(), user.getUsername()));
      }
    }
  }

  /**
   * Flags every local user whose id is NOT in {@code currentIds} as missing (soft-delete: kept as a
   * row for FK integrity, but excluded from active lists). Called by {@link UserSyncTask} after the
   * full Keycloak sync run; an empty {@code currentIds} short-circuits without marking anyone so a
   * Keycloak outage doesn't soft-delete the entire user base.
   *
   * @param currentIds the set of user ids currently present in Keycloak
   */
  @Transactional
  public void markMissingUsers(Collection<UUID> currentIds) {
    if (currentIds.isEmpty()) {
      return;
    }
    userRepository.markMissingUsers(currentIds);
  }

  /**
   * Returns the realm role names the scheduled Keycloak sync should index against Keycloak — the
   * display names of every role in the local catalog. {@code KeycloakService.fetchUsers} matches
   * these case-insensitively against the realm's actual role names (as {@link
   * #mapRoles(Collection)} joins them via {@code findByNameIgnoreCase}), so the role-indexed
   * membership fetch queries only the roles the app actually maps — never the ubiquitous
   * default/technical realm roles — and does so regardless of any casing difference between the
   * catalog and Keycloak. Read-only.
   *
   * @return the mappable realm role names; never {@code null}, possibly empty.
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
   * Maps Keycloak realm-role names to the local {@link Role} catalog, case-insensitively; unmatched
   * names are dropped. Falls back to the {@code Guest} role when none match (or the input is {@code
   * null}) so an unconfigured / mismatched Keycloak never leaves a user role-less.
   *
   * @param roleNames the Keycloak role names to map, possibly {@code null}
   * @return the matched local roles, or {@code Guest} as a singleton fallback; never {@code null}
   */
  private Set<Role> mapRoles(Collection<String> roleNames) {
    Set<Role> localRoles = new HashSet<>();
    if (roleNames != null) {
      for (String roleName : roleNames) {
        roleRepository.findByNameIgnoreCase(roleName).ifPresent(localRoles::add);
      }
    }

    if (localRoles.isEmpty()) {
      roleRepository.findByNameIgnoreCase("Guest").ifPresent(localRoles::add);
    }
    return localRoles;
  }

  /**
   * Normalises a raw Discord guild-nickname claim for storage: trims it, maps blank/empty to {@code
   * null}, and bounds it to the {@code discord_guild_nickname} column width (255 chars) so a
   * pathologically long attribute can never fail the login save. The Keycloak SPI already caps the
   * captured value, so this length bound is only a defensive backstop (REQ-DATA-008).
   *
   * @param raw the raw {@code discord_guild_nickname} claim value, possibly {@code null}
   * @return the trimmed, length-bounded nickname, or {@code null} when the claim is absent or blank
   */
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
    // Also check resource_access if needed, but realm_access is standard for realm roles
    return roles;
  }
}
