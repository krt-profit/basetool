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

package de.greluc.krt.profit.basetool.backend.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.module.SimpleModule;

/**
 * Customizes Spring Boot's primary Jackson 3 {@code JsonMapper} for the REST layer.
 *
 * <p>Registers {@link NormalizedStringDeserializer}, so JSON strings are normalized like form posts
 * in {@link GlobalBindingAdvice}, and sets {@code FAIL_ON_NULL_FOR_PRIMITIVES = false}, so an
 * absent primitive in a request body takes its type default instead of failing with 400.
 */
@Configuration
public class JacksonConfig {

  /**
   * Registers the normalized-string module and disables {@code FAIL_ON_NULL_FOR_PRIMITIVES} on the
   * builder of the primary {@code JsonMapper}.
   *
   * @return the customizer applied to the primary mapper's builder
   */
  @Bean
  public JsonMapperBuilderCustomizer appJsonMapperBuilderCustomizer() {
    return builder ->
        builder
            .addModule(normalizedStringModule())
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
  }

  /**
   * Builds the Jackson 3 module that installs {@link NormalizedStringDeserializer} for every JSON
   * {@code String} field.
   *
   * @return a module wiring {@link NormalizedStringDeserializer} as the {@code String} deserializer
   */
  @NotNull
  private static SimpleModule normalizedStringModule() {
    SimpleModule module = new SimpleModule();
    module.addDeserializer(String.class, new NormalizedStringDeserializer());
    return module;
  }
}
