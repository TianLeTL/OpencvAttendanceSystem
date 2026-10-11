package com.smartattendance.service;

import com.smartattendance.service.recognition.FaceRecognitionService;
import com.smartattendance.service.recognition.FaceRecognitionService.BoundingBox;
import com.smartattendance.service.recognition.FaceRecognitionService.DetectedFace;
import com.smartattendance.service.recognition.FaceRecognitionService.FrameAnalysisResult;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Validates uploaded training photos to ensure they contain a single face
 * of sufficient quality before persisting.
 */
@Service
public class TrainingPhotoValidator {

    private static final Logger log = LoggerFactory.getLogger(TrainingPhotoValidator.class);

    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 320;
    private static final double MIN_FACE_COVERAGE = 0.05;
    private static final double MIN_BRIGHTNESS = 45.0;
    private static final double MAX_BRIGHTNESS = 210.0;
    private static final double MIN_DETECTION_CONFIDENCE = 0.90;
    private static final double MIN_ASPECT_RATIO = 0.65;
    private static final double MAX_ASPECT_RATIO = 1.55;

    private final FaceRecognitionService faceRecognitionService;

    public TrainingPhotoValidator(FaceRecognitionService faceRecognitionService) {
        this.faceRecognitionService = faceRecognitionService;
    }

    public ValidationResult validate(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return ValidationResult.invalid("Empty or unreadable image payload");
        }

        Mat image = null;
        Mat gray = null;
        Mat laplacian = null;
        try {
            image = decodeImage(imageBytes);
            if (image == null || image.empty()) {
                return ValidationResult.invalid("Unable to decode image");
            }

            int width = image.cols();
            int height = image.rows();
            if (width < MIN_WIDTH || height < MIN_HEIGHT) {
                return ValidationResult.invalid(String.format(
                        "Image resolution too small (%dx%d). Minimum is %dx%d",
                        width, height, MIN_WIDTH, MIN_HEIGHT));
            }

            gray = new Mat();
            opencv_imgproc.cvtColor(image, gray, opencv_imgproc.COLOR_BGR2GRAY);

            double brightnessScore = computeBrightness(gray);
            if (brightnessScore < MIN_BRIGHTNESS || brightnessScore > MAX_BRIGHTNESS) {
                return ValidationResult.invalid(String.format(
                        "Image brightness %.1f outside accepted range (%.0f-%.0f)",
                        brightnessScore, MIN_BRIGHTNESS, MAX_BRIGHTNESS));
            }

            FrameAnalysisResult analysis = analyseWithRecognition(imageBytes);
            if (analysis == null || analysis.faces().isEmpty()) {
                return ValidationResult.invalid("No face detected in photo");
            }

            if (analysis.faces().size() > 1) {
                return ValidationResult.invalid("Multiple faces detected in photo");
            }

            DetectedFace face = analysis.faces().get(0);
            if (face.detectionScore() < MIN_DETECTION_CONFIDENCE) {
                return ValidationResult.invalid(String.format(
                        "Face detection confidence %.2f below minimum %.2f",
                        face.detectionScore(), MIN_DETECTION_CONFIDENCE));
            }

            double faceCoverage = computeFaceCoverage(face, width, height);
            if (faceCoverage < MIN_FACE_COVERAGE) {
                return ValidationResult.invalid(String.format(
                        "Face occupies only %.0f%% of frame (minimum %.0f%%)",
                        faceCoverage * 100, MIN_FACE_COVERAGE * 100));
            }

            double aspectRatio = computeAspectRatio(face);
            if (aspectRatio < MIN_ASPECT_RATIO || aspectRatio > MAX_ASPECT_RATIO) {
                return ValidationResult.invalid(String.format(
                        "Face bounding box ratio %.2f outside expected range (%.2f - %.2f)",
                        aspectRatio, MIN_ASPECT_RATIO, MAX_ASPECT_RATIO));
            }

                        return ValidationResult.valid(0.0, brightnessScore, faceCoverage, face.detectionScore());
        } catch (Exception ex) {
            log.warn("Training image validation failed: {}", ex.getMessage());
            return ValidationResult.invalid("Unexpected error while validating photo: " + ex.getMessage());
        } finally {
            releaseSafe(laplacian);
            releaseSafe(gray);
            releaseSafe(image);
        }
    }

    private FrameAnalysisResult analyseWithRecognition(byte[] imageBytes) {
        try {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            return faceRecognitionService.analyseFrame(base64);
        } catch (Exception ex) {
            log.warn("analyseWithRecognition failed: {}", ex.getMessage());
            return null;
        }
    }

    private static double computeFaceCoverage(DetectedFace face, int imageWidth, int imageHeight) {
        if (face == null || imageWidth <= 0 || imageHeight <= 0) {
            return 0.0;
        }
        BoundingBox box = face.box();
        double faceArea = Math.abs(box.width() * box.height());
        double frameArea = (double) imageWidth * imageHeight;
        if (frameArea <= 0) {
            return 0.0;
        }
        return faceArea / frameArea;
    }

    private static double computeAspectRatio(DetectedFace face) {
        if (face == null || face.box() == null) {
            return 0.0;
        }
        double width = Math.abs(face.box().width());
        double height = Math.abs(face.box().height());
        if (width == 0 || height == 0) {
            return 0.0;
        }
        return width / height;
    }

    private static double computeBrightness(Mat gray) {
        if (gray == null || gray.empty()) {
            return Double.NaN;
        }
        Scalar mean = opencv_core.mean(gray);
        double value = mean.get(0);
        mean.close();
        return value;
    }

    private static Mat decodeImage(byte[] data) {
        Mat buffer = null;
        try {
            buffer = new Mat(1, data.length, opencv_core.CV_8U);
            buffer.data().put(data);
            Mat image = opencv_imgcodecs.imdecode(buffer, opencv_imgcodecs.IMREAD_COLOR);
            if (image != null && !image.empty()) {
                return image;
            }
            releaseSafe(image);
            return null;
        } finally {
            releaseSafe(buffer);
        }
    }

    private static void releaseSafe(Mat mat) {
        if (mat != null) {
            mat.release();
        }
    }

    public record ValidationResult(boolean valid,
                                   List<String> errors,
                                   double sharpness,
                                   double brightness,
                                   double faceCoverage,
                                   double detectionConfidence) {

        public static ValidationResult valid(double sharpness,
                                             double brightness,
                                             double faceCoverage,
                                             double detectionConfidence) {
            return new ValidationResult(true, Collections.emptyList(), sharpness, brightness, faceCoverage, detectionConfidence);
        }

        public static ValidationResult invalid(String error) {
            List<String> errs = new ArrayList<>();
            errs.add(error);
            return new ValidationResult(false, errs, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }
}
