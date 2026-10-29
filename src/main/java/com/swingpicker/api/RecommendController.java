package com.swingpicker.api;

import com.swingpicker.core.RecommendationService;
import com.swingpicker.domain.Recommendation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RecommendController {

    public record RecommendRequest(@Positive double amount) {
    }

    private final RecommendationService service;

    public RecommendController(RecommendationService service) {
        this.service = service;
    }

    /** POST /api/recommend {"amount": 10000} */
    @PostMapping("/recommend")
    public Recommendation recommend(@Valid @RequestBody RecommendRequest request) {
        return service.recommend(request.amount());
    }
}
