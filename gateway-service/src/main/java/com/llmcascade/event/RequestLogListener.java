package com.llmcascade.event;

import com.llmcascade.entity.RequestLogEntity;
import com.llmcascade.repository.RequestLogRepository;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class RequestLogListener {

    private final RequestLogRepository repository;

    public RequestLogListener(RequestLogRepository repository) {
        this.repository = repository;
    }

    @Async("logExecutor")
    @EventListener
    public void onRequestLogged(RequestLogEvent event) {
        repository.save(RequestLogEntity.from(event));
    }
}

