package com.smartattendance.service.recognition;

import com.smartattendance.entity.TrainingData;
import com.smartattendance.repository.TrainingDataRepository;
import com.smartattendance.repository.StudentRepository;
import jakarta.annotation.PostConstruct;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_objdetect.FaceDetectorYN;
import org.bytedeco.opencv.opencv_objdetect.FaceRecognizerSF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Core face recognition engine backed by YuNet (detection) and SFace (embedding).
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Load ONNX models from classpath.</li>
 *     <li>Build and maintain in-memory embeddings for every student with training images.</li>
 *     <li>Analyse incoming frames and produce match candidates.</li>
 * </ul>
 */
@Service
public class FaceRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(FaceRecognitionService.class);

    private static final String DETECTOR_MODEL = "models/opencv/face_detection_yunet_2023mar.onnx";
    private static final String RECOGNIZER_MODEL = "models/opencv/face_recognition_sface_2021dec.onnx";

    private static final float DEFAULT_DETECTION_SCORE_THRESHOLD = 0.7f;
    private static final int MAX_CANDIDATES = 3;

    private final TrainingDataRepository trainingDataRepository;
    private final StudentRepository studentRepository;
    private final ReadWriteLock profileLock = new ReentrantReadWriteLock();
    private final Map<String, StudentProfile> profiles = new ConcurrentHashMap<>();

    private volatile FaceDetectorYN detector;
    private volatile FaceRecognizerSF recognizer;

    public FaceRecognitionService(TrainingDataRepository trainingDataRepository,
                                  StudentRepository studentRepository) {
        this.trainingDataRepository = trainingDataRepository;
        this.studentRepository = studentRepository;
    }

    @PostConstruct
    public void initialise() {
        Instant start = Instant.now();
        Loader.load(opencv_core.class);
        loadModels();
        refreshAllProfiles();
        log.info("Face recognition initialised in {} ms ({} student profiles loaded)",
                Duration.between(start, Instant.now()).toMillis(), profiles.size());
    }

    /**
     * Rebuild every student's embedding profile. Called at startup and can be triggered manually.
     */
    public void refreshAllProfiles() {
        ensureModelsLoaded();
        profileLock.writeLock().lock();
        try {
            profiles.clear();
            studentRepository.findAll().forEach(student ->
                    buildProfileForStudent(student.getStudentId())
                            .ifPresent(profile -> profiles.put(profile.studentId(), profile)));
        } finally {
            profileLock.writeLock().unlock();
        }
    }

    /**
     * Rebuild a specific student's embedding profile. Safe to call whenever their training data changes.
     */
    public void refreshStudentProfile(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            return;
        }

        ensureModelsLoaded();
        profileLock.writeLock().lock();
        try {
            profiles.remove(studentId);
            buildProfileForStudent(studentId)
                    .ifPresent(profile -> profiles.put(studentId, profile));
        } finally {
            profileLock.writeLock().unlock();
        }
    }

    /**
     * Analyse a single frame (base64-encoded JPEG/PNG) and return detected faces with match candidates.
     */
    public FrameAnalysisResult analyseFrame(String base64Image) {
        if (base64Image == null || base64Image.isBlank()) {
            return FrameAnalysisResult.empty();
        }

        ensureModelsLoaded();

        byte[] decoded = decodeBase64Image(base64Image);
        if (decoded.length == 0) {
            return FrameAnalysisResult.empty();
        }

        Mat image = decodeImage(decoded);
        if (image == null || image.empty()) {
            return FrameAnalysisResult.empty();
        }

        List<DetectedFace> faces = new ArrayList<>();
        int frameWidth = image.cols();
        int frameHeight = image.rows();
        try {
            Size inputSize = image.size();
            detector.setInputSize(inputSize);

            Mat detections = new Mat();
            detector.detect(image, detections);
            int rows = detections.rows();
            if (rows <= 0) {
                detections.close();
                return new FrameAnalysisResult(frameWidth, frameHeight, faces);
            }

            FloatIndexer idx = detections.createIndexer();

            for (int i = 0; i < rows; i++) {
                double score = idx.get(i, 4);
                if (score < DEFAULT_DETECTION_SCORE_THRESHOLD) {
                    continue;
                }

                double x = idx.get(i, 0);
                double y = idx.get(i, 1);
                double w = idx.get(i, 2);
                double h = idx.get(i, 3);

                Mat detectionRow = detections.row(i);

                Mat alignedFace = new Mat();
                recognizer.alignCrop(image, detectionRow, alignedFace);

                double eyeOpenness = computeEyeOpenness(alignedFace);

                Mat features = new Mat();
                recognizer.feature(alignedFace, features);

                float[] vector = toFloatArray(features);
                normalise(vector);

                List<MatchCandidate> candidates = rankCandidates(vector);

                faces.add(new DetectedFace(
                        new BoundingBox(x, y, w, h),
                        score,
                        eyeOpenness,
                        candidates
                ));

                alignedFace.close();
                features.close();
            }

            idx.release();
            detections.close();
        } catch (Exception ex) {
            log.warn("Failed to analyse frame: {}", ex.getMessage());
        } finally {
            image.close();
        }

        faces.sort(Comparator.comparingDouble(DetectedFace::detectionScore).reversed());

        return new FrameAnalysisResult(frameWidth, frameHeight, faces);
    }

    /**
     * @return number of currently cached student profiles.
     */
    public int getCachedProfileCount() {
        profileLock.readLock().lock();
        try {
            return profiles.size();
        } finally {
            profileLock.readLock().unlock();
        }
    }

    private Optional<StudentProfile> buildProfileForStudent(String studentId) {
        List<TrainingData> trainingData = trainingDataRepository.findByStudentId(studentId);
        if (trainingData.isEmpty()) {
            return Optional.empty();
        }

        List<float[]> unmaskedVectors = new ArrayList<>();
        List<float[]> maskedVectors = new ArrayList<>();

        for (TrainingData data : trainingData) {
            byte[] imageBytes = data.getImage();
            if (imageBytes == null || imageBytes.length == 0) {
                continue;
            }

            Mat image = decodeImage(imageBytes);
            if (image == null || image.empty()) {
                continue;
            }

            try {
                detector.setInputSize(image.size());
                Mat detections = new Mat();
                detector.detect(image, detections);

                int rows = detections.rows();
                if (rows <= 0) {
                    detections.close();
                    continue;
                }

                FloatIndexer idx = detections.createIndexer();
                int bestIndex = -1;
                double bestScore = 0.0;
                for (int i = 0; i < rows; i++) {
                    double score = idx.get(i, 4);
                    if (score > DEFAULT_DETECTION_SCORE_THRESHOLD && score > bestScore) {
                        bestScore = score;
                        bestIndex = i;
                    }
                }
                idx.release();

                if (bestIndex >= 0) {
                    Mat detectionRow = detections.row(bestIndex);
                    Mat alignedFace = new Mat();
                    recognizer.alignCrop(image, detectionRow, alignedFace);
                    Mat features = new Mat();
                    recognizer.feature(alignedFace, features);

                    float[] vector = toFloatArray(features);
                    normalise(vector);

                    if (data.isMaskVariant() || data.isHasRealMask()) {
                        maskedVectors.add(vector);
                    } else {
                        unmaskedVectors.add(vector);
                    }

                    alignedFace.close();
                    features.close();
                }

                detections.close();
            } catch (Exception ex) {
                log.debug("Failed to process training image {} for student {}: {}", data.getId(), studentId, ex.getMessage());
            } finally {
                image.close();
            }
        }

        if (unmaskedVectors.isEmpty() && maskedVectors.isEmpty()) {
            return Optional.empty();
        }

        float[] unmaskedEmbedding = averageVector(unmaskedVectors).orElse(null);
        float[] maskedEmbedding = averageVector(maskedVectors).orElse(null);

        if (unmaskedEmbedding == null && maskedEmbedding != null) {
            unmaskedEmbedding = maskedEmbedding.clone();
        } else if (unmaskedEmbedding != null && maskedEmbedding == null) {
            maskedEmbedding = null;
        }

        String fullName = studentRepository.findByStudentId(studentId)
                .map(student -> student.getFullName() != null ? student.getFullName() : student.getStudentId())
                .orElse(studentId);

        return Optional.of(new StudentProfile(studentId, fullName, unmaskedEmbedding, maskedEmbedding));
    }

    private void normalise(float[] vector) {
        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }

    private List<MatchCandidate> rankCandidates(float[] vector) {
        profileLock.readLock().lock();
        try {
            if (profiles.isEmpty()) {
                return Collections.emptyList();
            }

            return profiles.values().stream()
                    .map(profile -> {
                        double bestSimilarity = 0.0;
                        boolean masked = false;
                        if (profile.unmaskedEmbedding() != null) {
                            double sim = cosineSimilarity(vector, profile.unmaskedEmbedding());
                            if (sim > bestSimilarity) {
                                bestSimilarity = sim;
                                masked = false;
                            }
                        }
                        if (profile.maskedEmbedding() != null) {
                            double sim = cosineSimilarity(vector, profile.maskedEmbedding());
                            if (sim > bestSimilarity) {
                                bestSimilarity = sim;
                                masked = true;
                            }
                        }
                        double confidence = bestSimilarity * 100.0;
                        return new MatchCandidate(profile.studentId(), profile.displayName(), confidence, masked);
                    })
                    .sorted(Comparator.comparingDouble(MatchCandidate::confidence).reversed())
                    .limit(MAX_CANDIDATES)
                    .collect(Collectors.toList());
        } finally {
            profileLock.readLock().unlock();
        }
    }

    private Optional<float[]> averageVector(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return Optional.empty();
        }
        int length = vectors.get(0).length;
        float[] average = new float[length];
        int valid = 0;
        for (float[] vector : vectors) {
            if (vector == null || vector.length != length) {
                continue;
            }
            for (int i = 0; i < length; i++) {
                average[i] += vector[i];
            }
            valid++;
        }
        if (valid == 0) {
            return Optional.empty();
        }
        for (int i = 0; i < length; i++) {
            average[i] /= valid;
        }
        normalise(average);
        return Optional.of(average);
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private float[] toFloatArray(Mat features) {
        int cols = features.cols();
        float[] vector = new float[cols];
        FloatIndexer indexer = features.createIndexer();
        for (int i = 0; i < cols; i++) {
            vector[i] = indexer.get(0, i);
        }
        indexer.release();
        return vector;
    }

    private void ensureModelsLoaded() {
        if (detector != null && recognizer != null) {
            return;
        }
        synchronized (this) {
            if (detector == null || recognizer == null) {
                loadModels();
            }
        }
    }

    private void loadModels() {
        try {
            Resource detectorResource = new ClassPathResource(DETECTOR_MODEL);
            Resource recognizerResource = new ClassPathResource(RECOGNIZER_MODEL);

            String detectorPath = extractResource(detectorResource);
            String recognizerPath = extractResource(recognizerResource);

            detector = FaceDetectorYN.create(detectorPath, "", new Size(320, 320));
            detector.setScoreThreshold(DEFAULT_DETECTION_SCORE_THRESHOLD);
            detector.setNMSThreshold(0.3f);
            detector.setTopK(500);

            recognizer = FaceRecognizerSF.create(recognizerPath, "");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialise face recognition models", e);
        }
    }

    private Mat decodeImage(byte[] imageBytes) {
        Mat buffer = new Mat(1, imageBytes.length, opencv_core.CV_8U, new BytePointer(imageBytes));
        Mat image = opencv_imgcodecs.imdecode(buffer, opencv_imgcodecs.IMREAD_COLOR);
        buffer.release();
        return image;
    }

    private byte[] decodeBase64Image(String base64Image) {
        String sanitized = base64Image.trim();
        int commaIndex = sanitized.indexOf(',');
        if (commaIndex >= 0) {
            sanitized = sanitized.substring(commaIndex + 1);
        }
        try {
            return Base64.getDecoder().decode(sanitized);
        } catch (IllegalArgumentException ex) {
            log.debug("Invalid base64 image payload: {}", ex.getMessage());
            return new byte[0];
        }
    }

    private String extractResource(Resource resource) throws IOException {
        if (resource == null) {
            throw new IOException("Resource not found");
        }
        if (resource.isFile()) {
            File file = resource.getFile();
            if (file != null) {
                return file.getAbsolutePath();
            }
        }

        Path tempFile = Files.createTempFile("sas-model-", Objects.requireNonNull(resource.getFilename()));
        tempFile.toFile().deleteOnExit();

        try (InputStream inputStream = resource.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(tempFile.toFile())) {
            FileCopyUtils.copy(inputStream, outputStream);
        }

        return tempFile.toAbsolutePath().toString();
    }

    public record FrameAnalysisResult(int frameWidth,
                                      int frameHeight,
                                      List<DetectedFace> faces) {
        public static FrameAnalysisResult empty() {
            return new FrameAnalysisResult(0, 0, List.of());
        }

        public boolean isEmpty() {
            return faces == null || faces.isEmpty();
        }
    }

    public record DetectedFace(BoundingBox box,
                               double detectionScore,
                               double eyeOpenness,
                               List<MatchCandidate> candidates) {
        public Optional<MatchCandidate> bestCandidate() {
            return candidates == null || candidates.isEmpty()
                    ? Optional.empty()
                    : Optional.of(candidates.get(0));
        }
    }

    public record BoundingBox(double x, double y, double width, double height) {}

    public record MatchCandidate(String studentId,
                                 String displayName,
                                 double confidence,
                                 boolean masked) {}

    private record StudentProfile(String studentId,
                                  String displayName,
                                  float[] unmaskedEmbedding,
                                  float[] maskedEmbedding) {}

    private double computeEyeOpenness(Mat alignedFace) {
        if (alignedFace == null || alignedFace.empty()) {
            return 0.0;
        }
        Mat gray = new Mat();
        opencv_imgproc.cvtColor(alignedFace, gray, opencv_imgproc.COLOR_BGR2GRAY);
        int height = gray.rows();
        int width = gray.cols();
        int top = Math.max(0, (int) Math.floor(height * 0.18));
        int bottom = Math.min(height, (int) Math.ceil(height * 0.40));
        int regionHeight = Math.max(1, bottom - top);

        Rect eyeRect = new Rect(0, top, width, regionHeight);
        Mat eyeRegion = new Mat(gray, eyeRect);
        double mean = opencv_core.mean(eyeRegion).get(0);

        eyeRegion.close();
        eyeRect.close();
        gray.close();
        return mean;
    }
}
