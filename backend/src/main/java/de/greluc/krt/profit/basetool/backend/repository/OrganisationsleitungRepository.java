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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for the {@link Organisationsleitung}, the singleton top tier of the org
 * hierarchy.
 */
@Repository
public interface OrganisationsleitungRepository extends JpaRepository<Organisationsleitung, UUID> {

  /** Derived Spring-Data query — returns the OL row whose {@code shorthand} matches, if any. */
  Optional<Organisationsleitung> findByShorthand(String shorthand);

  /** Returns every active Organisationsleitung row (there is normally exactly one). */
  List<Organisationsleitung> findAllByActiveTrue();
}
