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

package de.greluc.krt.profit.basetool.architecture.fixtures.exchange;

import de.greluc.krt.profit.basetool.architecture.fixtures.exchangeleak.RogueService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Planted violations: an exchange endpoint without the exchange gate that calls a foreign service.
 */
@RestController
public class ExchangeFixtureController {

  private RogueService rogue;

  /**
   * Reads through a foreign service.
   *
   * @return the answer
   */
  @PreAuthorize("isAuthenticated()")
  @GetMapping("/fixture/exchange")
  public String read() {
    return rogue.answer();
  }
}
