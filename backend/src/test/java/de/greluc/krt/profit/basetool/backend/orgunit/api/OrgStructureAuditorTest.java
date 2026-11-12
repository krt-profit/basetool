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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link OrgStructureAuditor}: the event type, subject and bounded details of each
 * org-structure event, and that an unchanged state records nothing (REQ-AUDIT-001).
 */
@ExtendWith(MockitoExtension.class)
class OrgStructureAuditorTest {

  @Mock private AuditRecorder auditRecorder;

  private OrgStructureAuditor auditor;
  private Squadron staffel;

  @BeforeEach
  void setUp() {
    auditor = new OrgStructureAuditor(auditRecorder);
    staffel = new Squadron();
    staffel.setId(UUID.randomUUID());
    staffel.setName("Iridium");
    staffel.setShorthand("IRI");
  }

  @Test
  void created_unparentedStaffel_recordsKindAndNoParent() {
    auditor.created(staffel);

    assertThat(recorded(AuditEventType.ORG_UNIT_CREATED, staffel))
        .isEqualTo("kind=SQUADRON parent=none");
  }

  @Test
  void created_bereichWithParentAndDepartment_recordsReferenceAndDepartment() {
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(UUID.randomUUID());
    Bereich bereich = new Bereich();
    bereich.setId(UUID.randomUUID());
    bereich.setName("Profit");
    bereich.setShorthand("PRF");
    bereich.setParent(ol);
    bereich.setDepartment(Department.PROFIT);

    auditor.created(bereich);

    assertThat(recorded(AuditEventType.ORG_UNIT_CREATED, bereich))
        .isEqualTo("kind=BEREICH parent=ORGANISATIONSLEITUNG:" + ol.getId() + " department=PROFIT");
  }

  @Test
  void changedFields_namesOnlyTheDifferingFields() {
    staffel.setDescription("old");

    assertThat(OrgStructureAuditor.changedFields(staffel, "Fenris", "IRI", null))
        .containsExactly("name", "description");
    assertThat(OrgStructureAuditor.changedFields(staffel, "Iridium", "IRI", "old")).isEmpty();
  }

  @Test
  void updated_recordsFieldNamesNotValues() {
    auditor.updated(staffel, List.of("name", "shorthand"));

    assertThat(recorded(AuditEventType.ORG_UNIT_UPDATED, staffel))
        .isEqualTo("kind=SQUADRON fields=name,shorthand");
  }

  @Test
  void updated_noChangedField_recordsNothing() {
    auditor.updated(staffel, List.of());

    verifyNoInteractions(auditRecorder);
  }

  @Test
  void activeChanged_deactivation_recordsDeactivated() {
    staffel.setActive(false);

    auditor.activeChanged(staffel, true);

    assertThat(recorded(AuditEventType.ORG_UNIT_DEACTIVATED, staffel)).isEqualTo("kind=SQUADRON");
  }

  @Test
  void activeChanged_reactivation_recordsReactivated() {
    staffel.setActive(true);

    auditor.activeChanged(staffel, false);

    assertThat(recorded(AuditEventType.ORG_UNIT_REACTIVATED, staffel)).isEqualTo("kind=SQUADRON");
  }

  @Test
  void activeChanged_unchanged_recordsNothing() {
    staffel.setActive(true);

    auditor.activeChanged(staffel, true);

    verifyNoInteractions(auditRecorder);
  }

  @Test
  void parentChanged_detach_recordsFromReferenceToNone() {
    Bereich old = new Bereich();
    old.setId(UUID.randomUUID());

    auditor.parentChanged(staffel, old);

    assertThat(recorded(AuditEventType.ORG_UNIT_PARENT_CHANGED, staffel))
        .isEqualTo("kind=SQUADRON from=BEREICH:" + old.getId() + " to=none");
  }

  @Test
  void parentChanged_sameParent_recordsNothing() {
    Bereich parent = new Bereich();
    parent.setId(UUID.randomUUID());
    staffel.setParent(parent);

    auditor.parentChanged(staffel, parent);

    verifyNoInteractions(auditRecorder);
  }

  /**
   * Verifies one event of the given type about the unit, labelled by its shorthand, without a
   * target user, and returns its details.
   *
   * @param type the expected event type
   * @param unit the expected subject
   * @return the recorded details payload
   */
  private String recorded(AuditEventType type, OrgUnit unit) {
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(type),
            eq(unit.getId()),
            eq(OrgUnitLabels.shorthandOrName(unit)),
            isNull(),
            details.capture());
    return details.getValue().toString();
  }
}
