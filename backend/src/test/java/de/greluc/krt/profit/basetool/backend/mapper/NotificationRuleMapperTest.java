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

package de.greluc.krt.profit.basetool.backend.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.greluc.krt.profit.basetool.backend.model.NotificationRuleSelector;
import de.greluc.krt.profit.basetool.backend.model.SelectorKind;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationRuleSelectorDto;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * Pins the one place where the entity and the wire disagree on a name.
 *
 * <p>ADR-0142 renamed {@code notification_rule_selector.user_sub} to {@code user_id} (#1640), but
 * the DTO property stays {@code userSub} until the frozen external contract can be broken with an
 * {@code @ApiDeprecation} sunset (REQ-API-009). MapStruct matches by name, so the rename silently
 * mapped the property to {@code null} and the build stayed green -- the admin rule editor would
 * have rendered every {@code SPECIFIC_USER} selector as empty. This test is why that cannot happen
 * again, and it is also what will fail loudly when the DTO property is finally renamed, which is
 * exactly the reminder that moment needs.
 */
class NotificationRuleMapperTest {

  private final NotificationRuleMapper mapper = Mappers.getMapper(NotificationRuleMapper.class);

  @Test
  @DisplayName("the entity's userId reaches the DTO's differently-named userSub property")
  void toDto_bridgesUserIdOntoTheDeprecatedUserSubProperty() {
    UUID target = UUID.fromString("11111111-2222-4333-8444-555555555555");
    NotificationRuleSelector selector =
        NotificationRuleSelector.builder().kind(SelectorKind.SPECIFIC_USER).userId(target).build();

    NotificationRuleSelectorDto dto = mapper.toDto(selector);

    assertEquals(target, dto.userSub(), "userSub must carry the entity's userId, not null");
    assertEquals(SelectorKind.SPECIFIC_USER, dto.kind());
  }

  /**
   * The other selector kinds leave the user column NULL, and the bridge must not invent a value for
   * them -- a non-null {@code userSub} on a {@code ROLE} selector would make the editor render a
   * user picker the rule does not have.
   */
  @Test
  @DisplayName("a non-user selector maps a null userSub")
  void toDto_leavesUserSubNullForRoleSelectors() {
    NotificationRuleSelector selector =
        NotificationRuleSelector.builder().kind(SelectorKind.ROLE).roleCode("ADMIN").build();

    NotificationRuleSelectorDto dto = mapper.toDto(selector);

    assertNull(dto.userSub());
    assertEquals("ADMIN", dto.roleCode());
  }
}
