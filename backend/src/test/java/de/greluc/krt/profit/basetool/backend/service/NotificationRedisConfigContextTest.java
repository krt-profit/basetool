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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.support.NotificationFanoutProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Context-wiring tests for {@link NotificationRedisConfig} with the Redis fan-out enabled. */
class NotificationRedisConfigContextTest {

  /**
   * The listener container wires despite several {@link ThreadPoolTaskExecutor} beans, relying on
   * the {@code @Qualifier} of its executor parameter.
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
        .withBean("uexExecutor", ThreadPoolTaskExecutor.class, ThreadPoolTaskExecutor::new)
        .withBean("mailExecutor", ThreadPoolTaskExecutor.class, ThreadPoolTaskExecutor::new)
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

  /**
   * The context starts, with the fan-out bean present and auto-start on, against a Redis that
   * refuses every connection.
   */
  @Test
  void contextStarts_whenRedisRefusesTheSubscription() {
    RedisConnectionFactory refusing = mock(RedisConnectionFactory.class);
    when(refusing.getConnection())
        .thenThrow(new RedisConnectionFailureException("Connection refused"));

    new ApplicationContextRunner()
        .withPropertyValues("app.notifications.redis-fanout.enabled=true")
        .withBean(NotificationStreamService.class, () -> mock(NotificationStreamService.class))
        .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
        .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(
            NotificationFanoutProperties.class,
            () -> new NotificationFanoutProperties(true, "basetool:notify:published"))
        .withBean(RedisConnectionFactory.class, () -> refusing)
        .withUserConfiguration(NotificationRedisConfig.class)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(RedisMessageListenerContainer.class));
  }
}
