package com.sonar.trading.api;

import com.sonar.trading.domain.entity.NotificationEntity;
import com.sonar.trading.domain.repository.NotificationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 4.8: {@code GET /api/v1/notifications?after=} — `strategy` has no incoming REST, so it pulls
 * this queue on its own minute tick and relays to Telegram (stage 6).
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationsController {

    private final NotificationRepository repository;

    public NotificationsController(NotificationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<NotificationEntity> since(@RequestParam(defaultValue = "0") long after) {
        return repository.findByIdGreaterThanOrderByIdAsc(after);
    }
}
