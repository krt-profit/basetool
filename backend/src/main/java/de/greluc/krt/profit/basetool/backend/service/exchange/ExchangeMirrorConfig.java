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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeMirrorProperties;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the registry mirror: Redis when {@code app.exchange.mirror.enabled=true}, otherwise a
 * mirror that stores nothing, so the backend starts without Redis; a document an earlier run left
 * behind is then switched off by {@link ExchangeRegistryMirrorClosure}.
 */
@Slf4j
@Configuration
public class ExchangeMirrorConfig {

  /**
   * The Redis mirror, when it is switched on.
   *
   * @param redisTemplate the string template
   * @param properties the key and the switch
   * @return the mirror
   */
  @NotNull
  @Bean
  @ConditionalOnProperty(prefix = "app.exchange.mirror", name = "enabled", havingValue = "true")
  public ExchangeRegistryMirror redisExchangeRegistryMirror(
      StringRedisTemplate redisTemplate, ExchangeMirrorProperties properties) {
    log.info("Exchange registry mirror enabled (key={})", properties.key());
    return new RedisExchangeRegistryMirror(redisTemplate, properties.key(), Clock.systemUTC());
  }

  /**
   * The Redis revocation mirror, when mirroring is switched on.
   *
   * @param redisTemplate the string template
   * @return the mirror
   */
  @NotNull
  @Bean
  @ConditionalOnProperty(prefix = "app.exchange.mirror", name = "enabled", havingValue = "true")
  public ExchangeRevocationMirror redisExchangeRevocationMirror(StringRedisTemplate redisTemplate) {
    return new RedisExchangeRevocationMirror(redisTemplate, Clock.systemUTC());
  }

  /**
   * The revocation mirror used whenever Redis mirroring is off.
   *
   * @return a mirror that stores nothing
   */
  @NotNull
  @Bean
  @ConditionalOnMissingBean(ExchangeRevocationMirror.class)
  public ExchangeRevocationMirror disabledExchangeRevocationMirror() {
    return new DisabledExchangeRevocationMirror();
  }

  /**
   * The mirror used whenever Redis mirroring is off.
   *
   * @return a mirror that stores nothing
   */
  @NotNull
  @Bean
  @ConditionalOnMissingBean(ExchangeRegistryMirror.class)
  public ExchangeRegistryMirror disabledExchangeRegistryMirror() {
    log.info(
        "Exchange registry mirror disabled; a registry document left in Redis is switched off,"
            + " so the gateway refuses every exchange request");
    return new DisabledExchangeRegistryMirror();
  }
}
