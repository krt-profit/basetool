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

package de.greluc.krt.profit.basetool.frontend.oss;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One third-party component the Basetool ships, as one row of the „Open-Source-Lizenzen“ page.
 *
 * <p>Two kinds arrive here through the same shape. A <strong>library</strong> comes from the
 * Licensee report of a shipped Gradle module, so {@link #name} is its {@code group:artifact}
 * coordinate. A <strong>bundled asset</strong> — a font, for instance — is not a Maven artifact at
 * all and comes from the hand-kept {@code frontend/oss-bundled-components.json}; its {@link #name}
 * is the product name.
 *
 * @param name the {@code group:artifact} coordinate of a library, or the product name of a bundled
 *     asset
 * @param version the exact version shipped in this build
 * @param title the POM {@code <name>} of a library or the description of a bundled asset, or {@code
 *     null} when the source gives none
 * @param url the project's source repository or home page, or {@code null} when the source gives
 *     none
 * @param modules the shipped modules that carry the component ({@code backend}, {@code frontend},
 *     {@code ingest}, {@code keycloak-spi}), sorted; never empty
 * @param licenses every licence the component is offered under; a POM that lists several means the
 *     recipient may choose any of them (Maven's reading, and Licensee's), so the component appears
 *     under each
 */
public record OssComponent(
    @NotNull String name,
    @NotNull String version,
    @Nullable String title,
    @Nullable String url,
    @NotNull @Unmodifiable List<String> modules,
    @NotNull @Unmodifiable List<OssLicense> licenses) {

  /**
   * Copies both lists so the record stays immutable whatever the deserialiser handed in.
   *
   * @param name the library coordinate or the bundled asset's product name
   * @param version the exact version shipped
   * @param title the library's POM name or the asset's description, or {@code null}
   * @param url the source repository or home page, or {@code null}
   * @param modules the shipped modules carrying the component; {@code null} is read as empty
   * @param licenses the licences the component is offered under; {@code null} is read as empty
   */
  public OssComponent {
    modules = modules == null ? List.of() : List.copyOf(modules);
    licenses = licenses == null ? List.of() : List.copyOf(licenses);
  }
}
