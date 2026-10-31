package com.moveinsync.config.controller;

import com.moveinsync.config.dto.CreateDefinitionRequest;
import com.moveinsync.config.dto.DefinitionResponse;
import com.moveinsync.config.service.ConfigDefinitionService;
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
@RequestMapping("/api/v1/definitions")
public class DefinitionController {

    private final ConfigDefinitionService definitionService;

    public DefinitionController(ConfigDefinitionService definitionService) {
        this.definitionService = definitionService;
    }

    @PostMapping
    public ResponseEntity<DefinitionResponse> create(@Valid @RequestBody CreateDefinitionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(definitionService.create(request));
    }

    @GetMapping
    public List<DefinitionResponse> list() {
        return definitionService.findAll();
    }

    @GetMapping("/{key}")
    public DefinitionResponse get(@PathVariable String key) {
        return definitionService.find(key);
    }
}
