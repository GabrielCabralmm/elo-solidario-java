package br.com.fiap.elosolidario.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@Tag(name = "Status", description = "Verificação de disponibilidade da API")
public class StatusController {

    @Hidden
    @GetMapping("/")
    public ResponseEntity<Void> inicio() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/swagger-ui.html")).build();
    }

    @GetMapping("/status")
    @Operation(summary = "Indica se a API está no ar")
    public Map<String, Object> status() {
        return Map.of("aplicacao", "EloSolidário API", "status", "UP", "horario", LocalDateTime.now().toString());
    }
}
