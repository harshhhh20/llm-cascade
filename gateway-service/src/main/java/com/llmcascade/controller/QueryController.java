package com.llmcascade.controller;

import com.llmcascade.dto.QueryRequest;
import com.llmcascade.dto.QueryResponse;
import com.llmcascade.filter.TraceIdFilter;
import com.llmcascade.service.RouterService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final RouterService routerService;

    public QueryController(RouterService routerService) {
        this.routerService = routerService;
    }

    @PostMapping("/query")
    public QueryResponse query(@RequestBody QueryRequest request, HttpServletRequest httpRequest) {
        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.TRACE_ID_ATTR);
        return routerService.handle(request, traceId);
    }
}

