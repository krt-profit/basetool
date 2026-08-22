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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.model.dto.NotificationBulkResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationUnreadCountDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.NotificationService;
import de.greluc.krt.profit.basetool.backend.service.NotificationStreamService;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST surface over the caller's own notification inbox. Every endpoint derives the recipient from
 * the JWT {@code sub} — never from the request — so a caller can only ever read, mark, or delete
 * their own notifications (REQ-NOTIF-004). The recipient is the authorization boundary, so the
 * write endpoints carry no body: marking read and deleting are addressed purely by path id.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Notifications", description = "Per-user notification inbox.")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class NotificationController {

  private final NotificationService service;
  private final NotificationStreamService streamService;

  /**
   * Opens a Server-Sent-Event stream for the caller (REQ-NOTIF-010). Best-effort real-time push;
   * the frontend falls back to polling if the stream is unavailable. The recipient is the JWT
   * {@code sub}, so a caller only ever streams their own notifications.
   *
   * <p><strong>{@code X-Accel-Buffering: no} is part of the response, not decoration.</strong> An
   * nginx with response buffering on holds a trickling body in its buffers, and an SSE stream is
   * exactly that: a few bytes every twenty seconds. The events then arrive late, in bursts, or —
   * for a client that gives up first — not at all, and the failure looks like "push does not work
   * on this network" rather than like a proxy setting. nginx honours this header per response, so
   * the guarantee travels with the endpoint instead of depending on a vhost's defaults; the API
   * vhost the Android app uses is a second proxy host whose defaults nobody verified.
   *
   * @param recipientSub the caller's id, resolved from the JWT subject claim
   * @param response the servlet response, used only to set the no-buffering header
   * @return the SSE emitter registered for the caller
   */
  @GetMapping("/stream")
  @Operation(summary = "Subscribe to the caller's notification stream (Server-Sent Events).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "SSE stream opened."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public SseEmitter stream(@CurrentUserId UUID recipientSub, HttpServletResponse response) {
    response.setHeader("X-Accel-Buffering", "no");
    return streamService.subscribe(recipientSub);
  }

  /**
   * Lists the caller's notifications, newest first by default.
   *
   * @param page zero-based page index (optional)
   * @param size page size (optional)
   * @param sort sort expression (optional; whitelisted fields only)
   * @param recipientSub the caller's id, resolved from the JWT subject claim
   * @return a page of the caller's notifications
   */
  @GetMapping
  @Operation(summary = "List the caller's notifications (paginated, sortable).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Paginated list of the caller's notifications."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public PageResponse<NotificationDto> list(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @CurrentUserId UUID recipientSub) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            NotificationService.SORTABLE_FIELDS,
            NotificationService.DEFAULT_SORT_FIELD);
    Page<NotificationDto> result = service.listOwn(recipientSub, pageable);
    return PageResponse.of(result);
  }

  /**
   * Returns the caller's most recent notifications for the bell dropdown.
   *
   * @param limit maximum number of entries (optional; clamped server-side)
   * @param recipientSub the caller's id, resolved from the JWT subject claim
   * @return the most-recent-first list of notifications
   */
  @GetMapping("/recent")
  @Operation(summary = "List the caller's most recent notifications (for the bell dropdown).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Most-recent-first list."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public List<NotificationDto> recent(
      @RequestParam(required = false, defaultValue = "10") int limit,
      @CurrentUserId UUID recipientSub) {
    return service.listRecentOwn(recipientSub, limit);
  }

  /**
   * Returns the caller's unread count for the always-on bell badge.
   *
   * @param recipientSub the caller's id, resolved from the JWT subject claim
   * @return the unread count payload
   */
  @GetMapping("/unread-count")
  @Operation(summary = "Count the caller's unread notifications (always-on bell badge).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Unread count."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public NotificationUnreadCountDto unreadCount(@CurrentUserId UUID recipientSub) {
    return new NotificationUnreadCountDto(service.unreadCount(recipientSub));
  }

  /**
   * Marks one of the caller's notifications read.
   *
   * @param id notification id
   * @param recipientSub the caller's id, resolved from the JWT subject claim
   * @return the updated notification DTO
   */
  @PostMapping("/{id}/read")
  @Operation(summary = "Mark one of the caller's notifications read.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Notification marked read."),
    @ApiResponse(responseCode = "404", description = "Not found or not owned by caller.")
  })
  public NotificationDto markRead(@PathVariable UUID id, @CurrentUserId UUID recipientSub) {
    return service.markRead(recipientSub, id);
  }

  /**
   * Marks every unread notification of the caller read.
   *
   * @param recipientSub the caller's id, resolved from the JWT subject claim
   * @return the bulk result (count updated + resulting unread count of zero)
   */
  @PostMapping("/read-all")
  @Operation(summary = "Mark all of the caller's notifications read.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "All notifications marked read."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public NotificationBulkResultDto markAllRead(@CurrentUserId UUID recipientSub) {
    int affected = service.markAllRead(recipientSub);
    return new NotificationBulkResultDto(affected, service.unreadCount(recipientSub));
  }

  /**
   * Deletes one of the caller's notifications, whether read or unread (REQ-NOTIF-005).
   *
   * @param id notification id
   * @param recipientSub the caller's id, resolved from the JWT subject claim
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete one of the caller's notifications (read or unread).")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Notification deleted."),
    @ApiResponse(responseCode = "404", description = "Not found or not owned by caller.")
  })
  public void delete(@PathVariable UUID id, @CurrentUserId UUID recipientSub) {
    service.deleteOwn(recipientSub, id);
  }

  /**
   * Deletes every <em>read</em> notification of the caller (the "clear read" action).
   *
   * @param recipientSub the caller's id, resolved from the JWT subject claim
   * @return the bulk result (count deleted + remaining unread count)
   */
  @DeleteMapping("/read")
  @Operation(summary = "Delete all of the caller's already-read notifications.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Read notifications cleared."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public NotificationBulkResultDto deleteAllRead(@CurrentUserId UUID recipientSub) {
    int affected = service.deleteAllRead(recipientSub);
    return new NotificationBulkResultDto(affected, service.unreadCount(recipientSub));
  }
}
