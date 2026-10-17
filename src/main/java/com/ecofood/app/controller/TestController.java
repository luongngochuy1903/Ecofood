package com.ecofood.app.controller;

import com.ecofood.app.services.DumpCallCoreServiceImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/test")
public class TestController {

    private final DumpCallCoreServiceImpl testService;

    public TestController(DumpCallCoreServiceImpl testService) {
        this.testService = testService;
    }

    @GetMapping("/core")
    public ResponseEntity<String> testCore() {
        return ResponseEntity.ok(testService.callDump());
    }
}