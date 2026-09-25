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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.NotificationRuleMapper;
import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationRule;
import de.greluc.krt.profit.basetool.backend.model.NotificationRuleSelector;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.OrgRelativeRole;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.SelectorKind;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationRuleDto;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationRuleSelectorWriteRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationRuleWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRuleRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for the admin-CRUD guards of {@link NotificationRuleService}: the optimistic-lock
 * check on update and the per-kind selector validation, including event-derived kinds stored with
 * null selector columns (REQ-NOTIF-007).
 */
@ExtendWith(MockitoExtension.class)
class NotificationRuleServiceTest {

  @Mock private NotificationRuleRepository notificationRuleRepository;

  @Mock private RoleRepository roleRepository;

  @Mock private NotificationRuleMapper notificationRuleMapper;
  @InjectMocks private NotificationRuleService notificationRuleService;

  private static NotificationRuleWriteRequest writeRequest(
      Long version, NotificationRuleSelectorWriteRequest selector) {
    return new NotificationRuleWriteRequest(
        NotificationEventType.JOB_ORDER_CREATED,
        NotificationType.JOB_ORDER_CREATED,
        "admin description",
        true,
        true,
        version,
        List.of(selector));
  }

  private static NotificationRuleSelectorWriteRequest roleSelector(String roleCode) {
    return new NotificationRuleSelectorWriteRequest(SelectorKind.ROLE, null, roleCode, null, null);
  }

  /** Makes {@code roleCode} resolve to a catalogue row, so the REQ-SEC-053 check passes. */
  private void catalogueKnows(String roleCode) {
    Role role = new Role();
    role.setCode(roleCode);
    when(roleRepository.findByCodeIgnoreCase(roleCode)).thenReturn(Optional.of(role));
  }

  private static NotificationRule ruleWithVersion(UUID id, Long version) {
    NotificationRule rule = new NotificationRule();
    rule.setId(id);
    rule.setEventType(NotificationEventType.JOB_ORDER_CREATED);
    rule.setNotificationType(NotificationType.JOB_ORDER_CREATED);
    rule.setEnabled(true);
    rule.setExcludeActor(true);
    rule.setVersion(version);
    return rule;
  }

  @Test
  void updateWithStaleVersionThrows409() {
    UUID id = UUID.randomUUID();
    NotificationRule persisted = ruleWithVersion(id, 2L);
    when(notificationRuleRepository.findByIdWithSelectors(id)).thenReturn(Optional.of(persisted));

    NotificationRuleWriteRequest staleRequest = writeRequest(1L, roleSelector("ADMIN"));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> notificationRuleService.update(id, staleRequest));
    verify(notificationRuleRepository, never()).saveAndFlush(any());
  }

  @Test
  void updateWithMatchingVersionReplacesSelectorsAndFlushes() {
    UUID id = UUID.randomUUID();
    NotificationRule persisted = ruleWithVersion(id, 2L);
    persisted.addSelector(
        NotificationRuleSelector.builder()
            .kind(SelectorKind.SPECIFIC_USER)
            .userId(UUID.randomUUID())
            .build());
    when(notificationRuleRepository.findByIdWithSelectors(id)).thenReturn(Optional.of(persisted));
    when(notificationRuleRepository.saveAndFlush(persisted)).thenReturn(persisted);
    NotificationRuleDto dto = dtoFor(persisted);
    when(notificationRuleMapper.toDto(persisted)).thenReturn(dto);
    catalogueKnows("ADMIN");

    NotificationRuleDto result =
        notificationRuleService.update(id, writeRequest(2L, roleSelector("ADMIN")));

    assertThat(result).isSameAs(dto);
    verify(notificationRuleRepository).saveAndFlush(persisted);
    assertThat(persisted.getSelectors())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.getKind()).isEqualTo(SelectorKind.ROLE);
              assertThat(s.getRoleCode()).isEqualTo("ADMIN");
            });
  }

  @Test
  void updateWithNullPersistedVersionSkipsCheckAndFlushes() {
    UUID id = UUID.randomUUID();
    NotificationRule persisted = ruleWithVersion(id, null);
    when(notificationRuleRepository.findByIdWithSelectors(id)).thenReturn(Optional.of(persisted));
    when(notificationRuleRepository.saveAndFlush(persisted)).thenReturn(persisted);
    NotificationRuleDto dto = dtoFor(persisted);
    when(notificationRuleMapper.toDto(persisted)).thenReturn(dto);
    catalogueKnows("ADMIN");

    NotificationRuleDto result =
        notificationRuleService.update(id, writeRequest(7L, roleSelector("ADMIN")));

    assertThat(result).isSameAs(dto);
    verify(notificationRuleRepository).saveAndFlush(persisted);
  }

  private static Stream<Arguments> invalidSelectors() {
    return Stream.of(
        Arguments.of(
            "SPECIFIC_USER without userId",
            new NotificationRuleSelectorWriteRequest(
                SelectorKind.SPECIFIC_USER, null, null, null, null),
            "SPECIFIC_USER selector requires a user id"),
        Arguments.of(
            "ROLE with blank roleCode",
            new NotificationRuleSelectorWriteRequest(SelectorKind.ROLE, null, "   ", null, null),
            "ROLE selector requires roleCode"),
        Arguments.of(
            "ORG_RELATIVE_ROLE without contextRole",
            new NotificationRuleSelectorWriteRequest(
                SelectorKind.ORG_RELATIVE_ROLE, null, null, OrgRelativeRole.OFFICER, null),
            "ORG_RELATIVE_ROLE selector requires orgRelativeRole and contextRole"),
        Arguments.of(
            "ORG_RELATIVE_ROLE without orgRelativeRole",
            new NotificationRuleSelectorWriteRequest(
                SelectorKind.ORG_RELATIVE_ROLE,
                null,
                null,
                null,
                NotificationContextRole.RESPONSIBLE),
            "ORG_RELATIVE_ROLE selector requires orgRelativeRole and contextRole"));
  }

  /**
   * A selector of an event-derived kind that carries a value in every column it does not read — the
   * shape a hand-crafted or stale client payload could have.
   *
   * @param kind one of the event-derived kinds
   * @return the selector with stray values in all four columns
   */
  private static NotificationRuleSelectorWriteRequest strayValuedSelector(SelectorKind kind) {
    return new NotificationRuleSelectorWriteRequest(
        kind,
        UUID.randomUUID(),
        "ADMIN",
        OrgRelativeRole.OFFICER,
        NotificationContextRole.RESPONSIBLE);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = SelectorKind.class,
      names = {"ACCOUNT_GRANT", "EVENT_RECIPIENT", "ACCOUNT_RESPONSIBLE"})
  void createAcceptsEventDerivedKindAndStoresNoColumns(SelectorKind kind) {
    NotificationRule saved = ruleWithVersion(UUID.randomUUID(), 0L);
    when(notificationRuleRepository.saveAndFlush(any(NotificationRule.class))).thenReturn(saved);
    NotificationRuleDto dto = dtoFor(saved);
    when(notificationRuleMapper.toDto(saved)).thenReturn(dto);

    NotificationRuleDto result =
        notificationRuleService.create(writeRequest(null, strayValuedSelector(kind)));

    assertThat(result).isSameAs(dto);
    ArgumentCaptor<NotificationRule> persisted = ArgumentCaptor.forClass(NotificationRule.class);
    verify(notificationRuleRepository).saveAndFlush(persisted.capture());
    assertThat(persisted.getValue().getSelectors())
        .singleElement()
        .satisfies(selector -> assertStoredWithoutColumns(selector, kind));
    verify(roleRepository, never()).findByCodeIgnoreCase(any());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = SelectorKind.class,
      names = {"ACCOUNT_GRANT", "EVENT_RECIPIENT", "ACCOUNT_RESPONSIBLE"})
  void updateAcceptsEventDerivedKindAndStoresNoColumns(SelectorKind kind) {
    UUID id = UUID.randomUUID();
    NotificationRule persisted = ruleWithVersion(id, 3L);
    persisted.addSelector(NotificationRuleSelector.builder().kind(kind).build());
    when(notificationRuleRepository.findByIdWithSelectors(id)).thenReturn(Optional.of(persisted));
    when(notificationRuleRepository.saveAndFlush(persisted)).thenReturn(persisted);
    NotificationRuleDto dto = dtoFor(persisted);
    when(notificationRuleMapper.toDto(persisted)).thenReturn(dto);

    NotificationRuleWriteRequest disable =
        new NotificationRuleWriteRequest(
            NotificationEventType.BANK_BOOKING_REQUEST_CREATED,
            NotificationType.BANK_BOOKING_REQUEST_CREATED,
            null,
            false,
            true,
            3L,
            List.of(strayValuedSelector(kind)));

    NotificationRuleDto result = notificationRuleService.update(id, disable);

    assertThat(result).isSameAs(dto);
    verify(notificationRuleRepository).saveAndFlush(persisted);
    assertThat(persisted.isEnabled()).isFalse();
    assertThat(persisted.getSelectors())
        .singleElement()
        .satisfies(selector -> assertStoredWithoutColumns(selector, kind));
  }

  /**
   * Asserts a persisted selector kept its event-derived kind and none of the four columns.
   *
   * @param selector the persisted selector
   * @param kind the kind it must carry
   */
  private static void assertStoredWithoutColumns(
      NotificationRuleSelector selector, SelectorKind kind) {
    assertThat(selector.getKind()).isEqualTo(kind);
    assertThat(selector.getUserId()).isNull();
    assertThat(selector.getRoleCode()).isNull();
    assertThat(selector.getOrgRelativeRole()).isNull();
    assertThat(selector.getContextRole()).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidSelectors")
  void createRejectsInvalidSelector(
      String label, NotificationRuleSelectorWriteRequest selector, String expectedMessage) {
    NotificationRuleWriteRequest request = writeRequest(null, selector);

    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> notificationRuleService.create(request));
    assertThat(ex.getMessage()).as(label).contains(expectedMessage);
    verify(notificationRuleRepository, never()).saveAndFlush(any());
  }

  @Test
  void createWithValidRoleSelectorPersists() {
    NotificationRule saved = ruleWithVersion(UUID.randomUUID(), 0L);
    when(notificationRuleRepository.saveAndFlush(any(NotificationRule.class))).thenReturn(saved);
    NotificationRuleDto dto = dtoFor(saved);
    when(notificationRuleMapper.toDto(saved)).thenReturn(dto);
    catalogueKnows("ADMIN");

    NotificationRuleDto result =
        notificationRuleService.create(writeRequest(null, roleSelector("ADMIN")));

    assertThat(result).isSameAs(dto);
    verify(notificationRuleRepository).saveAndFlush(any(NotificationRule.class));
  }

  @Test
  void aDifferentlyCasedRoleCodeIsAcceptedAndStoredCanonically() {
    Role role = new Role();
    role.setCode("ADMIN");
    when(roleRepository.findByCodeIgnoreCase("admin")).thenReturn(Optional.of(role));
    NotificationRule saved = ruleWithVersion(UUID.randomUUID(), 0L);
    when(notificationRuleRepository.saveAndFlush(any(NotificationRule.class))).thenReturn(saved);
    when(notificationRuleMapper.toDto(saved)).thenReturn(dtoFor(saved));

    notificationRuleService.create(writeRequest(null, roleSelector("admin")));

    ArgumentCaptor<NotificationRule> persisted = ArgumentCaptor.forClass(NotificationRule.class);
    verify(notificationRuleRepository).saveAndFlush(persisted.capture());
    assertThat(persisted.getValue().getSelectors())
        .as("stored in the catalogue's casing, not the caller's")
        .allSatisfy(selector -> assertThat(selector.getRoleCode()).isEqualTo("ADMIN"));
  }

  @Test
  void createWithUnknownRoleCodeIsRejected() {
    when(roleRepository.findByCodeIgnoreCase("GUEST")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> notificationRuleService.create(writeRequest(null, roleSelector("GUEST"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown roleCode")
        .hasMessageContaining("GUEST");

    verify(notificationRuleRepository, never()).saveAndFlush(any(NotificationRule.class));
  }

  /**
   * Builds a lightweight read DTO stand-in for the mapper's return value so the service's mapping
   * step has a concrete instance to hand back.
   *
   * @param rule the rule the DTO mirrors (its id and version are carried through)
   * @return a minimal {@link NotificationRuleDto}
   */
  private static NotificationRuleDto dtoFor(NotificationRule rule) {
    return new NotificationRuleDto(
        rule.getId(),
        NotificationEventType.JOB_ORDER_CREATED,
        NotificationType.JOB_ORDER_CREATED,
        "admin description",
        true,
        true,
        rule.getVersion(),
        null,
        null,
        List.of());
  }
}
