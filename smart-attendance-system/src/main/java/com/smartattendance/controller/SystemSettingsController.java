package com.smartattendance.controller;

import com.smartattendance.service.SystemSettingsService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/settings/recognition")
@PreAuthorize("hasRole('ADMIN')")
public class SystemSettingsController {

    private final SystemSettingsService systemSettingsService;

    public SystemSettingsController(SystemSettingsService systemSettingsService) {
        this.systemSettingsService = systemSettingsService;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> getRecognitionSettings() {
        return ResponseEntity.ok(systemSettingsService.getSettingsSnapshot());
    }

    @PutMapping
    public ResponseEntity<Map<String, String>> updateRecognitionSettings(@Valid @RequestBody RecognitionSettingsRequest request) {
        systemSettingsService.updateRecognitionThreshold(request.recognitionThreshold());
        systemSettingsService.updateAttendanceLogPath(request.attendanceLogPath());
        return ResponseEntity.ok(systemSettingsService.getSettingsSnapshot());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecognitionSettingsRequest(
            @NotNull(message = "Recognition threshold is required")
            @DecimalMin(value = "0.0", inclusive = true, message = "Recognition threshold must be at least 0")
            @DecimalMax(value = "100.0", inclusive = true, message = "Recognition threshold cannot exceed 100")
            BigDecimal recognitionThreshold,

            @NotBlank(message = "Attendance log path is required")
            String attendanceLogPath
    ) {}
}
