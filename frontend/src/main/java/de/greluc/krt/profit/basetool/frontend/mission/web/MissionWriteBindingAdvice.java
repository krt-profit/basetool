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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;

/** Trims the values bound to mission write forms. */
@org.springframework.web.bind.annotation.ControllerAdvice(
    assignableTypes = {
      MissionParticipantWriteController.class,
      MissionLeadershipWriteController.class,
      MissionUnitWriteController.class,
      MissionFrequencyWriteController.class,
      MissionPlanWriteController.class,
      MissionWriteController.class
    })
public class MissionWriteBindingAdvice {

  /**
   * Registers a {@code StringTrimmerEditor(true)} for this controller's mission forms, overriding
   * the global String editor.
   *
   * @param binder Spring data binder for the current request
   */
  @InitBinder
  public void initBinder(@NotNull WebDataBinder binder) {
    binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
  }
}
