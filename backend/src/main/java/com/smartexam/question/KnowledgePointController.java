package com.smartexam.question;

import com.smartexam.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge-points")
public class KnowledgePointController {
    private final KnowledgePointService service;

    public KnowledgePointController(KnowledgePointService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<KnowledgePointRepository.KnowledgePoint>> list() { return ApiResponse.of(service.list()); }

    @PostMapping
    public ApiResponse<KnowledgePointRepository.KnowledgePoint> create(@Valid @RequestBody Request request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.create(request.name(), request.description(), jwt.getClaim("userId")));
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgePointRepository.KnowledgePoint> update(@PathVariable long id,
            @Valid @RequestBody Request request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(service.update(id, request.name(), request.description(), jwt.getClaim("userId")));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, jwt.getClaim("userId"));
        return ResponseEntity.noContent().build();
    }

    public record Request(@NotBlank @Size(max=100) String name, @Size(max=500) String description) {}
}
