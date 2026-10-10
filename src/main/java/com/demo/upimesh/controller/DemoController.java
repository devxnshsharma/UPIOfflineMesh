package com.demo.upimesh.controller;

import com.demo.upimesh.dto.*;
import com.demo.upimesh.service.DemoService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** The simulated sender phone and the simulated attackers. */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoService demo;

    public DemoController(DemoService demo) {
        this.demo = demo;
    }

    @PostMapping("/send")
    public SendResponse send(@Valid @RequestBody DemoSendRequest request) {
        return demo.send(request);
    }

    @PostMapping("/replay")
    public ReplayResponse replay(@Valid @RequestBody ReplayRequest request) {
        return demo.replay(request);
    }
}
