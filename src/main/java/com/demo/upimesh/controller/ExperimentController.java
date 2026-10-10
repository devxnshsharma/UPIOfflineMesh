package com.demo.upimesh.controller;

import com.demo.upimesh.config.AdminOnly;
import com.demo.upimesh.dto.ExperimentInfo;
import com.demo.upimesh.dto.ExperimentRequest;
import com.demo.upimesh.dto.ExperimentResult;
import com.demo.upimesh.service.ExperimentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService experiments;

    public ExperimentController(ExperimentService experiments) {
        this.experiments = experiments;
    }

    @GetMapping("/types")
    public List<ExperimentInfo> types() {
        return experiments.types();
    }

    /** Most recent results first. */
    @GetMapping
    public List<ExperimentResult> history() {
        return experiments.history();
    }

    @PostMapping("/run")
    @AdminOnly
    public ExperimentResult run(@Valid @RequestBody ExperimentRequest request) {
        return experiments.run(request);
    }
}
