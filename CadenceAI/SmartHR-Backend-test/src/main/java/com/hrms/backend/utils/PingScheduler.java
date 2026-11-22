package com.hrms.backend.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class PingScheduler {

    @Autowired
    private RestTemplate restTemplate;

    // Public URL of THIS backend's /ping endpoint, e.g. https://your-app.onrender.com/ping
    // Free hosts put idle servers to sleep; pinging ourselves every 10 minutes keeps it awake.
    // Leave KEEPALIVE_URL unset to disable (nothing is sent anywhere).
    @Value("${keepalive.url:}")
    private String keepAliveUrl;

    // Runs every 10 minutes
    @Scheduled(fixedRate = 10 * 60 * 1000)  // in milliseconds
    public void pingServer() {
        if (keepAliveUrl == null || keepAliveUrl.isBlank()) return;
        try {
            String url = keepAliveUrl;
            String response = restTemplate.getForObject(url, String.class);
            log.info("Ping response {}",response);
        } catch (Exception e) {
            log.info("Ping failed {}",e.getMessage());
        }
    }
}
