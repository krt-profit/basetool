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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Verifies pagination settings fail startup outside the upstream API's supported interval. */
class ScWikiPropertiesTest {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void vehiclePagesAboveTheUpstreamLimitFailStartup() {
    context
        .withPropertyValues("krt.scwiki.vehicles-page-size=51")
        .run(application -> assertThat(application).hasFailed());
  }

  @Test
  void vehiclePagesAtTheUpstreamLimitStart() {
    context
        .withPropertyValues("krt.scwiki.vehicles-page-size=50")
        .run(application -> assertThat(application).hasNotFailed());
  }

  @Test
  void smallVehiclePagesAreSupported() {
    context
        .withPropertyValues("krt.scwiki.vehicles-page-size=1")
        .run(application -> assertThat(application).hasNotFailed());
  }

  /** Registers the Wiki properties and a real startup validator. */
  @Configuration
  @EnableConfigurationProperties(ScWikiProperties.class)
  static class PropertiesConfiguration {

    /**
     * Supplies bean-validation constraints during configuration binding.
     *
     * @return validator factory
     */
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }
}
