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

package de.greluc.krt.profit.basetool.backend.notification.internal;

import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationParamsCodec;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.HandleAnonymisationParticipant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notification module's part of the handle anonymisation: the member's name in other members'
 * notifications, as the whole value of a person parameter (REQ-SEC-062).
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationHandleAnonymisation implements HandleAnonymisationParticipant {

  static final int POSITION = 40;

  private final NotificationRepository notificationRepository;
  private final NotificationParamsCodec paramsCodec;

  @Override
  public int position() {
    return POSITION;
  }

  @Override
  public @NotNull List<String> columns() {
    return List.of("notification.params");
  }

  /**
   * Replaces, per spelling, every person parameter whose whole value is that spelling, ignoring
   * case; a text that merely contains the spelling stays.
   *
   * @param userId the member whose snapshots are erased
   * @param spellings every name the member is stored under
   * @return the notifications rewritten
   */
  @Override
  public @NotNull Map<Rewritten, Integer> anonymise(
      @NotNull UUID userId, @NotNull List<String> spellings) {
    Set<UUID> rewritten = new HashSet<>();
    for (String spelling : spellings) {
      for (NotificationRepository.ParamsView row :
          notificationRepository.findParamsLike(LikePatterns.contains(spelling))) {
        Map<String, String> params = paramsCodec.deserialize(row.getParams());
        Map<String, String> redacted =
            NotificationPersonParams.redact(params, spelling, HandleAnonymisation.SENTINEL);
        if (!redacted.equals(params)) {
          notificationRepository.replaceParams(row.getId(), paramsCodec.serialize(redacted));
          rewritten.add(row.getId());
        }
      }
    }
    Map<Rewritten, Integer> counts = new EnumMap<>(Rewritten.class);
    if (!rewritten.isEmpty()) {
      counts.put(Rewritten.NOTIFICATIONS, rewritten.size());
    }
    return counts;
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
