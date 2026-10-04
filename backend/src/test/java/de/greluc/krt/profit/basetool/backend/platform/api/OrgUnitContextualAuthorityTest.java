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

package de.greluc.krt.profit.basetool.backend.platform.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OrgUnitContextualAuthority} — the SPEZIALKOMMANDO_PLAN.md §6.1 contextual
 * GrantedAuthority subtype. Pins the string-form contract that {@code @PreAuthorize} SpEL matches
 * against, plus equality semantics that the {@code OwnerScopeService.hasRoleInOrgUnit} matcher
 * relies on.
 */
class OrgUnitContextualAuthorityTest {

  @Test
  void getAuthority_composesRoleAndOrgUnitId() {
    UUID orgUnit = UUID.fromString("00000000-0000-0000-0000-000000000001");
    OrgUnitContextualAuthority auth = new OrgUnitContextualAuthority("LOGISTICIAN", orgUnit);

    assertEquals("ROLE_LOGISTICIAN@00000000-0000-0000-0000-000000000001", auth.getAuthority());
  }

  @Test
  void equalsAndHashCode_followFieldEquality() {
    UUID orgUnit = UUID.randomUUID();
    OrgUnitContextualAuthority a = new OrgUnitContextualAuthority("LOGISTICIAN", orgUnit);
    OrgUnitContextualAuthority b = new OrgUnitContextualAuthority("LOGISTICIAN", orgUnit);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void differentRole_isNotEqual() {
    UUID orgUnit = UUID.randomUUID();
    OrgUnitContextualAuthority a = new OrgUnitContextualAuthority("LOGISTICIAN", orgUnit);
    OrgUnitContextualAuthority b = new OrgUnitContextualAuthority("MISSION_MANAGER", orgUnit);

    assertNotEquals(a, b);
  }

  @Test
  void differentOrgUnit_isNotEqual() {
    OrgUnitContextualAuthority a = new OrgUnitContextualAuthority("LOGISTICIAN", UUID.randomUUID());
    OrgUnitContextualAuthority b = new OrgUnitContextualAuthority("LOGISTICIAN", UUID.randomUUID());

    assertNotEquals(a, b);
  }

  @Test
  void nullRoleName_isRejected() {
    UUID orgUnit = UUID.randomUUID();
    assertThrows(NullPointerException.class, () -> new OrgUnitContextualAuthority(null, orgUnit));
  }

  @Test
  void nullOrgUnitId_isRejected() {
    assertThrows(
        NullPointerException.class, () -> new OrgUnitContextualAuthority("LOGISTICIAN", null));
  }

  @Test
  void getAuthority_isStable_acrossCalls() {
    OrgUnitContextualAuthority auth =
        new OrgUnitContextualAuthority("LOGISTICIAN", UUID.randomUUID());
    String first = auth.getAuthority();
    String second = auth.getAuthority();
    assertTrue(first.equals(second), "getAuthority() must be deterministic");
  }
}
