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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;

/**
 * Records the org-structure audit events of the Staffel, SK, Bereich and Organisationsleitung
 * writers (REQ-AUDIT-001, Rollen &amp; Mitglieder).
 *
 * <p>Each call joins the caller's transaction. The subject is the org unit with its
 * shorthand-or-name label; the details carry only kinds, {@code KIND:id} references, field names
 * and booleans. A call whose state did not change records nothing.
 */
@Service
@RequiredArgsConstructor
public class OrgStructureAuditor {

  private final AuditRecorder auditRecorder;

  /**
   * Names the editable descriptive fields whose inbound value differs from the stored one.
   *
   * @param unit the stored org unit, read before the new values are applied
   * @param name the inbound name
   * @param shorthand the inbound shorthand
   * @param description the inbound description, or {@code null}
   * @return the changed field names in the order name, shorthand, description; possibly empty
   */
  public static @Unmodifiable @NotNull List<String> changedFields(
      @NotNull OrgUnit unit,
      @Nullable String name,
      @Nullable String shorthand,
      @Nullable String description) {
    List<String> changed = new ArrayList<>(3);
    if (!Objects.equals(unit.getName(), name)) {
      changed.add("name");
    }
    if (!Objects.equals(unit.getShorthand(), shorthand)) {
      changed.add("shorthand");
    }
    if (!Objects.equals(unit.getDescription(), description)) {
      changed.add("description");
    }
    return List.copyOf(changed);
  }

  /**
   * Records {@link AuditEventType#ORG_UNIT_CREATED} with the kind, the parent reference and, when
   * set, the Bereich department.
   *
   * @param unit the persisted org unit
   */
  public void created(@NotNull OrgUnit unit) {
    AuditDetails details =
        AuditDetails.of("kind", unit.getKind()).with("parent", OrgUnitLabels.ref(unit.getParent()));
    if (unit.getDepartment() != null) {
      details.with("department", unit.getDepartment());
    }
    record(AuditEventType.ORG_UNIT_CREATED, unit, details);
  }

  /**
   * Records {@link AuditEventType#ORG_UNIT_UPDATED} naming the changed fields, never their values.
   *
   * @param unit the persisted org unit
   * @param changedFields the field names from {@link #changedFields}; nothing is recorded when
   *     empty
   */
  public void updated(@NotNull OrgUnit unit, @NotNull List<String> changedFields) {
    if (changedFields.isEmpty()) {
      return;
    }
    record(
        AuditEventType.ORG_UNIT_UPDATED,
        unit,
        AuditDetails.of("kind", unit.getKind()).with("fields", String.join(",", changedFields)));
  }

  /**
   * Records {@link AuditEventType#ORG_UNIT_DEACTIVATED} or {@link
   * AuditEventType#ORG_UNIT_REACTIVATED} when the active flag flipped.
   *
   * @param unit the persisted org unit, carrying the new active flag
   * @param wasActive the active flag before the write
   */
  public void activeChanged(@NotNull OrgUnit unit, boolean wasActive) {
    if (unit.isActive() == wasActive) {
      return;
    }
    record(
        unit.isActive() ? AuditEventType.ORG_UNIT_REACTIVATED : AuditEventType.ORG_UNIT_DEACTIVATED,
        unit,
        AuditDetails.of("kind", unit.getKind()));
  }

  /**
   * Records {@link AuditEventType#ORG_UNIT_PARENT_CHANGED} when the parent edge moved.
   *
   * @param unit the persisted org unit, carrying the new parent
   * @param previousParent the parent before the write, or {@code null}
   */
  public void parentChanged(@NotNull OrgUnit unit, @Nullable OrgUnit previousParent) {
    OrgUnit parent = unit.getParent();
    if (Objects.equals(OrgUnit.idOf(previousParent), OrgUnit.idOf(parent))) {
      return;
    }
    record(
        AuditEventType.ORG_UNIT_PARENT_CHANGED,
        unit,
        AuditDetails.of("kind", unit.getKind())
            .with("from", OrgUnitLabels.ref(previousParent))
            .with("to", OrgUnitLabels.ref(parent)));
  }

  /**
   * Appends one event with the org unit as subject and no target user.
   *
   * @param type the event type
   * @param unit the subject org unit
   * @param details the bounded details payload
   */
  private void record(
      @NotNull AuditEventType type, @NotNull OrgUnit unit, @NotNull AuditDetails details) {
    auditRecorder.record(type, unit.getId(), OrgUnitLabels.shorthandOrName(unit), null, details);
  }
}
