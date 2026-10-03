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

package de.greluc.krt.profit.basetool.architecture.fixtures.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Planted violations: an unlisted {@code permitAll()} and a peer read that skips redaction. */
@RestController
public class PublicController {

  /**
   * A public endpoint off the allow-list.
   *
   * @return a greeting
   */
  @PreAuthorize("permitAll()")
  @GetMapping("/fixture/open")
  public String open() {
    return "open";
  }

  /**
   * A peer-readable endpoint returning PII without redaction.
   *
   * @return the DTO
   */
  @PreAuthorize("isAuthenticated()")
  @GetMapping("/fixture/pii")
  public FixturePiiDto pii() {
    return new FixturePiiDto("someone@example.invalid");
  }
}
