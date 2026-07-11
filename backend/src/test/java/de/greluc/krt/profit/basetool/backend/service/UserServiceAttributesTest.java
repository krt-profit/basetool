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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Profile-attribute + simple-delegate tests for {@link UserService}. Covers the methods not
 * addressed by {@code UserServiceRankTest} (rank-validation only) / {@code UserServiceSortTest} /
 * {@code UserServiceDeleteTest} / {@code UserServiceSyncTest}:
 *
 * <ul>
 *   <li>{@link UserService#updateUserAttributes} — the non-rank halves (description / displayName /
 *       joinDate / version-check), plus the rank-handling for users that are neither officer nor
 *       squadron member.
 *   <li>{@link UserService#updateReadAnnouncement}.
 *   <li>{@link UserService#markMissingUsers} — early-return on empty.
 *   <li>{@link UserService#isUsernameOrDisplayNameTaken} + {@link
 *       UserService#findMatchesByExactName} — blank-input short-circuit.
 *   <li>{@link UserService#searchByUsername} (both overloads), {@link
 *       UserService#findAll(org.springframework.data.domain.Pageable)}, {@link
 *       UserService#findAllReference} — straight repository delegates.
 *   <li>{@link UserService#deleteUser} fallback path — admin from {@code getCurrentUser()} when
 *       {@code findAllAdmins()} returns nothing usable.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceAttributesTest {

  @Mock private UserRepository userRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private ShipRepository shipRepository;
  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MissionOwnershipRepository missionOwnershipRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MaterialClaimRepository materialClaimRepository;
  @Mock private UserApprovalEventRepository userApprovalEventRepository;

  @Mock
  private de.greluc.krt.profit.basetool.backend.repository.SquadronRepository squadronRepository;

  @Mock private AuthHelperService authHelperService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private OrgUnitMembershipService orgUnitMembershipService;

  @Mock private AuditService auditService;

  @Mock
  private ObjectProvider<OrgUnitBankResponsibilityService> orgUnitBankResponsibilityServiceProvider;

  @Mock private OrgUnitBankResponsibilityService orgUnitBankResponsibilityService;

  @InjectMocks private UserService userService;

  private static final UUID USER_ID = UUID.randomUUID();

  // ---------------------------------------------------------------
  // updateUserAttributes — everything beyond the rank validation
  // ---------------------------------------------------------------

  @Nested
  class UpdateUserAttributesTests {

    @Test
    void throwsNotFoundException_whenUserMissing() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> userService.updateUserAttributes(USER_ID, null, null, null, null, null));
    }

    @Test
    void throwsOptimisticLockingFailure_whenVersionMismatch() {
      User user = newUser(USER_ID);
      user.setVersion(5L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> userService.updateUserAttributes(USER_ID, null, null, null, 2L, null));
    }

    @Test
    void nullVersion_bypassesOptimisticCheck() {
      User user = newUser(USER_ID);
      user.setVersion(5L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.save(user)).thenReturn(user);

      userService.updateUserAttributes(USER_ID, null, "desc", null, null, null);

      assertEquals("desc", user.getDescription());
    }

    @Test
    void descriptionUpdate_isApplied() {
      User user = newUser(USER_ID);
      user.setVersion(1L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.save(user)).thenReturn(user);

      userService.updateUserAttributes(USER_ID, null, "new desc", null, 1L, null);

      assertEquals("new desc", user.getDescription());
    }

    @Test
    void displayNameUpdate_isApplied() {
      User user = newUser(USER_ID);
      user.setVersion(1L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.save(user)).thenReturn(user);

      userService.updateUserAttributes(USER_ID, null, null, "Display Name", 1L, null);

      assertEquals("Display Name", user.getDisplayName());
    }

    @Test
    void blankDisplayName_isNormalisedToNull() {
      User user = newUser(USER_ID);
      user.setVersion(1L);
      user.setDisplayName("had-one");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.save(user)).thenReturn(user);

      userService.updateUserAttributes(USER_ID, null, null, "   ", 1L, null);

      assertNull(
          user.getDisplayName(),
          "blank displayName must be stored as null so getEffectiveName() "
              + "falls back to username");
    }

    @Test
    void joinDateProvided_isSet() {
      User user = newUser(USER_ID);
      user.setVersion(1L);
      LocalDate joinDate = LocalDate.of(2024, 1, 15);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.save(user)).thenReturn(user);

      userService.updateUserAttributes(USER_ID, null, null, null, 1L, joinDate);

      assertEquals(joinDate, user.getJoinDate());
    }

    @Test
    void joinDateExplicitlyNull_clearsExistingJoinDate() {
      // The inline comment in production code says: "joinDate can be explicitly
      // set to null (clear the date)" — verify that setter is always called
      // even when the parameter is null, unlike description/displayName which
      // skip the setter on null.
      User user = newUser(USER_ID);
      user.setVersion(1L);
      user.setJoinDate(LocalDate.of(2024, 1, 15));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.save(user)).thenReturn(user);

      userService.updateUserAttributes(USER_ID, null, null, null, 1L, null);

      assertNull(user.getJoinDate());
    }

    @Test
    void rankForNeitherOfficerNorSquadronUser_isSetWithoutValidation() {
      // Guests/admins/etc. — no rank-range validation; whatever is passed sticks.
      User user = newUser(USER_ID);
      user.setVersion(1L);
      user.setRoles(new HashSet<>(Set.of(roleNamed("GUEST"))));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.save(user)).thenReturn(user);

      userService.updateUserAttributes(USER_ID, 17, null, null, 1L, null);

      assertEquals(17, user.getRank());
    }

    @Test
    void rankNull_skipsRankSetterEntirely() {
      // Edge: leaving rank == null in the request -> existing rank preserved.
      User user = newUser(USER_ID);
      user.setVersion(1L);
      user.setRank(10);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.save(user)).thenReturn(user);

      userService.updateUserAttributes(USER_ID, null, "desc", null, 1L, null);

      assertEquals(
          10, user.getRank(), "existing rank must be preserved when the update doesn't touch rank");
    }

    @Test
    void officerRankBoundaries_areInclusive() {
      // rank=1 (low boundary) and rank=12 (high boundary) must both pass.
      assertOfficerRankPasses(1);
      assertOfficerRankPasses(12);
    }

    @Test
    void squadronMemberRankBoundaries_areInclusive() {
      assertSquadronRankPasses(13);
      assertSquadronRankPasses(20);
    }

    @Test
    void officerRank_zero_isRejected() {
      // Just below the 1-12 range.
      User user = newUser(USER_ID);
      user.setVersion(1L);
      user.setRoles(new HashSet<>(Set.of(roleNamed("OFFICER"))));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

      assertThrows(
          IllegalArgumentException.class,
          () -> userService.updateUserAttributes(USER_ID, 0, null, null, 1L, null));
    }

    @Test
    void squadronRank_21_isRejected() {
      // Just above the 13-20 range.
      User user = newUser(USER_ID);
      user.setVersion(1L);
      user.setRoles(new HashSet<>(Set.of(roleNamed("KRT_MEMBER"))));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

      assertThrows(
          IllegalArgumentException.class,
          () -> userService.updateUserAttributes(USER_ID, 21, null, null, 1L, null));
    }

    private void assertOfficerRankPasses(int rank) {
      User user = newUser(USER_ID);
      user.setVersion(1L);
      user.setRoles(new HashSet<>(Set.of(roleNamed("OFFICER"))));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.save(user)).thenReturn(user);

      userService.updateUserAttributes(USER_ID, rank, null, null, 1L, null);
      assertEquals(rank, user.getRank());
      org.mockito.Mockito.reset(userRepository);
    }

    private void assertSquadronRankPasses(int rank) {
      User user = newUser(USER_ID);
      user.setVersion(1L);
      user.setRoles(new HashSet<>(Set.of(roleNamed("KRT_MEMBER"))));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.save(user)).thenReturn(user);

      userService.updateUserAttributes(USER_ID, rank, null, null, 1L, null);
      assertEquals(rank, user.getRank());
      org.mockito.Mockito.reset(userRepository);
    }
  }

  // ---------------------------------------------------------------
  // updateReadAnnouncement
  // ---------------------------------------------------------------

  @Nested
  class UpdateReadAnnouncementTests {

    @Test
    void throwsNotFoundException_whenUserMissing() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> userService.updateReadAnnouncement(USER_ID, UUID.randomUUID()));
    }

    @Test
    void happyPath_setsAnnouncementIdAndSaves() {
      User user = newUser(USER_ID);
      UUID announcementId = UUID.randomUUID();
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRepository.save(user)).thenReturn(user);

      userService.updateReadAnnouncement(USER_ID, announcementId);

      assertEquals(announcementId, user.getLastReadAnnouncementId());
    }
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
      userService.markMissingUsers(List.of());

      verify(userRepository, never()).markMissingUsers(any());
    }

    @Test
    void nonEmptyInput_delegatesToRepository() {
      List<UUID> ids = List.of(USER_ID);

      userService.markMissingUsers(ids);

      verify(userRepository).markMissingUsers(ids);
    }
  }

  // ---------------------------------------------------------------
  // isUsernameOrDisplayNameTaken / findMatchesByExactName
  // ---------------------------------------------------------------

  @Nested
  class NameLookupTests {

    @Test
    void findMatchesByExactName_blankInput_returnsEmptyWithoutHittingRepo() {
      assertTrue(userService.findMatchesByExactName("   ").isEmpty());

      verify(userRepository, never())
          .findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase(any(), any());
    }

    @Test
    void findMatchesByExactName_trimsBeforeQuerying() {
      User u = newUser(USER_ID);
      when(userRepository.findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase("alice", "alice"))
          .thenReturn(List.of(u));

      assertEquals(List.of(u), userService.findMatchesByExactName("   alice   "));
    }

    @Test
    void isUsernameOrDisplayNameTaken_falseWhenBlank() {
      assertFalse(userService.isUsernameOrDisplayNameTaken("   "));
    }

    @Test
    void isUsernameOrDisplayNameTaken_trueWhenMatchExists() {
      when(userRepository.findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase("alice", "alice"))
          .thenReturn(List.of(newUser(USER_ID)));

      assertTrue(userService.isUsernameOrDisplayNameTaken("alice"));
    }

    @Test
    void isUsernameOrDisplayNameTaken_falseWhenNoMatch() {
      when(userRepository.findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase("ghost", "ghost"))
          .thenReturn(List.of());

      assertFalse(userService.isUsernameOrDisplayNameTaken("ghost"));
    }
  }

  // ---------------------------------------------------------------
  // searchByUsername / findAll / findAllReference
  // ---------------------------------------------------------------

  @Nested
  class SearchAndListDelegateTests {

    @Test
    void searchByUsername_unpaged_delegatesToRepository() {
      when(ownerScopeService.currentUserListScopeSquadronIds()).thenReturn(null);
      when(userRepository.searchScopedList("ali", null)).thenReturn(List.of(newUser(USER_ID)));

      assertEquals(1, userService.searchByUsername("ali").size());
    }

    @Test
    void searchByUsername_paged_delegatesToRepository() {
      PageRequest pr = PageRequest.of(0, 10);
      Page<User> page = new PageImpl<>(List.of(newUser(USER_ID)));
      when(ownerScopeService.currentUserListScopeSquadronIds()).thenReturn(null);
      when(userRepository.searchScoped("ali", null, pr)).thenReturn(page);

      assertEquals(1, userService.searchByUsername("ali", pr).getTotalElements());
    }

    @Test
    void findAllReference_delegatesToRepository() {
      UserReferenceDto dto = new UserReferenceDto(USER_ID, "alice", null, null, null);
      when(ownerScopeService.currentUserListScopeSquadronIds()).thenReturn(null);
      when(userRepository.findAllReferenceScoped(null)).thenReturn(List.of(dto));

      assertEquals(List.of(dto), userService.findAllReference());
    }

    @Test
    void findAllPaged_delegatesToRepository() {
      PageRequest pr = PageRequest.of(0, 10);
      Page<User> page = new PageImpl<>(List.of(newUser(USER_ID)));
      when(ownerScopeService.currentUserListScopeSquadronIds()).thenReturn(null);
      when(userRepository.findAllScoped(null, pr)).thenReturn(page);

      assertSame(page, userService.findAll(pr));
    }

    @Test
    void findAllReference_inFocusedMode_passesScopeToRepository() {
      UUID scope = UUID.randomUUID();
      java.util.Set<UUID> scopeSet = java.util.Set.of(scope);
      UserReferenceDto dto = new UserReferenceDto(USER_ID, "alice", null, null, null);
      when(ownerScopeService.currentUserListScopeSquadronIds()).thenReturn(scopeSet);
      when(userRepository.findAllReferenceScoped(scopeSet)).thenReturn(List.of(dto));

      assertEquals(List.of(dto), userService.findAllReference());
    }
  }

  // ---------------------------------------------------------------
  // deleteUser — fallback to getCurrentUser when findAllAdmins yields no usable admin
  // ---------------------------------------------------------------

  @Nested
  class DeleteUserFallbackTests {

    @Test
    void fallsBackToCurrentUser_whenCurrentUserIsAdmin_andFindAllAdminsHasNoOther() {
      // The user being deleted IS the only "admin" in findAllAdmins() — they
      // get filtered out of that list. The fallback resolves to the current
      // logged-in admin, which is a different user with the ADMIN role.
      User toDelete = newUser(USER_ID);
      toDelete.setInKeycloak(false);
      // toDelete also has ADMIN role — that's the scenario being tested
      toDelete.setRoles(new HashSet<>(Set.of(roleNamed("ADMIN"))));

      UUID currentAdminId = UUID.randomUUID();
      User currentAdmin = newUser(currentAdminId);
      currentAdmin.setRoles(new HashSet<>(Set.of(roleNamed("ADMIN"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(toDelete));
      // findAllAdmins returns only the user being deleted -> filtered out.
      when(userRepository.findAllAdmins()).thenReturn(List.of(toDelete));

      // Stub getCurrentUser via AuthHelperService + the second findById call.
      Jwt jwt = newJwt(currentAdminId.toString());
      Authentication auth = new UsernamePasswordAuthenticationToken(jwt, "n/a", List.of());
      when(authHelperService.rawAuthentication()).thenReturn(auth);
      when(userRepository.findById(currentAdminId)).thenReturn(Optional.of(currentAdmin));
      when(orgUnitBankResponsibilityServiceProvider.getObject())
          .thenReturn(orgUnitBankResponsibilityService);
      when(orgUnitBankResponsibilityService.snapshotResponsibleHoldersForUser(any()))
          .thenReturn(Map.of());

      userService.deleteUser(USER_ID);

      verify(inventoryItemRepository).updateOwner(toDelete, currentAdmin);
      verify(userRepository).delete(toDelete);
    }

    @Test
    void throws_whenCurrentUserIsAlsoTheUserBeingDeleted() {
      // The user trying to delete themselves can't be their own reassignment
      // target — must throw.
      User toDelete = newUser(USER_ID);
      toDelete.setInKeycloak(false);
      toDelete.setRoles(new HashSet<>(Set.of(roleNamed("ADMIN"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(toDelete));
      when(userRepository.findAllAdmins()).thenReturn(List.of(toDelete));

      Jwt jwt = newJwt(USER_ID.toString());
      Authentication auth = new UsernamePasswordAuthenticationToken(jwt, "n/a", List.of());
      when(authHelperService.rawAuthentication()).thenReturn(auth);

      assertThrows(IllegalStateException.class, () -> userService.deleteUser(USER_ID));
      verify(userRepository, never()).delete(any());
    }

    @Test
    void throws_whenCurrentUserIsNotAdmin() {
      // findAllAdmins has only the user being deleted; current user is logged in
      // but has no ADMIN role -> no fallback, throw.
      User toDelete = newUser(USER_ID);
      toDelete.setInKeycloak(false);

      UUID currentId = UUID.randomUUID();
      User current = newUser(currentId);
      current.setRoles(new HashSet<>(Set.of(roleNamed("KRT_MEMBER"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(toDelete));
      when(userRepository.findAllAdmins()).thenReturn(List.of(toDelete));

      Jwt jwt = newJwt(currentId.toString());
      Authentication auth = new UsernamePasswordAuthenticationToken(jwt, "n/a", List.of());
      when(authHelperService.rawAuthentication()).thenReturn(auth);
      when(userRepository.findById(currentId)).thenReturn(Optional.of(current));

      assertThrows(IllegalStateException.class, () -> userService.deleteUser(USER_ID));
      verify(userRepository, never()).delete(any());
    }
  }

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

  private static User newUser(UUID id) {
    User u = new User();
    u.setId(id);
    u.setUsername("user-" + id);
    u.setRoles(new HashSet<>());
    return u;
  }

  private static Role roleNamed(String name) {
    Role r = new Role();
    r.setName(name);
    return r;
  }

  private static Jwt newJwt(String subject) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(subject)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claims(c -> c.put("sub", subject))
        .build();
  }
}
