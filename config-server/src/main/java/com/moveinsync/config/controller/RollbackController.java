package com.moveinsync.config.controller;

import com.moveinsync.config.dto.ChangeEntryResponse;
import com.moveinsync.config.dto.RollbackRequest;
import com.moveinsync.config.dto.RollbackResponse;
import com.moveinsync.config.service.RollbackService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rollbacks")
public class RollbackController {

    private final RollbackService rollbackService;

    public RollbackController(RollbackService rollbackService) {
        this.rollbackService = rollbackService;
    }

    /**
     * Rolls the configuration back to {@code targetVersion} by publishing a new change
     * set. Set {@code dryRun=true} to inspect the diff first.
     */
    @PostMapping
    public ResponseEntity<RollbackResponse> rollback(@Valid @RequestBody RollbackRequest request) {
        RollbackResponse response = rollbackService.rollback(request);
        return ResponseEntity.status(response.publishedVersion() == null
                        ? HttpStatus.OK
                        : HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{targetVersion}/preview")
    public List<ChangeEntryResponse> preview(@PathVariable long targetVersion) {
        return rollbackService.preview(targetVersion);
    }
}
