package com.smartattendance.service.recognition.tracking;

import com.smartattendance.service.recognition.FaceRecognitionService;

import java.util.List;
import java.util.Optional;

public class SingleFaceTrackComponent implements FaceTrackComponent {

    private final FaceRecognitionService.DetectedFace face;

    public SingleFaceTrackComponent(FaceRecognitionService.DetectedFace face) {
        this.face = face;
    }

    @Override
    public List<FaceRecognitionService.DetectedFace> faces() {
        return List.of(face);
    }

    @Override
    public FaceRecognitionService.DetectedFace representative() {
        return face;
    }

    @Override
    public Optional<String> studentId() {
        if (face.candidates().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(face.candidates().get(0).studentId());
    }

    @Override
    public double maxConfidence() {
        return face.candidates().isEmpty() ? 0.0 : face.candidates().get(0).confidence();
    }

    @Override
    public int size() {
        return 1;
    }
}
