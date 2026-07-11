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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.support.NotificationFanoutProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Context-wiring guard for {@link NotificationRedisConfig} with the Redis fan-out <b>enabled</b>.
 *
 * <p>The fan-out is off by default, so the plain unit tests ({@code RedisNotificationFanoutTest})
 * never instantiate the {@link RedisMessageListenerContainer} bean — which is exactly how a wiring
 * regression shipped: the container's dispatch-executor parameter was injected by type, but the
 * backend context holds several {@link ThreadPoolTaskExecutor} beans (the async
 * uex/scWiki/import/notification/mail executors), so an un-qualified inject failed with {@code
 * NoUniqueBeanDefinitionException} and the backend crashed on startup <em>only</em> in the
 * fan-out-enabled deployments (prod, and the e2e stack, which default it {@code true}). This test
 * reproduces that multi-executor context with the fan-out enabled and asserts the container wires,
 * so the bug cannot return without a red unit test rather than a 45-minute e2e timeout.
 */
class NotificationRedisConfigContextTest {

  /**
   * Loads the config with {@code enabled=true} and two decoy {@link ThreadPoolTaskExecutor} beans
   * (on top of the config's own {@code notificationRedisListenerExecutor}), reproducing the
   * ambiguous-by-type situation, and asserts the context starts and the container bean exists.
   * Fails with an ambiguity {@code UnsatisfiedDependencyException} if the {@code @Qualifier} on the
   * container's executor parameter is removed.
   */
  @Test
  void listenerContainerWires_whenFanoutEnabled_despiteMultipleExecutorBeans() {
    new ApplicationContextRunner()
        .withPropertyValues("app.notifications.redis-fanout.enabled=true")
        .withBean(NotificationStreamService.class, () -> mock(NotificationStreamService.class))
        .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
        .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(
            NotificationFanoutProperties.class,
            () -> new NotificationFanoutProperties(true, "basetool:notify:published"))
        .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
        // Decoy executors: the real backend context holds five async ThreadPoolTaskExecutor beans,
        // so the container's executor inject is ambiguous by type — the exact multi-bean situation.
        .withBean("uexExecutor", ThreadPoolTaskExecutor.class, ThreadPoolTaskExecutor::new)
        .withBean("mailExecutor", ThreadPoolTaskExecutor.class, ThreadPoolTaskExecutor::new)
        // Keep the container from opening a real Redis subscription at lifecycle start against the
        // mock connection factory — the bean wiring, not the connection, is what this guards.
        .withBean(
            "noAutoStart", BeanPostProcessor.class, NotificationRedisConfigContextTest::noAutoStart)
        .withUserConfiguration(NotificationRedisConfig.class)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(RedisMessageListenerContainer.class));
  }

  /**
   * A {@link BeanPostProcessor} that disables auto-start on the {@link
   * RedisMessageListenerContainer} so the context refresh exercises the bean wiring without the
   * container trying to subscribe to the mock Redis at lifecycle start.
   *
   * @return the post-processor
   */
  private static BeanPostProcessor noAutoStart() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof RedisMessageListenerContainer container) {
          container.setAutoStartup(false);
        }
        return bean;
      }
    };
  }
}
