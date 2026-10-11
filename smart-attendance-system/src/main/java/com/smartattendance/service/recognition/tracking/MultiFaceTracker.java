package com.smartattendance.service.recognition.tracking;

import com.smartattendance.service.recognition.FaceRecognitionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MultiFaceTracker {

    public List<FaceTrackComponent> groupFaces(FaceRecognitionService.FrameAnalysisResult analysis) {
        if (analysis == null || analysis.faces().isEmpty()) {
            return List.of();
        }

        Map<String, FaceGroupTrackComponent> groups = new LinkedHashMap<>();
        List<FaceTrackComponent> components = new ArrayList<>();

        for (FaceRecognitionService.DetectedFace face : analysis.faces()) {
            Optional<String> recognised = topStudent(face);
            if (recognised.isPresent()) {
                FaceGroupTrackComponent group = groups.computeIfAbsent(recognised.get(), FaceGroupTrackComponent::new);
                group.add(new SingleFaceTrackComponent(face));
            } else {
                components.add(new SingleFaceTrackComponent(face));
            }
        }

        components.addAll(groups.values());
        return components;
    }

    private Optional<String> topStudent(FaceRecognitionService.DetectedFace face) {
        if (face == null || face.candidates().isEmpty()) {
            return Optional.empty();
        }
        var candidate = face.candidates().get(0);
        if (candidate == null || candidate.studentId() == null || candidate.studentId().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(candidate.studentId());
    }
}
