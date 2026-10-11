package com.smartattendance.service;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_dnn;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_dnn.Net;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Lightweight heuristic-based mask detector used during training photo ingestion.
 * It examines the lower half of a face image and estimates the proportion of pixels
 * that fall inside a skin-tone HSV range; if the ratio is low we treat the photo as masked.
 * When available we defer to an ONNX-based MobileNet classifier for higher accuracy.
 */
@Service
public class MaskDetectionService {

    private static final Logger log = LoggerFactory.getLogger(MaskDetectionService.class);

    private static final double LOWER_FACE_START_RATIO = 0.58;
    private static final double MIN_SKIN_RATIO = 0.32;
    private static final double MIN_CYAN_RATIO = 0.15;
    private static final double MAX_CYAN_RATIO = 0.35;
    private static final double CYAN_WEIGHT = 0.75;
    private static final double SKIN_WEIGHT = 0.25;
    private static final String MASK_MODEL_CLASSPATH = "models/mask_detector.onnx";
    private static final double DNN_ACCEPT_PROBABILITY = 0.80;

    private static final Scalar SKIN_LOWER = new Scalar(0, 48, 70, 0);
    private static final Scalar SKIN_UPPER = new Scalar(25, 255, 255, 0);
    private static final Scalar MASK_CYAN_LOWER = new Scalar(75, 30, 60, 0);
    private static final Scalar MASK_CYAN_UPPER = new Scalar(110, 255, 255, 0);

    private final Object modelLock = new Object();
    private final Object dnnLock = new Object();
    private transient volatile Net cachedClassifier;

    public MaskDetectionResult evaluate(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return new MaskDetectionResult(false, 0d);
        }
        Mat buffer = new Mat(1, imageBytes.length, opencv_core.CV_8U, new BytePointer(imageBytes));
        Mat image = opencv_imgcodecs.imdecode(buffer, opencv_imgcodecs.IMREAD_COLOR);
        buffer.release();

        if (image == null || image.empty()) {
            if (image != null) image.close();
            return new MaskDetectionResult(false, 0d);
        }

        try {
            Net classifier = ensureModelLoaded();
            if (classifier != null) {
                MaskDetectionResult dnnResult = evaluateWithDnn(image, classifier);
                if (dnnResult != null) {
                    return dnnResult;
                }
            }

            int height = image.rows();
            int width = image.cols();
            if (height <= 0 || width <= 0) {
                return new MaskDetectionResult(false, 0d);
            }

            int startY = (int) Math.round(height * LOWER_FACE_START_RATIO);
            int regionHeight = Math.max(1, height - startY);
            Rect roi = new Rect(0, startY, width, regionHeight);
            Mat lowerFace = new Mat(image, roi);
            Mat hsv = new Mat();
            opencv_imgproc.cvtColor(lowerFace, hsv, opencv_imgproc.COLOR_BGR2HSV);

            Mat skinLowerBound = new Mat(hsv.size(), hsv.type(), SKIN_LOWER);
            Mat skinUpperBound = new Mat(hsv.size(), hsv.type(), SKIN_UPPER);
            Mat skinMask = new Mat();
            opencv_core.inRange(hsv, skinLowerBound, skinUpperBound, skinMask);

            Mat cyanLowerBound = new Mat(hsv.size(), hsv.type(), MASK_CYAN_LOWER);
            Mat cyanUpperBound = new Mat(hsv.size(), hsv.type(), MASK_CYAN_UPPER);
            Mat cyanMask = new Mat();
            opencv_core.inRange(hsv, cyanLowerBound, cyanUpperBound, cyanMask);

            double skinPixels = opencv_core.countNonZero(skinMask);
            double cyanPixels = opencv_core.countNonZero(cyanMask);
            double totalPixels = roi.width() * (double) roi.height();
            double ratio = totalPixels == 0 ? 0.0 : skinPixels / totalPixels;
            double cyanRatio = totalPixels == 0 ? 0.0 : cyanPixels / totalPixels;

            skinMask.release();
            cyanMask.release();
            hsv.release();
            lowerFace.release();
            skinLowerBound.release();
            skinUpperBound.release();
            cyanLowerBound.release();
            cyanUpperBound.release();

            boolean skinDepleted = ratio < MIN_SKIN_RATIO;
            boolean cyanDominant = cyanRatio >= MIN_CYAN_RATIO;
            boolean masked = skinDepleted || cyanDominant;

            double confidence = 0d;
            if (masked) {
                double skinComponent = skinDepleted ? clamp((MIN_SKIN_RATIO - ratio) / MIN_SKIN_RATIO) : 0d;
                double cyanComponent = 0d;
                if (cyanDominant) {
                    double range = Math.max(0.05d, MAX_CYAN_RATIO - MIN_CYAN_RATIO);
                    cyanComponent = clamp((cyanRatio - MIN_CYAN_RATIO) / range);
                }
                confidence = clamp(SKIN_WEIGHT * skinComponent + CYAN_WEIGHT * cyanComponent);
            }
            return new MaskDetectionResult(masked, confidence);
        } catch (Exception ex) {
            log.debug("Mask detection failed: {}", ex.getMessage());
            return new MaskDetectionResult(false, 0d);
        } finally {
            image.close();
        }
    }

    private MaskDetectionResult evaluateWithDnn(Mat image, Net classifier) {
        Mat blob = null;
        try {
            blob = opencv_dnn.blobFromImage(
                    image,
                    1.0 / 127.5,
                    new Size(224, 224),
                    new Scalar(127.5, 127.5, 127.5, 0),
                    true,
                    false,
                    opencv_core.CV_32F
            );

            synchronized (dnnLock) {
                classifier.setInput(blob);
                Mat output = classifier.forward();
                try {
                    FloatPointer pointer = new FloatPointer(output.data());
                    double maskProb = pointer.get(0);
                    double noMaskProb = pointer.get(1);
                    boolean masked = maskProb >= noMaskProb;
                    double confidence = masked ? maskProb : noMaskProb;
                    if (!masked) {
                        return new MaskDetectionResult(false, noMaskProb);
                    }
                    if (confidence < DNN_ACCEPT_PROBABILITY) {
                        return null;
                    }
                    return new MaskDetectionResult(true, confidence);
                } finally {
                    output.release();
                }
            }
        } catch (Exception ex) {
            log.warn("Mask DNN inference failed, falling back to heuristic: {}", ex.getMessage());
            return null;
        } finally {
            if (blob != null) {
                blob.release();
            }
        }
    }

    public boolean isMasked(byte[] imageBytes) {
        return evaluate(imageBytes).masked();
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) return 0d;
        return Math.max(0d, Math.min(1d, value));
    }

    private Net ensureModelLoaded() {
        Net local = cachedClassifier;
        if (local != null) {
            return local;
        }
        synchronized (modelLock) {
            if (cachedClassifier != null) {
                return cachedClassifier;
            }
            try (InputStream modelStream = getClass().getClassLoader().getResourceAsStream(MASK_MODEL_CLASSPATH)) {
                if (modelStream == null) {
                    log.warn("Mask detector model not found on classpath at {}", MASK_MODEL_CLASSPATH);
                    return null;
                }
                Path tempFile = Files.createTempFile("mask-detector", ".onnx");
                Files.copy(modelStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                tempFile.toFile().deleteOnExit();
                cachedClassifier = opencv_dnn.readNetFromONNX(tempFile.toString());
                log.info("Loaded mask detector DNN from {}", MASK_MODEL_CLASSPATH);
            } catch (IOException ex) {
                log.warn("Unable to load mask detector ONNX model: {}", ex.getMessage());
            } catch (UnsatisfiedLinkError error) {
                log.warn("OpenCV DNN module not available: {}", error.getMessage());
            } catch (Exception ex) {
                log.warn("Failed to initialise mask detector ONNX model", ex);
            }
            return cachedClassifier;
        }
    }

    public record MaskDetectionResult(boolean masked, double maskConfidence) {}
}
