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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static de.greluc.krt.profit.basetool.backend.support.OrgUnitFixtures.squadron;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link JobOrderHandoverGivers}: the members that may be named as handing a
 * handover over are the active accounts among the direct members of the order's processing unit
 * (REQ-ORDERS-079).
 */
@ExtendWith(MockitoExtension.class)
class JobOrderHandoverGiversTest {

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private UserRepository userRepository;
  @Mock private OrgUnitMembershipQueryService membershipQueryService;

  private JobOrderHandoverGivers givers;
  private UUID unitId;
  private JobOrder order;

  @BeforeEach
  void setUp() {
    givers = new JobOrderHandoverGivers(jobOrderRepository, userRepository, membershipQueryService);
    unitId = UUID.randomUUID();
    order = JobOrder.builder().id(UUID.randomUUID()).responsibleOrgUnit(squadron(unitId)).build();
  }

  @Test
  void candidatesAreTheActiveDirectMembersOrderedCaseInsensitivelyByHandle() {
    UUID alpha = UUID.randomUUID();
    UUID bravo = UUID.randomUUID();
    UUID charlie = UUID.randomUUID();
    UUID inactive = UUID.randomUUID();
    Set<UUID> members = Set.of(alpha, bravo, charlie, inactive);
    when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(membershipQueryService.findDirectMemberUserIds(unitId)).thenReturn(members);
    when(userRepository.findActiveReferencesByIds(members))
        .thenReturn(
            List.of(
                reference(charlie, "charlie"),
                reference(bravo, "Bravo"),
                reference(alpha, "alpha")));

    assertThat(givers.candidates(order.getId()))
        .containsExactly(
            new JobOrderHandoverGiverDto(alpha, "alpha"),
            new JobOrderHandoverGiverDto(bravo, "Bravo"),
            new JobOrderHandoverGiverDto(charlie, "charlie"));
  }

  @Test
  void candidatesAreEmptyForAnOrderWithoutProcessingUnitWithoutReadingMembersOrUsers() {
    JobOrder unassigned = JobOrder.builder().id(UUID.randomUUID()).build();
    when(jobOrderRepository.findById(unassigned.getId())).thenReturn(Optional.of(unassigned));

    assertThat(givers.candidates(unassigned.getId())).isEmpty();
    verifyNoInteractions(membershipQueryService, userRepository);
  }

  @Test
  void candidatesAreEmptyForAProcessingUnitWithoutMembersWithoutReadingUsers() {
    when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(membershipQueryService.findDirectMemberUserIds(unitId)).thenReturn(Set.of());

    assertThat(givers.candidates(order.getId())).isEmpty();
    verifyNoInteractions(userRepository);
  }

  @Test
  void candidatesRejectAnUnknownOrder() {
    UUID unknown = UUID.randomUUID();
    when(jobOrderRepository.findById(unknown)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> givers.candidates(unknown)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void requireReturnsAnActiveDirectMemberWithTheirDisplayName() {
    User member = user("chewie", "Chewie");
    when(membershipQueryService.findDirectMemberUserIds(unitId)).thenReturn(Set.of(member.getId()));
    when(userRepository.findPlainById(member.getId())).thenReturn(Optional.of(member));

    JobOrderHandoverGivers.Giver giver = givers.require(order, member.getId());

    assertThat(giver.user()).isSameAs(member);
    assertThat(giver.handle()).isEqualTo("Chewie");
  }

  @Test
  void requireSnapshotsTheUsernameWhenNoDisplayNameIsSet() {
    User member = user("chewie", null);
    when(membershipQueryService.findDirectMemberUserIds(unitId)).thenReturn(Set.of(member.getId()));
    when(userRepository.findPlainById(member.getId())).thenReturn(Optional.of(member));

    assertThat(givers.require(order, member.getId()).handle()).isEqualTo("chewie");
  }

  @Test
  void requireRejectsAUserOutsideTheProcessingUnitWithoutLoadingTheUser() {
    UUID outsider = UUID.randomUUID();
    when(membershipQueryService.findDirectMemberUserIds(unitId))
        .thenReturn(Set.of(UUID.randomUUID()));

    assertThatThrownBy(() -> givers.require(order, outsider))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(JobOrderStockErrors.HANDING_OVER_NOT_MEMBER);
    verifyNoInteractions(userRepository);
  }

  @Test
  void requireRejectsEveryoneOnAnOrderWithoutProcessingUnit() {
    JobOrder unassigned = JobOrder.builder().id(UUID.randomUUID()).build();

    assertThatThrownBy(() -> givers.require(unassigned, UUID.randomUUID()))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(JobOrderStockErrors.HANDING_OVER_NOT_MEMBER);
    verifyNoInteractions(membershipQueryService, userRepository);
  }

  @ParameterizedTest
  @MethodSource("inactiveAccounts")
  void requireRejectsADirectMemberWhoseAccountIsNotActive(Consumer<User> deactivation) {
    User member = user("chewie", "Chewie");
    deactivation.accept(member);
    when(membershipQueryService.findDirectMemberUserIds(unitId)).thenReturn(Set.of(member.getId()));
    when(userRepository.findPlainById(member.getId())).thenReturn(Optional.of(member));

    assertThatThrownBy(() -> givers.require(order, member.getId()))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(JobOrderStockErrors.HANDING_OVER_NOT_MEMBER);
  }

  @Test
  void requireRejectsAnUnknownUser() {
    UUID unknown = UUID.randomUUID();
    when(membershipQueryService.findDirectMemberUserIds(unitId)).thenReturn(Set.of(unknown));
    when(userRepository.findPlainById(unknown)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> givers.require(order, unknown))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(JobOrderStockErrors.HANDING_OVER_NOT_MEMBER);
  }

  static Stream<Named<Consumer<User>>> inactiveAccounts() {
    return Stream.of(
        Named.of("pending approval", user -> user.setApprovalStatus(ApprovalStatus.PENDING)),
        Named.of("rejected", user -> user.setApprovalStatus(ApprovalStatus.REJECTED)),
        Named.of("absent from Keycloak", user -> user.setInKeycloak(false)),
        Named.of("disabled in Keycloak", user -> user.setEnabledInKeycloak(false)));
  }

  private static User user(String username, @Nullable String displayName) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setDisplayName(displayName);
    return user;
  }

  private static UserReferenceDto reference(UUID id, String effectiveName) {
    return new UserReferenceDto(id, null, effectiveName, effectiveName, 1);
  }
}
