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

package de.greluc.krt.profit.basetool.backend.audit.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.HandleAnonymisationParticipant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The audit module's part of the handle anonymisation: the activity trail's handle snapshots. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AuditHandleAnonymisation implements HandleAnonymisationParticipant {

  static final int POSITION = 10;

  private final AuditEventRepository auditEventRepository;

  @Override
  public int position() {
    return POSITION;
  }

  @Override
  public @NotNull List<String> columns() {
    return List.of("audit_event.actor_handle", "audit_event.subject_label");
  }

  /**
   * Rewrites the actor snapshot of the member's events by id, then the subject label of every event
   * whose whole label is one of the spellings.
   *
   * @param userId the member whose snapshots are erased
   * @param spellings every name the member is stored under
   * @return the rows rewritten in {@code audit_event}
   */
  @Override
  public @NotNull Map<Rewritten, Integer> anonymise(
      @NotNull UUID userId, @NotNull List<String> spellings) {
    String sentinel = HandleAnonymisation.SENTINEL;
    int rows = auditEventRepository.anonymiseActorHandle(userId, sentinel);
    for (String name : spellings) {
      rows += auditEventRepository.anonymiseSubjectLabel(name, sentinel);
    }
    Map<Rewritten, Integer> rewritten = new EnumMap<>(Rewritten.class);
    rewritten.put(Rewritten.ACTIVITY_AUDIT, rows);
    return rewritten;
  }

  /**
   * Records nothing: the activity trail's marker is written by the orchestrator.
   *
   * @param userId the member whose snapshots were erased
   * @param rewritten the summed row counts of every participant
   */
  @Override
  public void recordAnonymised(@NotNull UUID userId, @NotNull Map<Rewritten, Integer> rewritten) {}
}
