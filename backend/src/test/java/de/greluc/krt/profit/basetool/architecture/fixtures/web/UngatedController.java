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

import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Planted violations: a controller without any gate that returns entities, injects a repository and
 * the membership mapper, writes audit rows and binds a response-only DTO.
 */
@RestController
public class UngatedController {

  private FixtureRepository repository;
  private FixtureMembershipMapper membershipMapper;
  private FixtureAudit audit;

  /**
   * Returns an entity.
   *
   * @return the entity
   */
  @GetMapping("/fixture/entity")
  public FixtureEntity entity() {
    return null;
  }

  /**
   * Returns entities in a generic wrapper.
   *
   * @return the entities
   */
  @GetMapping("/fixture/entities")
  public List<FixtureEntity> entities() {
    return repository.findAll();
  }

  /**
   * Binds a response-only DTO and writes an audit row.
   *
   * @param body the body
   */
  @PostMapping("/fixture/write")
  public void write(@RequestBody ResponseOnlyFixtureDto body) {
    Objects.requireNonNull(body);
    audit.record();
  }
}
