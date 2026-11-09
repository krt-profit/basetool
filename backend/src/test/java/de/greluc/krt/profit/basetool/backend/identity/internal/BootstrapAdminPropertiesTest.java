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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Binding of {@code app.bootstrap.admin-subs}, the bootstrap admin subjects (REQ-SEC-088). */
class BootstrapAdminPropertiesTest {

  private static final UUID FIRST = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID SECOND = UUID.fromString("66666666-7777-8888-9999-000000000000");

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(BootstrapConfig.class);

  @Test
  void aCommaSeparatedListBindsToTheSubjects() {
    runner
        .withPropertyValues("app.bootstrap.admin-subs=" + FIRST + "," + SECOND)
        .run(context -> {
          BootstrapAdminProperties properties = context.getBean(BootstrapAdminProperties.class);
          assertThat(properties.adminSubs()).containsExactly(FIRST, SECOND);
          assertThat(properties.isBootstrapSubject(SECOND)).isTrue();
        });
  }

  @Test
  void anEmptyValueListsNobody() {
    runner
        .withPropertyValues("app.bootstrap.admin-subs=")
        .run(context ->
            assertThat(context.getBean(BootstrapAdminProperties.class).adminSubs()).isEmpty());
  }

  @Test
  void aValueThatIsNotASubjectFailsTheStartup() {
    runner
        .withPropertyValues("app.bootstrap.admin-subs=greluc")
        .run(context -> assertThat(context).hasFailed());
  }

  /** Registers {@link BootstrapAdminProperties} and the validator that enforces its constraints. */
  @Configuration
  @EnableConfigurationProperties(BootstrapAdminProperties.class)
  static class BootstrapConfig {
    /**
     * The JSR-380 validator {@code @Validated} properties binding delegates to.
     *
     * @return a real validator factory
     */
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }
}
