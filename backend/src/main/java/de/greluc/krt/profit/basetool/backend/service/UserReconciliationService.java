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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

  /**
   * Display name of the local fallback role every account lands on when not a single Keycloak role
   * name matched the local catalog. Matched case-insensitively, like every other role lookup here.
   */
  private static final String GUEST_FALLBACK_ROLE_NAME = "Guest";

  /**
   * How many accounts may be re-mapped ONTO the {@code Guest} fallback in a single sync run before
   * the run summary escalates from INFO to WARN. A handful is ordinary (a leaver whose realm roles
   * were stripped); more than this in one run is the fingerprint of a realm-side role rename, which
   * silently demotes every holder of the renamed role at once — the failure this aggregate exists
   * to make visible. Deliberately low: the run summary is emitted once per sync, so a WARN here is
   * not a flood vector.
   */
  private static final int GUEST_FALLBACK_WARN_THRESHOLD = 3;

  /** Accounts whose persisted role set actually changed in the current Admin-API sync run. */
  private final AtomicInteger roleChangedAccounts = new AtomicInteger();

  /**
   * Accounts whose role change in the current run was a demotion onto the {@code Guest} fallback —
   * i.e. NONE of their Keycloak role names resolved to a local role.
   */
  private final AtomicInteger guestFallbackAccounts = new AtomicInteger();

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

  /** Counts callsign collisions, the case the removed name-matching fallback used to hide. */
  private final MeterRegistry meterRegistry;

  /**
   * Reconciles the local user record with the supplied JWT — creates the row on first login,
   * updates username / email / displayName / roles when they have changed. Called by {@link
   * CustomJwtGrantedAuthoritiesConverter} on every authentication.
   *
   * <p>The lookup is <b>by id only</b>. A subject that matches no row is a new registration, never
   * an existing account found by name (ADR-0142 point 5) — a Keycloak username is neither immutable
   * nor unique after a deletion, so matching on it let a callsign decide which account a token
   * acted as. A row holding the same username under a different id is logged and counted; the admin
   * queue shows it as a collision, and merging the two is an explicit admin action. Roles default
   * to "Guest" when the JWT carries none so an unconfigured Keycloak doesn't lock everyone out.
   *
   * <p>Roles are the one field this does not always write. A client whose realm-role claim is
   * deliberately incomplete (REQ-SEC-036) leaves the stored set untouched, and the returned {@link
   * ReconciledUser#effectiveRoles()} then carries the token's roles rather than the row's -- see
   * the role branch below for why replacing the set from such a claim is a data loss rather than a
   * narrowing.
   *
   * @param jwt validated JWT from the resource server
   * @return the managed (created or updated) user together with the roles authorising this request
   */
  @Transactional
  @NotNull
  public ReconciledUser syncUser(@NotNull Jwt jwt) {
    final UUID finalUserId = userService.getUserIdFromJwt(jwt);
    String username = jwt.getClaimAsString("preferred_username");

    // A Discord federated login is recognised ONLY by the Keycloak subject / discord_user_id link,
    // never by username (REQ-SEC-017 / REQ-DATA-006). That used to need an explicit carve-out here,
    // because the name-matching fallback below would otherwise have linked a brokered -- i.e.
    // attacker-influenced -- Discord username onto a pre-existing, possibly privileged, already
    // ACTIVE row, bypassing the PENDING gate. The fallback is gone (#1639), so the rule the
    // carve-out enforced now holds for every login path by construction rather than by exception.
    final String discordUserId = jwt.getClaimAsString("discord_user_id");
    final boolean viaDiscord = discordUserId != null && !discordUserId.isBlank();

    Optional<User> existingUser = userRepository.findById(finalUserId);
    if (existingUser.isEmpty() && username != null) {
      // A token whose subject matches no row NEVER adopts one found by name (ADR-0142 point 5,
      // #1639). Until this release it did, and the consequences were both silent: from that login
      // on, app_user.id was not the caller's subject for that one row -- the invariant 39 foreign
      // keys, the frontend's own comparisons, /users/me and the audit trail all rest on -- and a
      // Keycloak username, which is neither immutable nor unique after a deletion, decided which
      // account a token acted as. A recreated account with a previous member's callsign inherited
      // their inventory, their bank grants and their notifications.
      //
      // The caller is provisioned as a brand-new registration below instead. That lands PENDING
      // and notifies every admin (REQ-SEC-017, REQ-NOTIF-012), so the collision surfaces as a
      // decision rather than as an inheritance. It is still worth a log line -- this is exactly
      // the case the fallback used to hide.
      List<UUID> sameCallsign = userRepository.findIdsByUsername(username);
      if (!sameCallsign.isEmpty()) {
        meterRegistry.counter(MetricNames.USER_CALLSIGN_COLLISIONS).increment();
        // REQ-OBS-004: never log names/handles. preferred_username can be a real callsign that the
        // PiiMasker cannot scrub (it only matches JWTs, e-mails and token keywords), so log the
        // row ids and omit the value.
        log.warn(
            "Callsign collision: subject {} is unknown, but {} existing account(s) hold the same"
                + " preferred_username (value omitted, PII), e.g. {}. Provisioning a NEW pending"
                + " registration; nothing is inherited. An admin merges the two explicitly.",
            finalUserId,
            sameCallsign.size(),
            sameCallsign.getFirst());
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

    // A token exists only for an enabled account, so a successful authentication is itself proof of
    // the flag the roster sync mirrors — which makes re-activation take effect at the member's next
    // login rather than waiting for the next sync pass.
    if (!user.isEnabledInKeycloak()) {
      user.setEnabledInKeycloak(true);
      changed = true;
    }

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

    // Sync Roles.
    //
    // The token's claim is authoritative for MOST clients and for none of them unconditionally.
    // A client provisioned with `fullScopeAllowed: false` and a narrowed scope mints a token that
    // describes a deliberately smaller member than the real one: the mobile client withholds
    // `Admin` on purpose (REQ-SEC-035). Because this write REPLACES the stored set rather than
    // merging into it, persisting such a claim lets whichever client a member happened to use last
    // decide what the database says they are -- an administrator who opens the app would have
    // `Admin` removed from their row, and every consumer that reads roles outside a request
    // (scheduled tasks, notification targeting, roster views) would see the narrower set until
    // their next web request put it back.
    //
    // So for a partial-scope client the stored set is left ALONE and the token's roles are used for
    // this request only. The stored set stays maintained by the clients whose claim is complete and
    // by the daily Admin-API pass (`syncUser(KeycloakUserDto)`), which reads the realm directly and
    // is unaffected by any client's scope -- so the row still converges even for a member who only
    // ever uses the app. REQ-SEC-036.
    Set<String> keycloakRoles = extractRolesFromJwt(jwt);
    Set<Role> localRoles = mapRoles(keycloakRoles);
    final boolean roleClaimIsPartial =
        partialRoleScopeProperties.isPartialRoleScopeClient(jwt.getClaimAsString("azp"));

    // A brand-new row is an exception in the safe direction: there is no stored set to protect, and
    // the alternative is persisting a member with no roles at all. The next complete-claim login or
    // Admin-API pass widens it.
    final boolean mayPersistRoles = !roleClaimIsPartial || created;

    if (mayPersistRoles) {
      if (!user.getRoles().equals(localRoles)) {
        user.setRoles(localRoles);
        changed = true;
      }
    } else if (!user.getRoles().equals(localRoles)) {
      // REQ-OBS-004: the id, never the username -- preferred_username can be a real callsign.
      log.debug(
          "Role claim from partial-scope client not persisted for user {} (stored {} roles, token"
              + " carried {})",
          user.getId(),
          user.getRoles().size(),
          localRoles.size());
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
      changed = true;
    }

    // The Admin API has always returned this; it used to be dropped on the floor. Persisted since
    // V230 because a named subject does not expire the way a token does (ADR-0129): without it,
    // deactivating a member in Keycloak left their extractor sending indefinitely. A null `enabled`
    // is read as TRUE — an absent field must not lock out a member base.
    boolean enabled = !Boolean.FALSE.equals(dto.enabled());
    if (user.isEnabledInKeycloak() != enabled) {
      user.setEnabledInKeycloak(enabled);
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

    // Sync Roles. The mapping is tracked (not just applied) on this path: a realm-side role rename
    // makes every holder's names stop matching, which silently re-maps them all onto the Guest
    // fallback overnight — a privilege change with no signal anywhere, because the fetch itself
    // succeeded and the item count stayed the same. The tallies feed the per-run aggregate written
    // by logRoleSyncSummary(); the per-account line stays at DEBUG and carries the sub UUID only.
    RoleMapping mapping = mapRolesTracked(dto.roles());
    droppedRoleNames.addAndGet(mapping.droppedNames());
    Set<Role> localRoles = mapping.roles();
    if (!user.getRoles().equals(localRoles)) {
      user.setRoles(localRoles);
      changed = true;
      roleChangedAccounts.incrementAndGet();
      if (mapping.guestFallback()) {
        guestFallbackAccounts.incrementAndGet();
      }
      log.debug(
          "Role set changed for user {} (guestFallback={}, unmatchedKeycloakRoleNames={})",
          user.getId(),
          mapping.guestFallback(),
          mapping.droppedNames());
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
   * <p>Returns the affected-row count rather than {@code void} so the caller can report how many
   * accounts vanished upstream in this run: soft-deleting members is a consequential write, and it
   * used to happen with no number attached anywhere (the count was discarded at the JPA level).
   *
   * @param currentIds the set of user ids currently present in Keycloak
   * @return the number of users newly flagged as missing; {@code 0} when {@code currentIds} is
   *     empty (the skip case) or when nobody disappeared
   */
  @Transactional
  public int markMissingUsers(Collection<UUID> currentIds) {
    if (currentIds.isEmpty()) {
      return 0;
    }
    return userRepository.markMissingUsers(currentIds);
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
   * Writes the per-run role-mapping aggregate and resets the tallies for the next run, so the
   * scheduled reconciliation leaves exactly one line stating how many accounts had their roles
   * rewritten — the signal that was missing when a realm-side role rename re-mapped every holder
   * onto {@code Guest} overnight with no trace at all.
   *
   * <p>Counts only, never handles: REQ-OBS-004 forbids the {@code preferred_username} / callsign /
   * e-mail / Discord snowflake, and the affected subs are already available per account at DEBUG.
   * The level is INFO for an ordinary run and WARN once more than {@link
   * #GUEST_FALLBACK_WARN_THRESHOLD} accounts were demoted onto the fallback in one run, which is
   * what a renamed or deleted realm role looks like from here.
   *
   * <p>Called by {@link UserSyncService} at the end of a run. The tallies are only fed by the
   * Admin-API path ({@link #syncUser(KeycloakUserDto)}), never by the per-login JWT path, so an
   * interactive login cannot inflate them; two overlapping runs (scheduled + admin-triggered) would
   * share one tally, an accepted and harmless imprecision for a summary line.
   */
  public void logRoleSyncSummary() {
    int changed = roleChangedAccounts.getAndSet(0);
    int fallbacks = guestFallbackAccounts.getAndSet(0);
    int dropped = droppedRoleNames.getAndSet(0);
    if (fallbacks > GUEST_FALLBACK_WARN_THRESHOLD) {
      log.warn(
          "User sync role mapping: {} accounts changed roles, {} of them re-mapped onto the Guest"
              + " fallback, {} Keycloak role names matched no local role — check for a renamed or"
              + " deleted realm role.",
          changed,
          fallbacks,
          dropped);
      return;
    }
    log.info(
        "User sync role mapping: {} accounts changed roles ({} onto the Guest fallback), {}"
            + " Keycloak role names matched no local role.",
        changed,
        fallbacks,
        dropped);
  }

  /**
   * Maps Keycloak realm-role names to the local {@link Role} catalog, case-insensitively; unmatched
   * names are dropped. Falls back to the {@code Guest} role when none match (or the input is {@code
   * null}) so an unconfigured / mismatched Keycloak never leaves a user role-less. Thin wrapper
   * over {@link #mapRolesTracked(Collection)} for the per-login JWT path, which deliberately does
   * not feed the sync-run tallies.
   *
   * @param roleNames the Keycloak role names to map, possibly {@code null}
   * @return the matched local roles, or {@code Guest} as a singleton fallback; never {@code null}
   */
  private Set<Role> mapRoles(Collection<String> roleNames) {
    return mapRolesTracked(roleNames).roles();
  }

  /**
   * Maps Keycloak realm-role names onto the local catalog exactly like {@link #mapRoles} but also
   * reports HOW the mapping went, so the caller can tell a legitimate role change apart from the
   * silent catch-all: a name that matches nothing is dropped, and an account for which nothing
   * matched at all is handed the {@code Guest} fallback rather than being left role-less.
   *
   * @param roleNames the Keycloak role names to map, possibly {@code null}
   * @return the mapped roles plus the fallback flag and the number of dropped names
   */
  @NotNull
  private RoleMapping mapRolesTracked(@Nullable Collection<String> roleNames) {
    Set<Role> localRoles = new HashSet<>();
    int dropped = 0;
    if (roleNames != null) {
      for (String roleName : roleNames) {
        Optional<Role> match = roleRepository.findByNameIgnoreCase(roleName);
        if (match.isPresent()) {
          localRoles.add(match.get());
        } else {
          dropped++;
        }
      }
    }

    boolean guestFallback = localRoles.isEmpty();
    if (guestFallback) {
      roleRepository.findByNameIgnoreCase(GUEST_FALLBACK_ROLE_NAME).ifPresent(localRoles::add);
    }
    return new RoleMapping(localRoles, guestFallback, dropped);
  }

  /**
   * Outcome of one role-name mapping: the resolved local roles plus the two facts the raw set
   * cannot express — whether the result is the {@code Guest} catch-all (nothing matched) and how
   * many supplied names were silently dropped.
   *
   * @param roles the resolved local roles (the {@code Guest} singleton when {@code guestFallback})
   * @param guestFallback {@code true} when no supplied name resolved and the fallback was applied
   * @param droppedNames how many supplied Keycloak role names matched no local role
   */
  private record RoleMapping(@NotNull Set<Role> roles, boolean guestFallback, int droppedNames) {}

  /**
   * What one authentication reconciled: the managed row, and the roles that authorise <em>this</em>
   * request.
   *
   * <p>The two are the same set for every client whose token carries the member's whole role list,
   * and deliberately differ for a partial-scope one (REQ-SEC-036): there the row keeps the roles it
   * had and {@link #effectiveRoles()} carries only what the token actually presented. Splitting
   * them is what lets the mobile client be denied admin authority without its narrower view of the
   * member being written down as the truth.
   *
   * @param user the created or updated {@code app_user} row; never {@code null}
   * @param effectiveRoles the roles this request is authorised with -- the token's, mapped onto the
   *     local catalogue, with the {@code Guest} fallback already applied; never {@code null}
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
