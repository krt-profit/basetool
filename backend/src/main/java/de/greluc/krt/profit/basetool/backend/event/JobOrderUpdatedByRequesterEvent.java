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

package de.greluc.krt.profit.basetool.backend.event;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Domain event published right after a requesting owner (Auftraggeber) commits an edit to one of
 * their own job orders (REQ-ORDERS-023) — a quantity change, an add/remove of a not-yet-delivered
 * item or material, or a comment edit. Carries the scalars the notification pipeline needs (the
 * responsible / requesting org units, the display id and handle for rendering, and the editing
 * actor to exclude) so the after-commit listener never touches the managed {@link
 * de.greluc.krt.profit.basetool.backend.model.JobOrder}.
 *
 * <p>The recipients are resolved from the {@link NotificationContextRole#RESPONSIBLE} org unit
 * (officers + leads of the processing squadron/SK) by the seeded default rule; the requesting org
 * unit is carried both as the {@link NotificationContextRole#REQUESTING} context and as the {@code
 * requester} render parameter so the message can name who changed the order without disclosing the
 * editing member's personal name (no PII in params).
 *
 * @param jobOrderId the edited job order's id (also the notification's loose entity id)
 * @param displayId the human-facing sequential display id
 * @param handle the order handle/title, or {@code null}
 * @param responsibleOrgUnit the processing org unit (id + kind) — the recipient-resolution target
 * @param responsibleOrgUnitShorthand the processing org unit's shorthand, for rendering
 * @param requestingOrgUnit the requesting/customer org unit (id + kind)
 * @param requestingOrgUnitShorthand the requesting org unit's shorthand, for rendering
 * @param actorSub the editing user's sub, or {@code null} when not resolvable
 */
public record JobOrderUpdatedByRequesterEvent(
    UUID jobOrderId,
    Integer displayId,
    String handle,
    OrgUnitRef responsibleOrgUnit,
    String responsibleOrgUnitShorthand,
    OrgUnitRef requestingOrgUnit,
    String requestingOrgUnitShorthand,
    UUID actorSub)
    implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notifications (shared with the create event). */
  public static final String ENTITY_TYPE = "JOB_ORDER";

  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.JOB_ORDER_UPDATED_BY_REQUESTER;
  }

  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    Map<NotificationContextRole, OrgUnitRef> map =
        new LinkedHashMap<>(Map.of(NotificationContextRole.RESPONSIBLE, responsibleOrgUnit));
    if (requestingOrgUnit != null) {
      map.put(NotificationContextRole.REQUESTING, requestingOrgUnit);
    }
    return map;
  }

  @Override
  public String entityType() {
    return ENTITY_TYPE;
  }

  @Override
  public UUID entityId() {
    return jobOrderId;
  }

  @Override
  public Map<String, String> renderParams() {
    Map<String, String> params = new LinkedHashMap<>();
    if (displayId != null) {
      params.put("displayId", String.valueOf(displayId));
    }
    if (handle != null && !handle.isBlank()) {
      params.put("handle", handle);
    }
    if (responsibleOrgUnitShorthand != null && !responsibleOrgUnitShorthand.isBlank()) {
      params.put("orgUnit", responsibleOrgUnitShorthand);
    }
    if (requestingOrgUnitShorthand != null && !requestingOrgUnitShorthand.isBlank()) {
      params.put("requester", requestingOrgUnitShorthand);
    }
    return params;
  }
}
