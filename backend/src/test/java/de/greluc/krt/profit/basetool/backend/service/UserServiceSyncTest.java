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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for the identity-seam and self-service halves of {@link UserService} left in place
 * after the Keycloak reconciliation moved to {@link UserReconciliationService} (audit Thema&nbsp;7,
 * #1252):
 *
 * <ul>
 *   <li>{@link UserService#getUserIdFromJwt} — JWT sub/UUID validation (fail-closed).
 *   <li>{@link UserService#updateUserDescription} — the version-check {@code
 *       ObjectOptimisticLockingFailureException} path.
 *   <li>{@link UserService#updateUserDefaultPayoutPreference}.
 *   <li>{@link UserService#getCurrentUser} — null/anonymous/non-JWT principal short-circuits.
 *   <li>{@link UserService#findById} — not-found path.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceSyncTest {

  @Mock private UserRepository userRepository;
  @Mock private AuthHelperService authHelperService;

  @InjectMocks private UserService userService;

  private static final UUID USER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

  // ---------------------------------------------------------------
  // getUserIdFromJwt
  // ---------------------------------------------------------------

  @Nested
  class GetUserIdFromJwtTests {

    @Test
    void returnsUuid_whenSubjectIsValidUuid() {
      Jwt jwt = newJwt(USER_ID.toString(), Map.of());
      assertEquals(USER_ID, userService.getUserIdFromJwt(jwt));
    }

    @Test
    void throwsAuthenticationServiceException_whenSubjectIsNull() {
      // Jwt.Builder requires a non-null subject; build with empty subject is impossible.
      // Mock to return null directly.
      Jwt jwt = org.mockito.Mockito.mock(Jwt.class);
      when(jwt.getSubject()).thenReturn(null);

      assertThrows(AuthenticationServiceException.class, () -> userService.getUserIdFromJwt(jwt));
    }

    @Test
    void throwsAuthenticationServiceException_whenSubjectIsNotUuid() {
      Jwt jwt = newJwt("not-a-uuid", Map.of());

      AuthenticationServiceException ex =
          assertThrows(
              AuthenticationServiceException.class, () -> userService.getUserIdFromJwt(jwt));
      assertTrue(ex.getMessage().contains("must be a UUID"));
    }
  }

  // ---------------------------------------------------------------
  // updateUserDescription
  // ---------------------------------------------------------------

  @Nested
  class UpdateUserDescriptionTests {

    @Test
    void throwsNotFoundException_whenUserMissing() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> userService.updateUserDescription(USER_ID, "desc", "display", 1L));
    }

    @Test
    void throwsOptimisticLockingFailure_whenVersionMismatch() {
      User user = newUser(USER_ID, "alice");
      user.setVersion(7L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> userService.updateUserDescription(USER_ID, "desc", "display", 3L));
    }

    @Test
    void updatesBothFields_whenProvided() {
      User user = newUser(USER_ID, "alice");
      user.setVersion(1L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.saveAndFlush(user)).thenReturn(user);

      userService.updateUserDescription(USER_ID, "new description", "Display Name", 1L);

      assertEquals("new description", user.getDescription());
      assertEquals("Display Name", user.getDisplayName());
    }

    @Test
    void blankDisplayName_isNormalisedToNull() {
      User user = newUser(USER_ID, "alice");
      user.setVersion(1L);
      user.setDisplayName("previous");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.saveAndFlush(user)).thenReturn(user);

      userService.updateUserDescription(USER_ID, null, "   ", 1L);

      assertEquals(
          null,
          user.getDisplayName(),
          "blank-only displayName is stored as null so getEffectiveName() "
              + "falls through to username");
    }

    @Test
    void nullVersion_bypassesOptimisticCheck() {
      User user = newUser(USER_ID, "alice");
      user.setVersion(5L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.saveAndFlush(user)).thenReturn(user);

      userService.updateUserDescription(USER_ID, "x", null, null);

      assertEquals("x", user.getDescription());
    }
  }

  // ---------------------------------------------------------------
  // updateUserDefaultPayoutPreference
  // ---------------------------------------------------------------

  @Nested
  class UpdateUserDefaultPayoutPreferenceTests {

    @Test
    void throwsNotFoundException_whenUserMissing() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () ->
              userService.updateUserDefaultPayoutPreference(USER_ID, PayoutPreference.DONATE, 1L));
    }

    @Test
    void throwsOptimisticLockingFailure_whenVersionMismatch() {
      User user = newUser(USER_ID, "alice");
      user.setVersion(7L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () ->
              userService.updateUserDefaultPayoutPreference(USER_ID, PayoutPreference.DONATE, 3L));
    }

    @Test
    void setsPreference_whenVersionMatches() {
      User user = newUser(USER_ID, "alice");
      user.setVersion(1L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.saveAndFlush(user)).thenReturn(user);

      userService.updateUserDefaultPayoutPreference(USER_ID, PayoutPreference.DONATE, 1L);

      assertEquals(PayoutPreference.DONATE, user.getDefaultPayoutPreference());
    }

    @Test
    void nullVersion_bypassesOptimisticCheck() {
      User user = newUser(USER_ID, "alice");
      user.setVersion(5L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.saveAndFlush(user)).thenReturn(user);

      userService.updateUserDefaultPayoutPreference(USER_ID, PayoutPreference.PAYOUT, null);

      assertEquals(PayoutPreference.PAYOUT, user.getDefaultPayoutPreference());
    }
  }

  // ---------------------------------------------------------------
  // findById
  // ---------------------------------------------------------------

  @Nested
  class FindByIdTests {

    @Test
    void returnsUser_whenPresent() {
      User user = newUser(USER_ID, "alice");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

      assertSame(user, userService.findById(USER_ID));
    }

    @Test
    void throwsNotFoundException_whenAbsent() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> userService.findById(USER_ID));
    }
  }

  // ---------------------------------------------------------------
  // getCurrentUser — every short-circuit branch
  // ---------------------------------------------------------------

  @Nested
  class GetCurrentUserTests {

    @Test
    void returnsEmpty_whenNoAuthenticationBound() {
      when(authHelperService.rawAuthentication()).thenReturn(null);

      assertTrue(userService.getCurrentUser().isEmpty());
    }

    @Test
    void returnsEmpty_whenAuthenticationIsNotAuthenticated() {
      Authentication auth = UsernamePasswordAuthenticationToken.unauthenticated("alice", "x");
      when(authHelperService.rawAuthentication()).thenReturn(auth);

      assertTrue(userService.getCurrentUser().isEmpty());
    }

    @Test
    void returnsEmpty_whenPrincipalIsNotJwt() {
      Authentication auth =
          new UsernamePasswordAuthenticationToken("alice", "n/a", java.util.List.of());
      when(authHelperService.rawAuthentication()).thenReturn(auth);

      assertTrue(
          userService.getCurrentUser().isEmpty(),
          "without a Jwt principal there's no Keycloak sub to look up");
    }

    @Test
    void returnsUserOptional_whenJwtPrincipalPresent() {
      Jwt jwt = newJwt(USER_ID.toString(), Map.of());
      Authentication auth =
          new UsernamePasswordAuthenticationToken(jwt, "n/a", java.util.List.of());
      when(authHelperService.rawAuthentication()).thenReturn(auth);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(newUser(USER_ID, "alice")));

      Optional<User> result = userService.getCurrentUser();

      assertTrue(result.isPresent());
      assertEquals(USER_ID, result.get().getId());
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
}
