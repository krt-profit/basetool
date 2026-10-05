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

package de.greluc.krt.profit.basetool.frontend.notification.client;

import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationBulkResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationCountResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationRuleDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationRuleWriteRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the notification domain: the caller's inbox and the admin notification
 * rules (REQ-NOTIF-*), over {@link BackendApiClient} (plan §5.9, ADR-0032). The inbox's SSE stream
 * is a side channel of the kernel, not part of this client.
 */
@Service
@RequiredArgsConstructor
public class NotificationBackendClient {

  /** The backend's inbox endpoints. */
  private static final String NOTIFICATIONS = "/api/v1/notifications";

  /** The backend's rule endpoints. */
  private static final String RULES = "/api/v1/notification-rules";

  private static final ParameterizedTypeReference<List<NotificationDto>> NOTIFICATION_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<NotificationDto>> NOTIFICATION_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<NotificationRuleDto>> RULE_LIST =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads the caller's most recent notifications.
   *
   * @param limit how many to read
   * @return the notifications, newest first, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<NotificationDto> recent(int limit) {
    return backendApiClient.get(
        NOTIFICATIONS + "/recent?limit={limit}", NOTIFICATION_LIST, Integer.valueOf(limit));
  }

  /**
   * Reads one page of the caller's inbox, newest first.
   *
   * @param page the zero-based page index
   * @param size the page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<NotificationDto> page(int page, int size) {
    return backendApiClient.get(
        NOTIFICATIONS + "?page={page}&size={size}&sort=createdAt,desc",
        NOTIFICATION_PAGE,
        Integer.valueOf(page),
        Integer.valueOf(size));
  }

  /**
   * Reads the caller's unread count.
   *
   * @return the count, or {@code null} when the backend sent no body
   */
  @Nullable
  public NotificationCountResponse unreadCount() {
    return backendApiClient.get(NOTIFICATIONS + "/unread-count", NotificationCountResponse.class);
  }

  /**
   * Marks one notification read.
   *
   * @param id the notification
   * @return the notification as stored
   */
  @Nullable
  public NotificationDto markRead(@NotNull UUID id) {
    return backendApiClient.post(NOTIFICATIONS + "/{id}/read", null, NotificationDto.class, id);
  }

  /**
   * Marks every notification of the caller read.
   *
   * @return how many changed
   */
  @Nullable
  public NotificationBulkResultDto markAllRead() {
    return backendApiClient.post(
        NOTIFICATIONS + "/read-all", null, NotificationBulkResultDto.class);
  }

  /**
   * Deletes one notification, read or unread.
   *
   * @param id the notification
   */
  public void delete(@NotNull UUID id) {
    backendApiClient.delete(NOTIFICATIONS + "/{id}", Void.class, id);
  }

  /**
   * Deletes every already-read notification of the caller.
   *
   * @return how many were deleted
   */
  @Nullable
  public NotificationBulkResultDto clearRead() {
    return backendApiClient.delete(NOTIFICATIONS + "/read", NotificationBulkResultDto.class);
  }

  /**
   * Lists the notification rules.
   *
   * @return the rules, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<NotificationRuleDto> rules() {
    return backendApiClient.get(RULES, RULE_LIST);
  }

  /**
   * Reads one notification rule.
   *
   * @param id the rule
   * @return the rule, or {@code null} when the backend sent no body
   */
  @Nullable
  public NotificationRuleDto rule(@NotNull UUID id) {
    return backendApiClient.get(RULES + "/{id}", NotificationRuleDto.class, id);
  }

  /**
   * Creates a notification rule.
   *
   * @param request the rule
   * @return the created rule
   */
  @Nullable
  public NotificationRuleDto createRule(@Nullable NotificationRuleWriteRequest request) {
    return backendApiClient.post(RULES, request, NotificationRuleDto.class);
  }

  /**
   * Updates a notification rule, carrying the optimistic-lock version in the request.
   *
   * @param id the rule
   * @param request the rule
   * @return the updated rule
   */
  @Nullable
  public NotificationRuleDto updateRule(
      @NotNull UUID id, @Nullable NotificationRuleWriteRequest request) {
    return backendApiClient.put(RULES + "/{id}", request, NotificationRuleDto.class, id);
  }

  /**
   * Deletes a notification rule.
   *
   * @param id the rule
   */
  public void deleteRule(@NotNull UUID id) {
    backendApiClient.delete(RULES + "/{id}", Void.class, id);
  }
}
