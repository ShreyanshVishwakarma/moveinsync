package com.moveinsync.config.controller;

import com.moveinsync.config.dto.ChangeSetResponse;
import com.moveinsync.config.dto.CommitChangeSetRequest;
import com.moveinsync.config.dto.VersionSummary;
import com.moveinsync.config.service.ConfigChangeService;
import com.moveinsync.config.service.ConfigQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/change-sets")
public class ChangeSetController {

    private final ConfigChangeService changeService;
    private final ConfigQueryService queryService;

    public ChangeSetController(ConfigChangeService changeService, ConfigQueryService queryService) {
        this.changeService = changeService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<ChangeSetResponse> commit(@Valid @RequestBody CommitChangeSetRequest request) {
        ChangeSetResponse response = changeService.commit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<VersionSummary> list(@RequestParam(defaultValue = "50") int limit) {
        return queryService.listVersions(limit);
    }

    @GetMapping("/{version}")
    public ChangeSetResponse get(@PathVariable long version) {
        return queryService.getChangeSet(version);
    }
}
