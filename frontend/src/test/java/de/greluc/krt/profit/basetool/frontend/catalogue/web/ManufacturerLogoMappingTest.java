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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed Fan Kit logo mapping names only existing PNGs by well-formed P4K GUIDs, every
 * shipped PNG is mapped, and a manufacturer without an entry has no logo (REQ-P4K-022).
 */
class ManufacturerLogoMappingTest {

  /** A file name the mapping may name: lower-case words joined by hyphens, a PNG. */
  private static final Pattern FILE_NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*\\.png$");

  /** Where the PNGs ship on the classpath. */
  private static final String PNG_PATTERN = "classpath*:static/images/manufacturers/*.png";

  private final ObjectMapper mapper = JsonMapper.builder().build();

  private Map<String, String> committedMapping() throws IOException {
    try (InputStream in =
        new ClassPathResource(ManufacturerLogos.MAPPING_LOCATION).getInputStream()) {
      return mapper.readValue(in, new TypeReference<>() {});
    }
  }

  private Set<String> shippedPngs() throws IOException {
    Resource[] pngs = new PathMatchingResourcePatternResolver().getResources(PNG_PATTERN);
    return Arrays.stream(pngs)
        .map(Resource::getFilename)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  @Test
  void everyEntryNamesAWellFormedGuidAndAnExistingPng() throws IOException {
    Map<String, String> mapping = committedMapping();
    Set<String> pngs = shippedPngs();

    assertThat(mapping.keySet())
        .allSatisfy(guid -> assertThat(UUID.fromString(guid).toString()).isEqualTo(guid));
    assertThat(mapping.values())
        .allSatisfy(file -> assertThat(file).matches(FILE_NAME))
        .allSatisfy(file -> assertThat(pngs).contains(file));
  }

  @Test
  void everyShippedPngIsMapped() throws IOException {
    assertThat(committedMapping().values()).containsAll(shippedPngs());
  }

  @Test
  void aMappedGuidHasItsPath_andAnUnmappedOneHasNone() {
    UUID mapped = UUID.fromString("cf4a74bf-eb2c-462a-9b78-f7f2724c31d2");
    ManufacturerLogos logos =
        new ManufacturerLogos(mapper, json("{\"" + mapped + "\": \"aegis-dynamics.png\"}"));

    assertThat(logos.path(mapped)).isEqualTo("/images/manufacturers/aegis-dynamics.png");
    assertThat(logos.path(UUID.randomUUID())).isNull();
    assertThat(logos.path(null)).isNull();
  }

  @Test
  void theCommittedMappingLoads() throws IOException {
    assertThat(new ManufacturerLogos(mapper).mapping()).hasSize(committedMapping().size());
  }

  @Test
  void aMalformedGuidFailsTheLoad() {
    assertThatThrownBy(() -> new ManufacturerLogos(mapper, json("{\"not-a-guid\": \"x.png\"}")))
        .isInstanceOf(IllegalStateException.class);
  }

  private static Resource json(String text) {
    return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8));
  }
}
