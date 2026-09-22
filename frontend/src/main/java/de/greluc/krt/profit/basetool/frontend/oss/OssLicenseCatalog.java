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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The third-party licence notice behind {@code /licenses} (REQ-UI-021), read once at startup from
 * the report the build generated for exactly this jar.
 *
 * <p>The report is a build output ({@code :frontend:generateOssLicenses}), never a committed file,
 * so it lists the versions of the build that serves it. A missing or unreadable report is logged
 * and turned into an unavailable notice rather than a failed startup: the page is a notice, and an
 * application that refuses to boot over it would take every other page down with it. {@code
 * OssLicenseCatalogTest} is what guarantees the file exists in a real build.
 */
@Slf4j
@Component
public class OssLicenseCatalog {

  /** Where {@code :frontend:generateOssLicenses} puts the report on the classpath. */
  static final String REPORT_LOCATION = "oss/oss-licenses.json";

  /** Group order: licence name as a reader sees it, case-insensitive. */
  private static final Comparator<OssLicenseGroup> BY_LICENSE_NAME =
      Comparator.comparing(
          (OssLicenseGroup g) -> g.license().name().toLowerCase(Locale.ROOT),
          Comparator.naturalOrder());

  /** Row order inside a group: coordinate or product name ignoring case, then version. */
  private static final Comparator<OssComponent> BY_NAME_THEN_VERSION =
      Comparator.comparing((OssComponent c) -> c.name().toLowerCase(Locale.ROOT))
          .thenComparing(OssComponent::version);

  /** The loaded report, or {@code null} when it could not be read. */
  private final @Nullable OssLicenseReport report;

  /** The report's components grouped by licence; empty when {@link #report} is {@code null}. */
  private final @NotNull @Unmodifiable List<OssLicenseGroup> groups;

  /**
   * Loads the report from {@link #REPORT_LOCATION} on the classpath.
   *
   * <p>{@code @Autowired} because the class has a second, package-private constructor for tests;
   * with two, Spring would otherwise look for a default one and fail the context.
   *
   * @param objectMapper the application's Jackson 3 mapper, used to read the report once
   */
  @Autowired
  public OssLicenseCatalog(@NotNull ObjectMapper objectMapper) {
    this(objectMapper, new ClassPathResource(REPORT_LOCATION));
  }

  /**
   * Loads the report from an explicit resource — the seam the unit test uses to feed a missing,
   * broken or hand-written report.
   *
   * @param objectMapper the mapper that reads the JSON
   * @param resource where the report lives
   */
  OssLicenseCatalog(@NotNull ObjectMapper objectMapper, @NotNull Resource resource) {
    this.report = load(objectMapper, resource);
    this.groups = report == null ? List.of() : group(report.components());
  }

  /**
   * Whether the report was read; {@code false} renders the unavailable notice instead of the list.
   *
   * @return {@code true} when the report loaded
   */
  public boolean isAvailable() {
    return report != null;
  }

  /**
   * The licence sections of the page, sorted by licence name; a component offered under several
   * licences appears in each of their sections.
   *
   * @return the groups, empty when the report is unavailable
   */
  public @NotNull @Unmodifiable List<OssLicenseGroup> groups() {
    return groups;
  }

  /**
   * How many distinct components the report lists — each counted once, however many licences it is
   * offered under.
   *
   * @return the component count, {@code 0} when the report is unavailable
   */
  public int componentCount() {
    return report == null ? 0 : report.components().size();
  }

  /**
   * The tool that generated the library half of the report, for the page's closing line.
   *
   * @return e.g. {@code licensee 1.14.1}, or {@code null} when unavailable or not stated
   */
  public @Nullable String generator() {
    return report == null ? null : report.generator();
  }

  /**
   * Reads and parses the report, answering {@code null} on any failure.
   *
   * @param objectMapper the mapper that reads the JSON
   * @param resource where the report lives
   * @return the report, or {@code null} when it is missing or unreadable
   */
  private static @Nullable OssLicenseReport load(
      @NotNull ObjectMapper objectMapper, @NotNull Resource resource) {
    if (!resource.exists()) {
      log.warn(
          "Third-party licence report {} is missing; /licenses shows the unavailable notice",
          REPORT_LOCATION);
      return null;
    }
    try (InputStream in = resource.getInputStream()) {
      return objectMapper.readValue(in, OssLicenseReport.class);
    } catch (IOException | JacksonException e) {
      log.warn(
          "Third-party licence report {} could not be read; /licenses shows the unavailable"
              + " notice",
          REPORT_LOCATION,
          e);
      return null;
    }
  }

  /**
   * Groups components by licence, keyed by SPDX identifier where there is one.
   *
   * @param components the report's components
   * @return one group per licence, each sorted, the list sorted by licence name
   */
  private static @NotNull @Unmodifiable List<OssLicenseGroup> group(
      @NotNull List<OssComponent> components) {
    Map<String, OssLicense> licenseByKey = new LinkedHashMap<>();
    Map<String, List<OssComponent>> componentsByKey = new LinkedHashMap<>();
    for (OssComponent component : components) {
      for (OssLicense license : component.licenses()) {
        String key = license.groupKey();
        licenseByKey.putIfAbsent(key, license);
        componentsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(component);
      }
    }
    List<OssLicenseGroup> result = new ArrayList<>();
    componentsByKey.forEach(
        (key, members) -> {
          members.sort(BY_NAME_THEN_VERSION);
          result.add(new OssLicenseGroup(licenseByKey.get(key), members));
        });
    result.sort(BY_LICENSE_NAME);
    return List.copyOf(result);
  }
}
