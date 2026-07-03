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
import de.greluc.krt.profit.basetool.backend.event.UserApprovalDecidedEvent;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.model.ApprovalDecision;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.UserApprovalEvent;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionOwnershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the local {@code app_user} mirror of Keycloak users plus everything around them (roles,
 * logistician/mission-manager flags, rank, description, displayName, joinDate, read announcement
 * state).
 *
 * <p>JWT-driven sync ({@link #syncUser(Jwt)}) runs on every authentication so the local record is
 * always in sync with Keycloak; periodic scheduled sync ({@link #syncUser(KeycloakUserDto)} +
 * {@link #markMissingUsers}) is driven by {@link UserSyncTask} and reconciles deletions. The
 * service is the architectural seam where the project's "every read filters by JWT sub" rule
 * (CLAUDE.md) is enforced — {@link #getCurrentUser()} is the canonical source for the calling
 * user's id and most other services delegate here rather than reaching for {@code
 * SecurityContextHolder} (which is forbidden outside this seam by the ArchUnit rule).
 *
 * <p>JWT subject parsing is fail-closed: a missing {@code sub} or a non-UUID subject is rejected
 * rather than falling back to a derived identifier — silently mapping different Keycloak realms
 * onto the same local id is a worse failure mode than refusing the request.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final ShipRepository shipRepository;
  private final RefineryOrderRepository refineryOrderRepository;
  private final MissionRepository missionRepository;
  private final MissionOwnershipRepository missionOwnershipRepository;
  private final JobOrderRepository jobOrderRepository;
  private final MissionParticipantRepository missionParticipantRepository;
  private final MaterialClaimRepository materialClaimRepository;
  private final AuthHelperService authHelperService;
  private final OwnerScopeService ownerScopeService;
  private final OrgUnitMembershipService orgUnitMembershipService;
  private final DefaultBlueprintProvisioningService defaultBlueprintProvisioningService;
  private final UserApprovalEventRepository userApprovalEventRepository;
  private final AuditService auditService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Whether a brand-new non-admin registration must be approved by an admin before it is granted
   * any authorities (REQ-SEC-017 fail-safe default). {@code true} in prod and by default. Set to
   * {@code false} ONLY in controlled non-prod stacks (the Playwright e2e stack, via {@code
   * APP_REGISTRATION_REQUIRE_APPROVAL=false}) where fixture users are provisioned on the fly and an
   * interactive approval step would deadlock the seeder — there, a new non-admin keeps the {@code
   * ACTIVE} entity default. Field-injected with a {@code true} initializer so Mockito unit tests
   * (no Spring) exercise the secure default without extra wiring.
   */
  @Value("${app.registration.require-approval:true}")
  private boolean requireApproval = true;

  /**
   * Convenience predicate: does any user have this exact name (case-insensitive) as either username
   * or displayName? Used by participant-add flows to detect "this guest name is actually a known
   * member".
   *
   * @param name candidate name
   * @return true when at least one match exists
   */
  public boolean isUsernameOrDisplayNameTaken(@NotNull String name) {
    return !findMatchesByExactName(name).isEmpty();
  }

  /**
   * Resolves a free-text participant name to existing users by case-insensitive exact match on
   * {@code username} or {@code displayName}. The input is trimmed. An empty or blank name yields an
   * empty result without hitting the database.
   *
   * <p>Used by participant-add flows to translate free-text input (when the user did not pick an
   * entry from the autocomplete dropdown) into a concrete user reference, so that a member is
   * correctly linked instead of being (wrongly) rejected as a duplicate guest name.
   */
  @NotNull
  public List<User> findMatchesByExactName(@NotNull String name) {
    String trimmed = name.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    return userRepository.findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase(trimmed, trimmed);
  }

  /**
   * Extracts the user id from the JWT's {@code sub} claim.
   *
   * <p>Fail-closed validation: a missing {@code sub} or a non-UUID value throws {@link
   * org.springframework.security.authentication.AuthenticationServiceException} rather than falling
   * back to a derived id. Silently mapping different Keycloak realms (or two realms with similar
   * usernames) onto the same local id is a worse failure mode than refusing the request — see the
   * explicit AGENTS.md / CLAUDE.md guidance on stable identity.
   *
   * @param jwt validated JWT
   * @return the {@code sub} parsed as UUID
   * @throws org.springframework.security.authentication.AuthenticationServiceException when sub is
   *     missing or not a UUID
   */
  @NotNull
  public UUID getUserIdFromJwt(@NotNull Jwt jwt) {
    String sub = jwt.getSubject();
    if (sub == null) {
      // The OIDC standard requires `sub` on every ID token. A missing subject
      // indicates a misconfigured authorization server. Refuse rather than
      // falling back to a different claim and silently identifying users by
      // a value an admin might rename in Keycloak.
      // Audit finding H-10: only log the claim keys, never the values. The claims map still
      // carries PII (preferred_username / email — and, on a Keycloak that has not yet had its
      // name mappers removed, possibly given_name / family_name) which PiiMasker only partially
      // scrubs — the keys still help diagnose a Keycloak mapper misconfiguration.
      log.error(
          "JWT has no subject (sub). Refusing the request. Claim keys: {}",
          jwt.getClaims().keySet());
      throw new org.springframework.security.authentication.AuthenticationServiceException(
          "JWT subject (sub) must be present");
    }

    try {
      return UUID.fromString(sub);
    } catch (IllegalArgumentException e) {
      // Standard Keycloak issues UUIDs as subjects. A non-UUID sub is a
      // configuration deviation; deriving a UUID via UUID.nameUUIDFromBytes
      // would mix up identities (renaming the underlying value, two realms
      // with similar usernames, casing differences, ...). Fail-closed.
      log.error(
          "JWT subject is not a valid UUID: '{}'. Refusing the request to avoid identity mix-up.",
          sub);
      throw new org.springframework.security.authentication.AuthenticationServiceException(
          "JWT subject must be a UUID");
    }
  }

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
    final UUID finalUserId = getUserIdFromJwt(jwt);
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
    // regardless of whether the login arrived via Discord or credentials. This is deliberately
    // decoupled from Discord detection: the PENDING decision must NOT depend on the optional
    // discord_user_id claim/mapper, otherwise a misconfigured Keycloak (attribute/protocol mapper
    // absent) would let a federated login inherit the ACTIVE entity-default and silently skip
    // approval. Keycloak ADMIN-realm-role holders are auto-ACTIVE (bootstrap safety — the first
    // admin
    // can never be locked out), and the carve-out below also promotes an existing PENDING admin to
    // ACTIVE. The admin notification (REQ-NOTIF-012) fires for EVERY such new PENDING registration,
    // keyed off the PENDING transition itself and NOT off the discord_user_id claim: a missing
    // claim
    // mapper must never silence an approval notification any more than it may skip the gate. A
    // credential registration therefore notifies too — at first login there is no reliable Discord
    // signal without the claim, and the event carries no Discord id/PII anyway (only id +
    // username).
    boolean isAdmin = localRoles.stream().anyMatch(r -> Roles.ADMIN.equalsIgnoreCase(r.getCode()));
    boolean newPendingRegistration = false;
    if (created && !isAdmin && requireApproval) {
      user.setApprovalStatus(ApprovalStatus.PENDING);
      newPendingRegistration = true;
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

    // Fail-safe approval default (REQ-SEC-017), mirroring syncUser(Jwt). A brand-new non-admin user
    // first discovered by the scheduled reconciliation lands PENDING, so the scheduler can never
    // pre-create an ACTIVE row that a later interactive login would inherit (created == false) and
    // thereby skip the approval gate. Admins stay ACTIVE (entity default, bootstrap safety). Only
    // brand-new rows are touched — an existing user's approval state is never changed here.
    boolean newPendingRegistration = false;
    if (created
        && requireApproval
        && localRoles.stream().noneMatch(r -> Roles.ADMIN.equalsIgnoreCase(r.getCode()))) {
      user.setApprovalStatus(ApprovalStatus.PENDING);
      newPendingRegistration = true;
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

  /**
   * Updates a user's editable attributes (rank, description, displayName, joinDate). Optimistic-
   * lock check is explicit when {@code version} is non-null; admins can override by passing {@code
   * null}.
   *
   * <p>Rank validation enforces the role-based range: officers get 1–12, squadron members get
   * 13–20. Out-of-range rank throws {@link IllegalArgumentException} → 400. {@code joinDate} can be
   * explicitly set to {@code null} to clear the field; the other nullable fields are only updated
   * when supplied.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   * @throws IllegalArgumentException when the rank is outside the role-permitted range
   */
  @Transactional
  @NotNull
  public User updateUserAttributes(
      @NotNull UUID id,
      @Nullable Integer rank,
      @Nullable String description,
      @Nullable String displayName,
      @Nullable Long version,
      @Nullable java.time.LocalDate joinDate) {
    User user =
        userRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));

    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);

    if (rank != null) {
      boolean isOfficer =
          user.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase(Roles.OFFICER));
      boolean isSquadronMember =
          user.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase(Roles.KRT_MEMBER));

      if (isOfficer) {
        if (rank < 1 || rank > 12) {
          throw new IllegalArgumentException("Officers can only have rank 1-12");
        }
      } else if (isSquadronMember) {
        if (rank < 13 || rank > 20) {
          throw new IllegalArgumentException("Squadron members can only have rank 13-20");
        }
      }
      user.setRank(rank);
    }
    if (description != null) {
      user.setDescription(description);
    }
    if (displayName != null) {
      user.setDisplayName(displayName.isBlank() ? null : displayName);
    }
    // joinDate can be explicitly set to null (clear the date)
    user.setJoinDate(joinDate);
    return userRepository.save(user);
  }

  /**
   * Narrower update than {@link #updateUserAttributes}: covers only the profile-page editable
   * subset (description + displayName). Used by the user's own profile-edit form so a regular user
   * cannot bump their rank.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public User updateUserDescription(
      @NotNull UUID id,
      @Nullable String description,
      @Nullable String displayName,
      @Nullable Long version) {
    User user =
        userRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);
    if (description != null) {
      user.setDescription(description);
    }
    if (displayName != null) {
      user.setDisplayName(displayName.isBlank() ? null : displayName);
    }
    // saveAndFlush so the bumped @Version is in the response — the profile page writes the returned
    // version back onto every hidden version input in place via syncAllVersions (no reload), so a
    // stale save() version 409s the next consecutive profile edit.
    return userRepository.saveAndFlush(user);
  }

  /**
   * Sets the calling user's personal default payout preference — the value pre-filled into the
   * per-participant {@code payoutPreference} at mission sign-up ({@link
   * MissionService#addParticipant}). Mirrors {@link #updateUserDescription}'s optimistic-lock
   * contract: a stale {@code version} surfaces as a 409. Unlike the description fields the
   * preference is set unconditionally (the request DTO enforces {@code @NotNull}), so this never
   * silently no-ops. Changing it is forward-only — it does not rewrite existing {@code
   * MissionParticipant} rows.
   *
   * @param id the calling user's id, resolved from the JWT (never from the URL); never {@code
   *     null}.
   * @param preference the new default payout preference; never {@code null}.
   * @param version the optimistic-lock version the caller last read; {@code null} bypasses the
   *     check, matching {@link #updateUserDescription}.
   * @return the persisted user with the updated default and bumped version.
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown.
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale.
   */
  @Transactional
  public User updateUserDefaultPayoutPreference(
      @NotNull UUID id, @NotNull PayoutPreference preference, @Nullable Long version) {
    User user =
        userRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);
    user.setDefaultPayoutPreference(preference);
    // saveAndFlush so the bumped @Version reaches the response — the profile payout-preference
    // dropdown writes the returned version back in place via syncAllVersions (no reload), so a
    // stale save version 409s the next consecutive change.
    return userRepository.saveAndFlush(user);
  }

  /**
   * Sets the calling user's opt-in flag for global blueprint sharing. When {@code true}, the user's
   * owned blueprints are counted in the leadership blueprint-availability overview and the
   * item-order blueprint-coverage view for <em>every</em> org unit, not only the ones they belong
   * to (REQ-INV-018). Mirrors {@link #updateUserDefaultPayoutPreference}'s optimistic-lock
   * contract: a stale {@code version} surfaces as a 409. The flag is set unconditionally; the
   * widening is read-only and the viewer-access gates are unchanged.
   *
   * @param id the calling user's id, resolved from the JWT (never from the URL); never {@code
   *     null}.
   * @param shareBlueprintsGlobally the new opt-in value.
   * @param version the optimistic-lock version the caller last read; {@code null} bypasses the
   *     check, matching {@link #updateUserDefaultPayoutPreference}.
   * @return the persisted user with the updated flag and bumped version.
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown.
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale.
   */
  @Transactional
  public User updateUserShareBlueprintsGlobally(
      @NotNull UUID id, boolean shareBlueprintsGlobally, @Nullable Long version) {
    User user =
        userRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);
    user.setShareBlueprintsGlobally(shareBlueprintsGlobally);
    // saveAndFlush so the bumped @Version reaches the response — the profile blueprint-sharing
    // toggle writes the returned version back in place via syncAllVersions (no reload), so a stale
    // save version 409s the next consecutive change.
    return userRepository.saveAndFlush(user);
  }

  /**
   * Records that the user has read the given announcement (clears the unread badge on the home
   * page).
   *
   * @param id user id
   * @param announcementId announcement they read
   * @return the persisted user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user id is
   *     unknown
   */
  @Transactional
  public User updateReadAnnouncement(@NotNull UUID id, @NotNull UUID announcementId) {
    User user =
        userRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));
    user.setLastReadAnnouncementId(announcementId);
    return userRepository.save(user);
  }

  /**
   * Returns all users sorted case-insensitively by username, scoped to the caller's squadron
   * context. Admin in "all squadrons" mode receives the cross-staffel list; everyone else sees the
   * members of <em>every</em> Staffel they belong to (REQ-ORG-017 — up to two), plus unassigned
   * admins/guests. Reads {@link OwnerScopeService#currentUserListScopeSquadronIds()} once per call.
   *
   * @return scoped user list, case-insensitively sorted by username
   */
  public List<User> findAll() {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.findAllScopedList(
        scope, Sort.by(Sort.Order.asc("username").ignoreCase()));
  }

  /**
   * Returns paged user list, squadron-scoped (see {@link #findAll()}).
   *
   * @param pageable page request
   * @return scoped paged user list
   */
  public Page<User> findAll(@NotNull Pageable pageable) {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.findAllScoped(scope, pageable);
  }

  /**
   * Returns paged squadron members eligible to be evaluated in the promotion system, scoped to the
   * caller's squadron context and excluding both admins and officers — the promotion system
   * assesses only the simple members of a squadron (issue #817). An Officer sees the ordinary
   * members of <em>every</em> Staffel they belong to (REQ-ORG-017 — up to two); an Admin in "all
   * squadrons" mode sees every squadron's ordinary members; an Admin/officer with the sidebar
   * switcher pinned to one Staffel sees that Staffel's ordinary members. Admins and officers
   * themselves are never returned — admins are squadron-less by design, and officers run the
   * Bewertungsverwaltung rather than being its subject. Delegates the filter to {@link
   * UserRepository#findEvaluatableMembers(java.util.Collection, Pageable)}.
   *
   * @param pageable page request
   * @return paged evaluatable members (squadron-scoped, admin- and officer-free)
   */
  @NotNull
  public Page<User> findEvaluatableMembers(@NotNull Pageable pageable) {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.findEvaluatableMembers(scope, pageable);
  }

  /**
   * Returns lightweight reference projection used by typeaheads (id + username + displayName).
   * Squadron-scoped via {@link OwnerScopeService#currentUserListScopeSquadronIds()} — a non-admin
   * sees the members of every Staffel they belong to in pickers (REQ-ORG-017).
   *
   * @return lightweight reference projection used by typeaheads
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto> findAllReference() {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.findAllReferenceScoped(scope);
  }

  /**
   * Unpaged username/displayName substring search, squadron-scoped (the union of the caller's
   * Staffeln, REQ-ORG-017).
   *
   * @param query free-text filter
   * @return matching users in the caller's squadron context
   */
  public List<User> searchByUsername(@NotNull String query) {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.searchScopedList(query, scope);
  }

  /**
   * Paged username/displayName substring search, squadron-scoped (the union of the caller's
   * Staffeln, REQ-ORG-017).
   *
   * @param query free-text filter
   * @param pageable page request
   * @return matching users in the caller's squadron context
   */
  public Page<User> searchByUsername(@NotNull String query, @NotNull Pageable pageable) {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.searchScoped(query, scope, pageable);
  }

  /**
   * Returns the user.
   *
   * @param id user primary key
   * @return the user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  public User findById(@NotNull UUID id) {
    return userRepository
        .findById(id)
        .orElseThrow(
            () ->
                new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                    "User not found"));
  }

  /**
   * Looks up the calling user via the JWT in the current {@link Authentication}. Returns empty for
   * unauthenticated callers (guests). The single canonical accessor for "who is calling" — other
   * services delegate here instead of reaching for {@code SecurityContextHolder} (the architectural
   * seam enforced by ArchUnit).
   *
   * @return the calling user, or empty for unauthenticated requests
   */
  public Optional<User> getCurrentUser() {
    Authentication auth = authHelperService.rawAuthentication();
    if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
      return Optional.empty();
    }
    UUID userId = getUserIdFromJwt(jwt);
    return userRepository.findById(userId);
  }

  /**
   * SPEZIALKOMMANDO_PLAN.md §7.4 single-POST membership-delta orchestrator. Applies the supplied
   * Staffel + SK change set in one transaction so the admin member-edit page can persist every
   * change with one Save button click.
   *
   * <p>Resolution order matters and is fixed by this method:
   *
   * <ol>
   *   <li>Staffel side first — the {@code staffeln} list (the desired complete Staffel membership
   *       set, REQ-ORG-017 allows up to two) is reconciled against the current state by {@link
   *       OrgUnitMembershipService#reconcileStaffelMemberships}: squadrons are added / removed and
   *       per-squadron flags patched in one pass. A {@code null} list leaves the Staffel side
   *       untouched; a non-null (possibly empty) list is the authoritative target.
   *   <li>SK side second, in the order the client sent them. ADD adopts initial flags inline (no
   *       second {@code save}); REMOVE deletes by composite PK; PATCH validates the per-row
   *       {@code @Version} before writing. {@code is_lead} is intentionally not part of this
   *       payload — Lead toggles stay isolated in the SK detail page workflow per Plan D2 so the
   *       audit trail keeps clear per-toggle attribution.
   * </ol>
   *
   * <p>If any step throws (NotFoundException on a stale id, OptimisticLockingFailureException on a
   * stale version, DuplicateEntityException on an ADD for an existing membership,
   * BadRequestException on a Staffel-cardinality / leadership conflict) the entire transaction
   * rolls back — partial application is not exposed.
   *
   * @param userId the user whose memberships to mutate; never {@code null}.
   * @param delta the delta to apply; never {@code null}, but both halves may be {@code null} /
   *     empty (no-op delta is allowed and just returns the current state).
   * @return the user's complete post-write membership list (Staffel + every SK), never {@code
   *     null}.
   * @throws java.util.NoSuchElementException when the user does not exist.
   */
  @Transactional
  public java.util.List<de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership>
      applyMembershipDelta(
          UUID userId,
          de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest delta) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NoSuchElementException("User not found with id: " + userId));

    if (delta.staffeln() != null) {
      orgUnitMembershipService.reconcileStaffelMemberships(user, delta.staffeln());
    }
    if (delta.specialCommands() != null) {
      for (de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest
              .SpecialCommandChange
          sk : delta.specialCommands()) {
        applySpecialCommandChange(userId, sk);
      }
    }
    return orgUnitMembershipService.findAllMembershipsForUser(userId);
  }

  /**
   * SK-side half of {@link #applyMembershipDelta}. Dispatches on the action discriminator and
   * forwards to the existing membership-service primitives. ADD adopts initial flags via a single
   * {@code save} on the freshly-created row (avoiding the intra-transaction double-version-bump
   * trap from CLAUDE.md "Concurrency" section); PATCH delegates to {@link
   * OrgUnitMembershipService#patchFlags} which does its own optimistic-lock check; REMOVE delegates
   * to {@link OrgUnitMembershipService#removeMember}.
   *
   * @param userId target user id.
   * @param change the SK-side change record.
   */
  private void applySpecialCommandChange(
      UUID userId,
      de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.SpecialCommandChange
          change) {
    switch (change.action()) {
      case ADD -> {
        de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership fresh =
            orgUnitMembershipService.addMember(change.orgUnitId(), userId);
        if (Boolean.TRUE.equals(change.isLogistician())
            || Boolean.TRUE.equals(change.isMissionManager())) {
          // The freshly-created row has version 0 and is still managed in this transaction.
          // Mutate it in place; Hibernate dirty-checking flushes the second update on commit
          // without a second explicit save call (avoiding the intra-transaction @Version race
          // documented in CLAUDE.md).
          if (Boolean.TRUE.equals(change.isLogistician())) {
            fresh.setLogistician(true);
          }
          if (Boolean.TRUE.equals(change.isMissionManager())) {
            fresh.setMissionManager(true);
          }
        }
      }
      case REMOVE -> orgUnitMembershipService.removeMember(change.orgUnitId(), userId);
      case PATCH ->
          orgUnitMembershipService.patchFlags(
              change.orgUnitId(),
              userId,
              new de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest(
                  change.isLogistician(), change.isMissionManager(), change.version()));
      default ->
          throw new IllegalArgumentException(
              "Unsupported SpecialCommandChange action: " + change.action());
    }
  }

  /**
   * Deletes a user account along with all owned data (ships, inventory, refinery orders, mission
   * memberships) and its Discord-approval audit trail (epic #720 / V173). Used by admins to remove
   * ex-members; only a user no longer present in Keycloak may be deleted. The cascade is explicit
   * (per-table delete / reassign calls) so the order matches the FK constraints; auto-cascading
   * would surface confusing FK errors when the table order changes. References whose FK declares
   * {@code ON DELETE SET NULL} / {@code CASCADE} (bank tables, org-unit membership, org-chart, …)
   * are left to the database; the no-{@code ON DELETE} references — the owner columns (reassigned
   * to an admin) and the V173 approval audit (cleaned up here) — must be resolved explicitly first.
   *
   * <p>Every {@code app_user} foreign key that carries no {@code ON DELETE} clause is resolved here
   * before the final {@link UserRepository#delete} — reassigned to the fallback admin (owned
   * aggregates: inventory, ships, refinery orders, missions and the {@code mission_ownership}
   * companion) or unlinked (managers, job-order assignees, mission participants, and the audit-only
   * {@code material_claim.claimed_by_user_id} stamp). The {@code mission_ownership.owner_id}
   * reassignment must stay paired with {@code missionRepository.updateOwner}: the parent mission
   * survives the delete (its owner having been moved to the admin), so the {@code ON DELETE
   * CASCADE} on {@code mission_id} never fires to clear the row, and the FK-less {@code owner_id}
   * would otherwise dangle and FK-fail (SQLSTATE 23503).
   *
   * @param userId user to delete
   * @throws NoSuchElementException when the user id is unknown
   * @throws IllegalStateException when the user is still present in Keycloak, or when no other
   *     admin exists to receive the reassigned owner references
   */
  @Transactional
  public void deleteUser(UUID userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NoSuchElementException("User not found"));

    if (user.isInKeycloak()) {
      throw new IllegalStateException("Cannot delete user that is still in Keycloak");
    }

    User admin =
        userRepository.findAllAdmins().stream()
            .filter(u -> !u.getId().equals(userId))
            .findFirst()
            .orElseGet(
                () ->
                    getCurrentUser()
                        .filter(u -> !u.getId().equals(userId))
                        .filter(
                            u ->
                                u.getRoles().stream()
                                    .anyMatch(r -> r.getName().equalsIgnoreCase(Roles.ADMIN)))
                        .orElseThrow(
                            () ->
                                new IllegalStateException("No admin user found to reassign data")));

    // Reassign mandatory fields
    int inventoryReassigned = inventoryItemRepository.updateOwner(user, admin);
    shipRepository.updateOwner(user, admin);
    int refineryReassigned = refineryOrderRepository.updateOwner(user, admin);
    missionRepository.updateOwner(user, admin);
    // System/cascade audit: a deleted user's warehouse rows and refinery orders are bulk-reassigned
    // to the fallback admin. Summary events only (set-based UPDATEs expose no per-row ids); the
    // deleted user is the target, the acting admin is the actor. Recorded only when rows actually
    // moved and carrying the affected-row count, mirroring InventoryOrgUnitReconciler's >0 guard.
    if (inventoryReassigned > 0) {
      auditService.record(
          AuditEventType.INVENTORY_OWNER_REASSIGNED,
          null,
          null,
          userId,
          AuditDetails.of("reason", "user-deletion")
              .with("rows", inventoryReassigned)
              .with("fromUser", userId)
              .with("toAdmin", admin.getId()));
    }
    if (refineryReassigned > 0) {
      auditService.record(
          AuditEventType.REFINERY_ORDERS_REASSIGNED,
          null,
          null,
          userId,
          AuditDetails.of("reason", "user-deletion")
              .with("rows", refineryReassigned)
              .with("fromUser", userId)
              .with("toAdmin", admin.getId()));
    }
    // The mission_ownership companion (1:1 with mission, owner_id FK has no ON DELETE clause) must
    // be reassigned in lock-step with mission.owner above; otherwise its dangling owner_id FK-fails
    // (23503) on the final delete, because the parent mission survives so its mission_id cascade
    // never clears the row.
    missionOwnershipRepository.updateOwner(user, admin);

    // Remove ManyToMany and nullable references
    missionRepository.removeManager(userId);
    jobOrderRepository.removeAssignee(userId);
    missionParticipantRepository.unlinkUser(userId);
    // material_claim.claimed_by_user_id (V131) is an audit-only FK with no ON DELETE clause; null
    // it
    // so an ex-logistician who ever filed a claim does not FK-fail (23503) on the delete below.
    materialClaimRepository.unlinkClaimedByUser(userId);

    // Discord-approval audit cleanup (epic #720 / REQ-SEC-017, V173). These three references into
    // app_user declare no ON DELETE clause (Postgres NO ACTION), so without explicit cleanup a
    // decided-on or deciding account cannot be hard-deleted. This is the reported regression: an
    // approved, since-removed Discord registration could not be deleted because of FK
    // user_approval_event_user_id_fkey (409). The subject's own audit rows are deleted (user_id is
    // NOT NULL, so they cannot be orphaned); rows the deleted account decided keep the audit but
    // lose their now-gone decider link; and the denormalised app_user.approved_by_id back-pointer
    // on other users is nulled. The approval audit of OTHER users survives. Must run before the
    // app_user delete below so the FK is satisfied at flush.
    userApprovalEventRepository.deleteByUserId(userId);
    userApprovalEventRepository.clearDecidedBy(userId);
    userRepository.clearApprovedBy(userId);

    // Delete the user
    userRepository.delete(user);
    log.info("User {} deleted and references reassigned to admin {}", userId, admin.getId());
  }

  /**
   * Returns the registrations awaiting an admin decision (status {@link ApprovalStatus#PENDING}),
   * oldest first. Admin-only at the controller boundary; not squadron-scoped because a pending user
   * has no org unit yet.
   *
   * @return the pending registrations, oldest registration first
   */
  @NotNull
  public List<User> findPendingRegistrations() {
    return userRepository.findByApprovalStatusOrderByCreatedAtAsc(ApprovalStatus.PENDING);
  }

  /**
   * Approves a pending registration: moves it to {@link ApprovalStatus#ACTIVE}, stamps the
   * approving admin + time, and writes an audit row. The user's Basetool roles/units stay manually
   * managed (Track 1) — approval grants no roles by itself. Optimistic-locking via {@code version}.
   *
   * @param userId the registration to approve
   * @param version the optimistic-lock version echoed back from the admin queue; {@code null}
   *     bypasses the check
   * @param adminId the approving admin's id (for the audit row)
   * @return the now-active user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  @NotNull
  public User approveUser(@NotNull UUID userId, @Nullable Long version, @NotNull UUID adminId) {
    User user = decide(userId, version, ApprovalStatus.ACTIVE, adminId);
    userApprovalEventRepository.save(
        new UserApprovalEvent(userId, ApprovalDecision.APPROVED, null, adminId));
    // REQ-NOTIF-014: notify the user by e-mail after commit. Best-effort and off-thread — the
    // after-commit listener swallows any mail failure so it never affects this approval.
    eventPublisher.publishEvent(
        new UserApprovalDecidedEvent(userId, true, user.getEmail(), user.getEffectiveName(), null));
    return user;
  }

  /**
   * Rejects a pending registration: moves it to {@link ApprovalStatus#REJECTED} (the user keeps no
   * authorities and is routed to the waiting page), stamps the deciding admin + time, and writes an
   * audit row carrying the optional reason. Optimistic-locking via {@code version}.
   *
   * @param userId the registration to reject
   * @param reason optional free-text reason recorded in the audit; may be {@code null}
   * @param version the optimistic-lock version echoed back from the admin queue; {@code null}
   *     bypasses the check
   * @param adminId the deciding admin's id (for the audit row)
   * @return the now-rejected user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  @NotNull
  public User rejectUser(
      @NotNull UUID userId,
      @Nullable String reason,
      @Nullable Long version,
      @NotNull UUID adminId) {
    User user = decide(userId, version, ApprovalStatus.REJECTED, adminId);
    userApprovalEventRepository.save(
        new UserApprovalEvent(userId, ApprovalDecision.REJECTED, reason, adminId));
    // REQ-NOTIF-014: notify the user by e-mail after commit, including the admin's reason. Best-
    // effort and off-thread — the after-commit listener swallows any mail failure.
    eventPublisher.publishEvent(
        new UserApprovalDecidedEvent(
            userId, false, user.getEmail(), user.getEffectiveName(), reason));
    return user;
  }

  /**
   * Shared approve/reject body: loads the user, checks the optimistic-lock version, stamps the new
   * status + deciding admin + time, and persists (saveAndFlush so the bumped {@code @Version}
   * reaches the response for the no-reload admin queue).
   *
   * @param userId the registration to decide
   * @param version the optimistic-lock version; {@code null} bypasses the check
   * @param newStatus the target status ({@link ApprovalStatus#ACTIVE} or {@link
   *     ApprovalStatus#REJECTED})
   * @param adminId the deciding admin's id
   * @return the persisted user
   */
  private User decide(UUID userId, @Nullable Long version, ApprovalStatus newStatus, UUID adminId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, userId);
    // State-transition guard (PR review #3): only a still-PENDING registration may be approved or
    // rejected. Acting on an already-ACTIVE member would silently strip their authorities and trap
    // them on the waiting page; an already-REJECTED row is a stale double-action. Either is a 409.
    if (user.getApprovalStatus() != ApprovalStatus.PENDING) {
      throw new BusinessConflictException(
          "Only a pending registration can be decided; current status is "
              + user.getApprovalStatus());
    }
    user.setApprovalStatus(newStatus);
    user.setApprovedAt(Instant.now());
    user.setApprovedById(adminId);
    return userRepository.saveAndFlush(user);
  }
}
