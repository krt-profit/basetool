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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.event.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.PartialRoleScopeProperties;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link UserReconciliationService} — the Keycloak&nbsp;-&gt;&nbsp;local sync seam
 * extracted out of {@code UserService} (audit Thema&nbsp;7, #1252): {@link
 * UserReconciliationService#syncUser(Jwt)} (the per-login hot path), {@link
 * UserReconciliationService#syncUser(KeycloakUserDto)} (the scheduled Admin-API sync), {@link
 * UserReconciliationService#markMissingUsers}, the role mapping ({@link
 * UserReconciliationService#extractRolesFromJwt} + the Guest fallback) and the sync-input catalogs
 * ({@link UserReconciliationService#getMappableRoleNames} / {@link
 * UserReconciliationService#getKnownDiscordLinkedUserIds}).
 *
 * <p>The subject is a real {@link UserReconciliationService} wired to a real {@link
 * UserRegistrationService} (so the shared fail-safe PENDING stamping runs for real) and a mock
 * {@link UserService} whose {@code getUserIdFromJwt} is stubbed to parse the token subject (the
 * identity seam stays in {@code UserService}).
 */
@ExtendWith(MockitoExtension.class)
class UserReconciliationServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private DefaultBlueprintProvisioningService defaultBlueprintProvisioningService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private UserApprovalEventRepository userApprovalEventRepository;
  @Mock private UserService userService;
  @Mock private KeycloakService keycloakService;
  @Mock private UserDeletionService userDeletionService;
  @Mock private ObjectProvider<UserRegistrationService> selfProvider;

  private UserRegistrationService userRegistrationService;
  private UserReconciliationService userReconciliationService;

  /**
   * A real instance rather than a mock: it holds a list and answers one pure predicate, so stubbing
   * it would only restate the production logic under test. Left empty by default so every
   * pre-existing case keeps the complete-claim behaviour it was written for; the REQ-SEC-036 cases
   * populate it explicitly.
   */
  private final PartialRoleScopeProperties partialRoleScopeProperties =
      new PartialRoleScopeProperties();

  private static final UUID USER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

  @BeforeEach
  void setUp() {
    userRegistrationService =
        new UserRegistrationService(
            userRepository,
            userApprovalEventRepository,
            eventPublisher,
            keycloakService,
            userDeletionService,
            selfProvider);
    setField(userRegistrationService, "requireApproval", true);
    userReconciliationService =
        new UserReconciliationService(
            userRepository,
            roleRepository,
            defaultBlueprintProvisioningService,
            eventPublisher,
            userRegistrationService,
            userService,
            partialRoleScopeProperties);
    // The identity seam stays in UserService; reconciliation delegates the JWT-subject parse to it.
    lenient()
        .when(userService.getUserIdFromJwt(any(Jwt.class)))
        .thenAnswer(inv -> UUID.fromString(((Jwt) inv.getArgument(0)).getSubject()));
  }

  // ---------------------------------------------------------------
  // syncUser(Jwt) — the hot path on every authenticated request
  // ---------------------------------------------------------------

  /**
   * The roster sync persists the Keycloak {@code enabled} flag (V230, ADR-0129).
   *
   * <p>The Admin API always returned it and the sync always dropped it, so deactivating a member
   * refused them nothing: with a token that is bounded by expiry, but the ingest gateway can
   * <em>name</em> a subject, and a name does not expire. This is what makes revocation take effect
   * at the next sync pass instead of never.
   */
  @Test
  void persistsTheKeycloakEnabledFlag() {
    User existing = new User();
    existing.setId(USER_ID);
    existing.setEnabledInKeycloak(true);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

    userReconciliationService.syncUser(
        new KeycloakUserDto(USER_ID, "alice", "alice@example.com", false, Set.of(), null));

    assertFalse(existing.isEnabledInKeycloak());
  }

  /**
   * A missing {@code enabled} field reads as enabled.
   *
   * <p>Fail-open on purpose, and the only place in this boundary where that is right: the flag can
   * only ever refuse, so a realm that stops sending the field would otherwise lock out the entire
   * member base at the next sync pass.
   */
  @Test
  void treatsAnAbsentEnabledFieldAsEnabled() {
    User existing = new User();
    existing.setId(USER_ID);
    existing.setEnabledInKeycloak(false);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

    userReconciliationService.syncUser(
        new KeycloakUserDto(USER_ID, "alice", "alice@example.com", null, Set.of(), null));

    assertTrue(existing.isEnabledInKeycloak());
  }

  @Nested
  class SyncJwtUserTests {

    @Test
    void createsNewUser_whenIdAndUsernameUnknown() {
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username", "alice",
                  "email", "alice@example.com"));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(role(99L, "Guest")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(jwt).user();

      assertEquals(USER_ID, result.getId());
      assertEquals("alice", result.getUsername());
      assertEquals("alice@example.com", result.getEmail());
      assertEquals(1, result.getRoles().size());
      assertEquals("Guest", result.getRoles().iterator().next().getName());
      verify(userRepository, times(1)).save(any(User.class));
      // A brand-new user is granted the default blueprints synchronously (REQ-INV-016).
      verify(defaultBlueprintProvisioningService).grantDefaultsToUser(USER_ID);
    }

    @Test
    void usesUsernameFallback_whenIdLookupFails_butUsernameMatches() {
      // The "warn-and-recover" path: ID changed (rare, Keycloak realm import,
      // imports, ...) but username still matches a legacy local row.
      Jwt jwt = newJwt(USER_ID.toString(), Map.of("preferred_username", "alice"));
      User existing = newUser(UUID.randomUUID(), "alice");
      existing.setVersion(1L); // not new

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findByUsername("alice")).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(role(99L, "Guest")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(jwt).user();

      assertSame(existing, result, "must reuse the looked-up legacy user");
    }

    @Test
    void noFieldChanged_andUserNotNew_skipsSave() {
      // Every field already matches the JWT claims; the user has been seen
      // before (version != null -> isNew() == false). The service must short-
      // circuit and NOT call save().
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username", "alice",
                  "email", "alice@example.com"));

      User existing = newUser(USER_ID, "alice");
      existing.setEmail("alice@example.com");
      existing.setVersion(2L);
      Role guest = role(99L, "Guest");
      existing.setRoles(new HashSet<>(Set.of(guest)));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("Guest")).thenReturn(Optional.of(guest));

      User result = userReconciliationService.syncUser(jwt).user();

      assertSame(existing, result);
      verify(userRepository, never()).save(any(User.class));
      // An already-known user is never re-granted the defaults.
      verify(defaultBlueprintProvisioningService, never()).grantDefaultsToUser(any());
    }

    @Test
    void detectsChange_whenUsernameDiffers() {
      assertSavedOnFieldChange(
          "preferred_username", "new-username", User::setUsername, "old-username");
    }

    @Test
    void detectsChange_whenEmailDiffers() {
      assertSavedOnFieldChange("email", "new@example.com", User::setEmail, "old@example.com");
    }

    @Test
    void detectsChange_whenRolesDiffer() {
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username",
                  "alice",
                  "realm_access",
                  Map.of("roles", List.of("ADMIN"))));

      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      Role guest = role(99L, "Guest");
      existing.setRoles(new HashSet<>(Set.of(guest)));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(role(1L, "ADMIN")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(jwt);

      verify(userRepository, times(1)).save(any(User.class));
      assertEquals(1, existing.getRoles().size());
      assertEquals("ADMIN", existing.getRoles().iterator().next().getName());
    }

    @Test
    void newUserWithNoChangedFields_isStillSaved() {
      // The "user.isNew()" branch triggers save() even when no detected
      // changes happened — required because a brand-new entity has to be
      // persisted to acquire an ID/version.
      Jwt jwt = newJwt(USER_ID.toString(), Map.of()); // no claims at all

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(role(99L, "Guest")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(jwt).user();

      // Even though every JWT claim is null and every field stays null,
      // changed==true via the role-sync block (empty Keycloak roles -> Guest)
      // and additionally user.isNew()==true.
      verify(userRepository, times(1)).save(result);
    }

    /**
     * Builds a JWT where every field already matches an existing user, then flips the named claim
     * to a different value and asserts that save is called.
     */
    private void assertSavedOnFieldChange(
        String jwtClaim,
        String newValue,
        java.util.function.BiConsumer<User, String> oldFieldSetter,
        String oldValue) {
      Map<String, Object> claims =
          new java.util.HashMap<>(
              Map.of(
                  "preferred_username", "alice",
                  "email", "alice@example.com"));
      claims.put(jwtClaim, newValue);
      Jwt jwt = newJwt(USER_ID.toString(), claims);

      User existing = newUser(USER_ID, "alice");
      existing.setEmail("alice@example.com");
      existing.setVersion(1L);
      oldFieldSetter.accept(existing, oldValue);
      Role guest = role(99L, "Guest");
      existing.setRoles(new HashSet<>(Set.of(guest)));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      lenient().when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest")).thenReturn(Optional.of(guest));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(jwt);
      verify(userRepository, times(1)).save(any(User.class));
    }
  }

  // ---------------------------------------------------------------
  // syncUser(Jwt) — Discord federated-login approval branch (PR review #5)
  // ---------------------------------------------------------------

  @Nested
  class DiscordSyncTests {

    private static final String DISCORD_ID = "123456789012345678";

    @Test
    void newDiscordNonAdmin_landsPending_andNotifiesAdmins() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(codeRole("GUEST", "Guest")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(true, List.of())).user();

      assertEquals(ApprovalStatus.PENDING, result.getApprovalStatus());
      assertEquals(DISCORD_ID, result.getDiscordUserId());
      verify(eventPublisher).publishEvent(any(DiscordRegistrationPendingEvent.class));
    }

    @Test
    void newDiscordAdmin_landsActive_noNotification() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Admin"))
          .thenReturn(Optional.of(codeRole("ADMIN", "Admin")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(true, List.of("Admin"))).user();

      assertEquals(ApprovalStatus.ACTIVE, result.getApprovalStatus());
      verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * Security hardening (PR #740 review): a new Discord login MUST NOT be matched onto a
     * pre-existing row by {@code preferred_username}. The brokered Discord username is
     * attacker-influenced, so the legacy username fallback is suppressed for a Discord login —
     * otherwise a verified guild member could link their Discord identity to someone else's
     * (possibly privileged, already-ACTIVE) account and bypass the PENDING gate. The Discord
     * identity is a brand-new PENDING registration keyed by its own subject, and {@code
     * findByUsername} is never consulted.
     */
    @Test
    void newDiscordLogin_ignoresMatchingCredentialUsername_landsPending() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(codeRole("GUEST", "Guest")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(true, List.of())).user();

      assertEquals(USER_ID, result.getId());
      assertEquals(ApprovalStatus.PENDING, result.getApprovalStatus());
      assertEquals(DISCORD_ID, result.getDiscordUserId());
      // The core guarantee: a Discord login is recognised only by subject, never by username.
      verify(userRepository, never()).findByUsername(any());
      verify(eventPublisher).publishEvent(any(DiscordRegistrationPendingEvent.class));
    }

    @Test
    void newPendingRegistration_notifiesAdmins_evenWithoutDiscordClaim() {
      // REQ-NOTIF-012 regression guard: the admin notification is keyed off the PENDING transition,
      // NOT off the discord_user_id claim. A brand-new non-admin registration whose token carries
      // NO discord_user_id claim (discordJwt(false, ...)) — e.g. because the optional Keycloak
      // claim mapper is absent/misconfigured — still lands PENDING (fail-safe, REQ-SEC-017) AND
      // still notifies every admin.
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findByUsername("discorduser")).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(codeRole("GUEST", "Guest")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(false, List.of())).user();

      assertEquals(ApprovalStatus.PENDING, result.getApprovalStatus());
      org.junit.jupiter.api.Assertions.assertNull(result.getDiscordUserId());
      verify(eventPublisher).publishEvent(any(DiscordRegistrationPendingEvent.class));
    }

    @Test
    void newCredentialAdmin_landsActive_noNotification() {
      // ADMIN bootstrap carve-out: a brand-new Keycloak ADMIN-realm-role holder is ACTIVE even
      // without Discord, so the first admin can never be locked out by the fail-safe default.
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findByUsername("discorduser")).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Admin"))
          .thenReturn(Optional.of(codeRole("ADMIN", "Admin")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(false, List.of("Admin"))).user();

      assertEquals(ApprovalStatus.ACTIVE, result.getApprovalStatus());
      verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void existingPendingAdmin_isPromotedToActive() {
      User existing = new User();
      existing.setId(USER_ID);
      existing.setUsername("discorduser");
      existing.setApprovalStatus(ApprovalStatus.PENDING);
      existing.setVersion(1L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("Admin"))
          .thenReturn(Optional.of(codeRole("ADMIN", "Admin")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(false, List.of("Admin"))).user();

      assertEquals(ApprovalStatus.ACTIVE, result.getApprovalStatus());
      verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void newNonAdmin_landsActive_whenApprovalNotRequired() {
      // With app.registration.require-approval=false (the e2e stack), a brand-new non-admin is
      // created ACTIVE rather than PENDING — the gate lives on the shared UserRegistrationService.
      setField(userRegistrationService, "requireApproval", false);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findByUsername("discorduser")).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(codeRole("GUEST", "Guest")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(false, List.of())).user();

      assertEquals(ApprovalStatus.ACTIVE, result.getApprovalStatus());
      verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void newDiscordLogin_persistsGuildNickname_trimmed() {
      // covers REQ-DATA-008 — the per-guild server nickname claim is persisted (trimmed) for
      // display in the admin approval queue.
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(codeRole("GUEST", "Guest")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      Jwt jwt =
          Jwt.withTokenValue("t")
              .header("alg", "none")
              .subject(USER_ID.toString())
              .claim("preferred_username", "discorduser")
              .claim("realm_access", Map.of("roles", List.of()))
              .claim("discord_user_id", DISCORD_ID)
              .claim("discord_guild_nickname", "  Vanguard Pilot  ")
              .build();

      User result = userReconciliationService.syncUser(jwt).user();

      assertEquals("Vanguard Pilot", result.getDiscordGuildNickname());
    }

    @Test
    void discordLoginWithoutNicknameClaim_leavesGuildNicknameNull() {
      // covers REQ-DATA-008 — the nickname capture is best-effort/optional: an absent claim leaves
      // the field null rather than failing the login.
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(codeRole("GUEST", "Guest")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(true, List.of())).user();

      org.junit.jupiter.api.Assertions.assertNull(result.getDiscordGuildNickname());
    }

    private Jwt discordJwt(boolean withDiscord, List<String> realmRoles) {
      Jwt.Builder builder =
          Jwt.withTokenValue("t")
              .header("alg", "none")
              .subject(USER_ID.toString())
              .claim("preferred_username", "discorduser")
              .claim("realm_access", Map.of("roles", realmRoles));
      if (withDiscord) {
        builder.claim("discord_user_id", DISCORD_ID);
      }
      return builder.build();
    }
  }

  // ---------------------------------------------------------------
  // REQ-SEC-036 - a partial-scope client's role claim is not authoritative
  // ---------------------------------------------------------------

  /**
   * The mobile client runs with {@code fullScopeAllowed: false} and withholds {@code Admin} on
   * purpose (REQ-SEC-035), so its tokens describe a deliberately smaller member than the real one.
   * Since this reconciliation REPLACES the stored role set rather than merging into it, persisting
   * that description would let whichever client a member used last decide what the database says
   * they are.
   *
   * <p>These cases pin both halves, because either alone is a defect: the row must survive the
   * partial claim, and the request must still be authorised by it.
   */
  @Nested
  class PartialRoleScopeTests {

    private static final String MOBILE_CLIENT = "basetool-android";

    /** Lists the mobile client, which every case in this class assumes is configured as partial. */
    @BeforeEach
    void listTheMobileClient() {
      partialRoleScopeProperties.setClientIds(List.of(MOBILE_CLIENT));
    }

    /**
     * Builds a token for {@code azp} carrying exactly {@code realmRoles}.
     *
     * @param azp the authorized-party claim naming the client that requested the token
     * @param realmRoles the realm-role names the client's scope let through
     * @return the token
     */
    private Jwt tokenFrom(String azp, List<String> realmRoles) {
      return newJwt(
          USER_ID.toString(),
          Map.of(
              "preferred_username",
              "alice",
              "azp",
              azp,
              "realm_access",
              Map.of("roles", realmRoles)));
    }

    /**
     * An administrator opening the app keeps {@code Admin} in the database.
     *
     * <p>This is the regression that motivated the requirement. Measured on the test stack before
     * the guard existed, an account holding Admin + Officer + KRT Member was left holding {@code
     * Guest} alone after one app login - and the same mechanism, once the client's scope carried
     * the member roles, still stripped {@code Admin} specifically.
     */
    @Test
    void doesNotOverwriteTheStoredRoles_whenTheClaimComesFromAPartialScopeClient() {
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      Role admin = role(1L, "Admin");
      Role member = role(2L, "KRT Member");
      existing.setRoles(new HashSet<>(Set.of(admin, member)));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("KRT Member")).thenReturn(Optional.of(member));

      userReconciliationService.syncUser(tokenFrom(MOBILE_CLIENT, List.of("KRT Member")));

      assertEquals(
          Set.of("Admin", "KRT Member"),
          roleNames(existing.getRoles()),
          "the stored role set must survive a claim that could not have carried Admin");
    }

    /**
     * The request is nevertheless authorised with the token's roles, not the row's.
     *
     * <p>Without this the guard would be worse than the defect: the row keeps {@code Admin}
     * precisely because the app path stopped overwriting it, so authorising from the row would hand
     * the app the very authority its client scope withholds.
     */
    @Test
    void returnsTheTokensRolesAsEffective_whenTheClaimIsPartial() {
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>(Set.of(role(1L, "Admin"), role(2L, "KRT Member"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("KRT Member"))
          .thenReturn(Optional.of(role(2L, "KRT Member")));

      UserReconciliationService.ReconciledUser reconciled =
          userReconciliationService.syncUser(tokenFrom(MOBILE_CLIENT, List.of("KRT Member")));

      assertEquals(
          Set.of("KRT Member"),
          roleNames(reconciled.effectiveRoles()),
          "the request must carry only what the token presented");
    }

    /**
     * A client that is NOT listed still replaces the stored set - including shrinking it.
     *
     * <p>The complement that keeps the guard honest: one that stopped every role removal would mean
     * a demotion in Keycloak never reached the database, which is its own privilege defect.
     */
    @Test
    void stillReplacesTheStoredRoles_whenTheClaimComesFromAnOrdinaryClient() {
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>(Set.of(role(1L, "Admin"), role(2L, "KRT Member"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("KRT Member"))
          .thenReturn(Optional.of(role(2L, "KRT Member")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(tokenFrom("basetool-frontend", List.of("KRT Member")));

      assertEquals(
          Set.of("KRT Member"),
          roleNames(existing.getRoles()),
          "a complete claim must still be able to remove a role");
    }

    /**
     * A first-ever login through the app does persist its roles.
     *
     * <p>There is no stored set to protect on a brand-new row, and the alternative is writing a
     * member with no roles at all - which the Guest fallback would then stand in for permanently.
     * The next complete-claim login or Admin-API pass widens it.
     */
    @Test
    void persistsTheRoles_whenThePartialClaimCreatesTheRow() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("KRT Member"))
          .thenReturn(Optional.of(role(2L, "KRT Member")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      UserReconciliationService.ReconciledUser reconciled =
          userReconciliationService.syncUser(tokenFrom(MOBILE_CLIENT, List.of("KRT Member")));

      assertEquals(
          Set.of("KRT Member"),
          roleNames(reconciled.user().getRoles()),
          "a new row must not be created role-less");
    }

    /**
     * A token with no {@code azp} is never treated as partial.
     *
     * <p>An absent claim must fail towards the established behaviour, not towards the exception:
     * treating "unknown client" as partial would quietly stop every role change from any issuer
     * that omits the claim.
     */
    @Test
    void treatsAMissingAzpAsAnOrdinaryClient() {
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>(Set.of(role(1L, "Admin"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("KRT Member"))
          .thenReturn(Optional.of(role(2L, "KRT Member")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      Jwt noAzp =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username",
                  "alice",
                  "realm_access",
                  Map.of("roles", List.of("KRT Member"))));

      userReconciliationService.syncUser(noAzp);

      assertEquals(Set.of("KRT Member"), roleNames(existing.getRoles()));
    }

    /**
     * Collapses a role set to its names, so a failure message names the roles rather than printing
     * entity identity hashes.
     *
     * @param roles the roles to name
     * @return their names
     */
    private Set<String> roleNames(java.util.Collection<Role> roles) {
      return roles.stream().map(Role::getName).collect(java.util.stream.Collectors.toSet());
    }
  }

  // ---------------------------------------------------------------
  // syncUser(KeycloakUserDto)
  // ---------------------------------------------------------------

  @Nested
  class SyncKeycloakUserTests {

    @Test
    void returnsEarly_whenDtoIdIsNull() {
      KeycloakUserDto dto =
          new KeycloakUserDto(null, "alice", "alice@example.com", true, Set.of(), null);

      userReconciliationService.syncUser(dto);

      verify(userRepository, never()).save(any());
    }

    @Test
    void flipsInKeycloak_whenLocalUserPreviouslyMarkedAbsent() {
      User existing = newUser(USER_ID, "alice");
      existing.setInKeycloak(false);
      existing.setVersion(1L);

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(role(99L, "Guest")));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", null, true, Set.of(), null));

      assertTrue(existing.isInKeycloak(), "must flip back to true once seen again");
      verify(userRepository, times(1)).save(existing);
    }

    @Test
    void createsNewUser_whenIdUnknown() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(role(99L, "Guest")));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, Set.of(), null));

      verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void createsNewNonAdminUser_landsPending() {
      // Fail-safe default (REQ-SEC-017): a brand-new non-admin user first discovered by the
      // scheduled sync lands PENDING, so the scheduler can never pre-create an ACTIVE row that a
      // later login would inherit (created == false) and use to skip the approval gate.
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(role(99L, "Guest")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, Set.of(), null));

      verify(userRepository).save(argThat(u -> u.getApprovalStatus() == ApprovalStatus.PENDING));
    }

    @Test
    void createsNewAdminUser_landsActive() {
      // ADMIN bootstrap carve-out applies to the scheduled sync too: a brand-new ADMIN stays
      // ACTIVE.
      Role adminRole = role(1L, "ADMIN");
      adminRole.setCode("ADMIN");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(adminRole));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "root", null, true, Set.of("ADMIN"), null));

      verify(userRepository).save(argThat(u -> u.getApprovalStatus() == ApprovalStatus.ACTIVE));
    }

    @Test
    void createsNewNonAdminUser_notifiesAdmins() {
      // REQ-NOTIF-012: a registration first materialised by the scheduled reconciler (not the
      // interactive login) must also notify the admins. Gated on `created`, so it fires exactly
      // once across the two sync paths.
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(role(99L, "Guest")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, Set.of(), null));

      verify(eventPublisher).publishEvent(any(DiscordRegistrationPendingEvent.class));
    }

    @Test
    void createsNewAdminUser_doesNotNotify() {
      // An admin lands ACTIVE (bootstrap carve-out), so no pending-approval notification is raised.
      Role adminRole = role(1L, "ADMIN");
      adminRole.setCode("ADMIN");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(adminRole));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "root", null, true, Set.of("ADMIN"), null));

      verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void existingPendingUser_doesNotReNotify() {
      // Exactly-once guard: an already-persisted user (created == false) — re-seen on a later
      // reconciler pass, or after the interactive login already announced them — never
      // re-publishes.
      User existing = newUser(USER_ID, "alice");
      existing.setEmail("alice@example.com");
      existing.setInKeycloak(true);
      existing.setApprovalStatus(ApprovalStatus.PENDING);
      existing.setVersion(2L);
      Role guest = role(99L, "Guest");
      existing.setRoles(new HashSet<>(Set.of(guest)));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("Guest")).thenReturn(Optional.of(guest));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, Set.of(), null));

      verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void noFieldChanged_andUserNotNew_skipsSave() {
      User existing = newUser(USER_ID, "alice");
      existing.setEmail("alice@example.com");
      existing.setInKeycloak(true);
      existing.setVersion(3L);
      Role guest = role(99L, "Guest");
      existing.setRoles(new HashSet<>(Set.of(guest)));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("Guest")).thenReturn(Optional.of(guest));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, Set.of(), null));

      verify(userRepository, never()).save(any());
    }

    @Test
    void backfillsDiscordLink_whenExistingUserLinkedLater() {
      // The reported bug (REQ-DATA-006): a pre-existing credential account that linked Discord
      // AFTER creation. The scheduled sync reads the Discord federated identity from the Admin API
      // and back-fills the local link with no re-login.
      User existing = newUser(USER_ID, "linkedlater");
      existing.setEmail("l@example.com");
      existing.setInKeycloak(true);
      existing.setVersion(4L);
      existing.setDiscordUserId(null);
      Role guest = role(99L, "Guest");
      existing.setRoles(new HashSet<>(Set.of(guest)));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("Guest")).thenReturn(Optional.of(guest));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(
              USER_ID, "linkedlater", "l@example.com", true, Set.of(), "123456789012345678"));

      assertEquals("123456789012345678", existing.getDiscordUserId());
      verify(userRepository, times(1)).save(existing);
    }

    @Test
    void leavesExistingDiscordLink_whenDtoCarriesNoFederatedId() {
      // A null discordUserId means "the federated-identity lookup found nothing OR failed" — it is
      // NOT a signal to unlink. An already-linked user must keep their link (and the run must not
      // even save, since nothing changed), so a transient Admin-API hiccup can never wipe the link.
      User existing = newUser(USER_ID, "linked");
      existing.setEmail("l@example.com");
      existing.setInKeycloak(true);
      existing.setVersion(2L);
      existing.setDiscordUserId("123456789012345678");
      Role guest = role(99L, "Guest");
      existing.setRoles(new HashSet<>(Set.of(guest)));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findByNameIgnoreCase("Guest")).thenReturn(Optional.of(guest));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "linked", "l@example.com", true, Set.of(), null));

      assertEquals("123456789012345678", existing.getDiscordUserId());
      verify(userRepository, never()).save(any());
    }
  }

  // ---------------------------------------------------------------
  // extractRolesFromJwt
  // ---------------------------------------------------------------

  @Nested
  class ExtractRolesFromJwtTests {

    @Test
    void returnsRoles_whenRealmAccessHasRolesKey() {
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of("realm_access", Map.of("roles", List.of("ADMIN", "KRT_MEMBER"))));

      Set<String> roles = userReconciliationService.extractRolesFromJwt(jwt);

      assertEquals(Set.of("ADMIN", "KRT_MEMBER"), roles);
    }

    @Test
    void returnsEmpty_whenRealmAccessClaimMissing() {
      Jwt jwt = newJwt(USER_ID.toString(), Map.of());

      assertTrue(userReconciliationService.extractRolesFromJwt(jwt).isEmpty());
    }

    @Test
    void returnsEmpty_whenRealmAccessLacksRolesKey() {
      Jwt jwt =
          newJwt(USER_ID.toString(), Map.of("realm_access", Map.of("something_else", "value")));

      assertTrue(userReconciliationService.extractRolesFromJwt(jwt).isEmpty());
    }
  }

  // ---------------------------------------------------------------
  // mapRoles (via syncUser) — Guest fallback when none match
  // ---------------------------------------------------------------

  @Test
  void mapRoles_fallsBackToGuest_whenNoKeycloakRoleMatchesLocal() {
    Jwt jwt =
        newJwt(
            USER_ID.toString(),
            Map.of(
                "preferred_username",
                "alice",
                "realm_access",
                Map.of("roles", List.of("UNKNOWN_ROLE_FROM_OTHER_REALM"))));

    when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
    when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
    when(roleRepository.findByNameIgnoreCase("UNKNOWN_ROLE_FROM_OTHER_REALM"))
        .thenReturn(Optional.empty());
    when(roleRepository.findByNameIgnoreCase("Guest")).thenReturn(Optional.of(role(99L, "Guest")));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User result = userReconciliationService.syncUser(jwt).user();

    assertEquals(1, result.getRoles().size());
    assertEquals("Guest", result.getRoles().iterator().next().getName());
  }

  @Test
  void mapRoles_nullRoleNames_alsoFallsBackToGuest() {
    // KeycloakUserDto.roles() == null is treated as empty.
    when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
    when(roleRepository.findByNameIgnoreCase("Guest")).thenReturn(Optional.of(role(99L, "Guest")));

    userReconciliationService.syncUser(
        new KeycloakUserDto(USER_ID, "alice", null, true, null, null));

    verify(roleRepository, times(1)).findByNameIgnoreCase("Guest");
  }

  // ---------------------------------------------------------------
  // markMissingUsers
  // ---------------------------------------------------------------

  @Nested
  class MarkMissingUsersTests {

    @Test
    void emptyInput_doesNotCallRepository() {
      // Early-return guard: an empty input must NOT trigger a useless
      // (and potentially expensive) bulk-update query.
      userReconciliationService.markMissingUsers(List.of());

      verify(userRepository, never()).markMissingUsers(any());
    }

    @Test
    void nonEmptyInput_delegatesToRepository() {
      List<UUID> ids = List.of(USER_ID);

      userReconciliationService.markMissingUsers(ids);

      verify(userRepository).markMissingUsers(ids);
    }

    @Test
    void returnsTheRepositoryAffectedRowCount_soTheCallerCanReportIt() {
      // The count used to be discarded at the JPA level (void), which is why a mass
      // soft-delete left no trace anywhere.
      List<UUID> ids = List.of(USER_ID);
      when(userRepository.markMissingUsers(ids)).thenReturn(7);

      assertEquals(7, userReconciliationService.markMissingUsers(ids));
    }

    @Test
    void emptyInput_reportsZeroFlagged() {
      assertEquals(0, userReconciliationService.markMissingUsers(List.of()));
    }
  }

  // ---------------------------------------------------------------
  // logRoleSyncSummary — the per-run role-mapping aggregate
  // ---------------------------------------------------------------

  @Nested
  class RoleSyncSummaryTests {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
      logger = (Logger) LoggerFactory.getLogger(UserReconciliationService.class);
      appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
      logger.detachAppender(appender);
    }

    @Test
    void quietRun_logsTheAggregateAtInfo() {
      userReconciliationService.logRoleSyncSummary();

      ILoggingEvent event = onlyEvent();
      assertEquals(Level.INFO, event.getLevel());
      assertTrue(event.getFormattedMessage().contains("0 accounts changed roles"));
    }

    @Test
    void aMassGuestFallback_escalatesToWarn_withCountsOnly() {
      // A realm-side role rename: every holder's role name stops resolving, so each account is
      // re-mapped onto the Guest fallback. Four accounts is one past the threshold.
      when(roleRepository.findByNameIgnoreCase("Officer")).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(codeRole("GUEST", "Guest")));
      for (int i = 0; i < 4; i++) {
        syncDemotedAccount();
      }

      userReconciliationService.logRoleSyncSummary();

      ILoggingEvent event = lastEvent();
      assertEquals(Level.WARN, event.getLevel());
      String message = event.getFormattedMessage();
      assertTrue(message.contains("4 accounts changed roles"), message);
      assertTrue(message.contains("4 of them re-mapped onto the Guest fallback"), message);
      // REQ-OBS-004: counts only — never the callsign / preferred_username of a demoted account.
      assertFalse(message.contains("demoted-callsign"), message);
    }

    @Test
    void aSingleGuestFallback_staysAtInfo_andTheTalliesResetForTheNextRun() {
      when(roleRepository.findByNameIgnoreCase("Officer")).thenReturn(Optional.empty());
      when(roleRepository.findByNameIgnoreCase("Guest"))
          .thenReturn(Optional.of(codeRole("GUEST", "Guest")));
      syncDemotedAccount();

      userReconciliationService.logRoleSyncSummary();
      assertEquals(Level.INFO, lastEvent().getLevel());
      assertTrue(lastEvent().getFormattedMessage().contains("1 accounts changed roles"));

      // A second summary without any further sync must report a clean run, not the previous
      // run's numbers again.
      userReconciliationService.logRoleSyncSummary();
      assertEquals(Level.INFO, lastEvent().getLevel());
      assertTrue(lastEvent().getFormattedMessage().contains("0 accounts changed roles"));
    }

    /**
     * Runs one Admin-API sync of an account that holds {@code Officer} locally while Keycloak's
     * {@code Officer} no longer resolves — the shape a renamed realm role produces.
     */
    private void syncDemotedAccount() {
      UUID id = UUID.randomUUID();
      User existing = newUser(id, "demoted-callsign");
      existing.setInKeycloak(true);
      existing.setApprovalStatus(ApprovalStatus.ACTIVE);
      existing.setRoles(new HashSet<>(Set.of(codeRole("OFFICER", "Officer"))));
      when(userRepository.findById(id)).thenReturn(Optional.of(existing));

      userReconciliationService.syncUser(
          new KeycloakUserDto(id, "demoted-callsign", null, true, Set.of("Officer"), null));
    }

    /**
     * The single log event the appender captured.
     *
     * @return that event
     */
    private ILoggingEvent onlyEvent() {
      assertEquals(1, appender.list.size());
      return appender.list.getFirst();
    }

    /**
     * The most recent log event the appender captured.
     *
     * @return that event
     */
    private ILoggingEvent lastEvent() {
      assertFalse(appender.list.isEmpty());
      return appender.list.getLast();
    }
  }

  // ---------------------------------------------------------------
  // getMappableRoleNames / getKnownDiscordLinkedUserIds — sync inputs
  // ---------------------------------------------------------------

  @Nested
  class SyncInputCatalogTests {

    @Test
    void getMappableRoleNames_returnsTheLocalRoleCatalogNames() {
      when(roleRepository.findAllNames()).thenReturn(Set.of("ADMIN", "OFFICER", "Guest"));

      assertEquals(
          Set.of("ADMIN", "OFFICER", "Guest"), userReconciliationService.getMappableRoleNames());
      verify(roleRepository).findAllNames();
    }

    @Test
    void getKnownDiscordLinkedUserIds_returnsAlreadyLinkedIds() {
      UUID linked = UUID.randomUUID();
      when(userRepository.findIdsWithDiscordLink()).thenReturn(Set.of(linked));

      assertEquals(Set.of(linked), userReconciliationService.getKnownDiscordLinkedUserIds());
      verify(userRepository).findIdsWithDiscordLink();
    }
  }

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

  private static Jwt newJwt(String subject, Map<String, Object> additionalClaims) {
    Map<String, Object> claims = new java.util.HashMap<>();
    claims.put("sub", subject);
    claims.putAll(additionalClaims);
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(subject)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claims(c -> c.putAll(claims))
        .build();
  }

  private static User newUser(UUID id, String username) {
    User u = new User();
    u.setId(id);
    u.setUsername(username);
    return u;
  }

  private static Role role(long id, String name) {
    Role r = new Role();
    r.setId(id);
    r.setName(name);
    return r;
  }

  private static Role codeRole(String code, String name) {
    Role r = new Role();
    r.setCode(code);
    r.setName(name);
    return r;
  }
}
