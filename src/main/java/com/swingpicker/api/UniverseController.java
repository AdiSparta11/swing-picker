package com.swingpicker.api;

import com.swingpicker.data.UniverseLoader.Universe;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/universe : which stocks get scanned, and where the list came from. */
@RestController
@RequestMapping("/api")
public class UniverseController {

    private final Universe universe;

    public UniverseController(Universe universe) {
        this.universe = universe;
    }

    @GetMapping("/universe")
    public Universe universe() {
        return universe;
    }
}
