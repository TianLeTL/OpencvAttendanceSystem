package com.smartattendance.service.recognition.tracking;

import com.smartattendance.service.recognition.FaceRecognitionService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class FaceGroupTrackComponent implements FaceTrackComponent {

    private final String studentId;
    private final List<FaceTrackComponent> children = new ArrayList<>();

    public FaceGroupTrackComponent(String studentId) {
        this.studentId = studentId;
    }

    public void add(FaceTrackComponent component) {
        if (component != null) {
            children.add(component);
        }
    }

    @Override
    public List<FaceRecognitionService.DetectedFace> faces() {
        return Collections.unmodifiableList(children.stream()
                .flatMap(child -> child.faces().stream())
                .toList());
    }

    @Override
    public FaceRecognitionService.DetectedFace representative() {
        return children.stream()
                .map(FaceTrackComponent::representative)
                .max(Comparator.comparingDouble(this::confidenceOf))
                .orElseThrow(() -> new IllegalStateException("Empty face group"));
    }

    @Override
    public Optional<String> studentId() {
        return Optional.ofNullable(studentId);
    }

    @Override
    public double maxConfidence() {
        return children.stream()
                .mapToDouble(FaceTrackComponent::maxConfidence)
                .max()
                .orElse(0.0);
    }

    @Override
    public int size() {
        return children.stream().mapToInt(FaceTrackComponent::size).sum();
    }

    private double confidenceOf(FaceRecognitionService.DetectedFace face) {
        return face.candidates().isEmpty() ? 0.0 : face.candidates().get(0).confidence();
    }
}
