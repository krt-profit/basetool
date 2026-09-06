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
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit coverage for {@link NotificationRuleService}'s admin-CRUD guards: the optimistic-lock check
 * on {@code update} and the per-kind selector validation applied on {@code create}/{@code update}.
 *
 * <p>Pins that a stale client version raises a 409 (lost-update protection) while a matching or
 * absent persisted version proceeds to {@code saveAndFlush}, and that the admin-manageable selector
 * kinds ({@code SPECIFIC_USER}, {@code ROLE}, {@code ORG_RELATIVE_ROLE}) reject missing required
 * fields while the three seed-only kinds ({@code ACCOUNT_GRANT}, {@code ACCOUNT_RESPONSIBLE},
 * {@code EVENT_RECIPIENT}) are refused before any row is written.
 */
@ExtendWith(MockitoExtension.class)
class NotificationRuleServiceTest {

  @Mock private NotificationRuleRepository notificationRuleRepository;

  // REQ-SEC-053: a ROLE selector's code is validated against the catalogue now, so the service
  // reads the role table. The admin screen used to offer GUEST, and a rule pointed at a role that
  // does not exist addresses nobody for ever without ever saying so.
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
    when(roleRepository.findByCode(roleCode)).thenReturn(Optional.of(role));
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
    // A pre-existing stale selector that the full-replace update must clear out.
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
    // A never-versioned rule: OptimisticLock.check skips when the persisted version is null.
    NotificationRule persisted = ruleWithVersion(id, null);
    when(notificationRuleRepository.findByIdWithSelectors(id)).thenReturn(Optional.of(persisted));
    when(notificationRuleRepository.saveAndFlush(persisted)).thenReturn(persisted);
    NotificationRuleDto dto = dtoFor(persisted);
    when(notificationRuleMapper.toDto(persisted)).thenReturn(dto);
    catalogueKnows("ADMIN");

    // A non-null client version must NOT trip a 409 when the persisted version is absent.
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
            "ORG_RELATIVE_ROLE selector requires orgRelativeRole and contextRole"),
        Arguments.of(
            "seed-only ACCOUNT_GRANT",
            new NotificationRuleSelectorWriteRequest(
                SelectorKind.ACCOUNT_GRANT, null, null, null, null),
            "Unsupported selector kind"),
        Arguments.of(
            "seed-only ACCOUNT_RESPONSIBLE",
            new NotificationRuleSelectorWriteRequest(
                SelectorKind.ACCOUNT_RESPONSIBLE, null, null, null, null),
            "Unsupported selector kind"),
        Arguments.of(
            "seed-only EVENT_RECIPIENT",
            new NotificationRuleSelectorWriteRequest(
                SelectorKind.EVENT_RECIPIENT, null, null, null, null),
            "Unsupported selector kind"));
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
  void createWithUnknownRoleCodeIsRejected() {
    // REQ-SEC-053: the admin screen offered GUEST until V239 deleted it. A rule addressed at a
    // role the catalogue does not have would have been saved happily and then matched nobody, for
    // ever — a notification silently not sent is the hardest kind of defect to notice.
    when(roleRepository.findByCode("GUEST")).thenReturn(Optional.empty());

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
