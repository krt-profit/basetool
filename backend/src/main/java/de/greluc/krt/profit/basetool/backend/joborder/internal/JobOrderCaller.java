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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderReadCheck;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The authority one call of a job-order status, target or information command runs under, as its
 * caller passed it (ADR-0408).
 *
 * @param rule the writer rule that decides which side's writer the actor is
 * @param read the read check the loaded order must pass
 * @param actor who acts
 */
record JobOrderCaller(
    @NotNull JobOrderWriterRule rule,
    @NotNull JobOrderReadCheck read,
    @NotNull JobOrderActor actor) {

  /**
   * The web's caller: the contextual authorities with the honoured pin, the contextual read and the
   * request's member.
   *
   * @param member the request's member, or {@code null} when the request resolves to none
   * @return the web caller
   */
  @Contract("_ -> new")
  static @NotNull JobOrderCaller web(@Nullable UUID member) {
    return new JobOrderCaller(
        JobOrderWriterRule.CONTEXTUAL, JobOrderReadCheck.CONTEXTUAL, new JobOrderActor.Web(member));
  }
}
