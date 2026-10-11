package com.smartattendance.service.recognition.tracking;

import com.smartattendance.service.recognition.FaceRecognitionService;

import java.util.List;
import java.util.Optional;

public interface FaceTrackComponent {
    List<FaceRecognitionService.DetectedFace> faces();

    FaceRecognitionService.DetectedFace representative();

    Optional<String> studentId();

    double maxConfidence();

    int size();

    default boolean isGroup() {
        return size() > 1;
    }
}
