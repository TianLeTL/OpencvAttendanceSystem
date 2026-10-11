package com.smartattendance.service;

import com.smartattendance.entity.TrainingData;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * Generates simple synthetic mask overlays for existing training photos so that
 * the recognition pipeline can maintain mask-aware templates without requiring
 * a second round of photo capture from users.
 */
@Service
public class MaskAugmentationService {

    private static final Logger log = LoggerFactory.getLogger(MaskAugmentationService.class);

    private static final double MASK_RECT_TOP_RATIO = 0.50; // leave more upper face exposed
    private static final Scalar MASK_COLOUR = new Scalar(110, 110, 220, 0); // slightly lighter blue

    public Optional<MaskAugmentationResult> createMaskVariant(TrainingData trainingData) {
        byte[] original = trainingData.getImage();
        if (original == null || original.length == 0) {
            return Optional.empty();
        }

        Mat buffer = new Mat(1, original.length, opencv_core.CV_8U, new BytePointer(original));
        Mat image = opencv_imgcodecs.imdecode(buffer, opencv_imgcodecs.IMREAD_COLOR);
        buffer.release();

        if (image == null || image.empty()) {
            log.debug("Unable to decode training image {} for mask augmentation", trainingData.getId());
            if (image != null) {
                image.close();
            }
            return Optional.empty();
        }

        try {
            int height = image.rows();
            int width = image.cols();
            if (height <= 0 || width <= 0) {
                return Optional.empty();
            }

            // Base rectangle covering the lower face.
            int rectTop = Math.max(0, Math.min(height, (int) Math.round(height * MASK_RECT_TOP_RATIO)));
            opencv_imgproc.rectangle(
                    image,
                    new Point(0, rectTop),
                    new Point(width, height),
                    MASK_COLOUR,
                    opencv_imgproc.FILLED,
                    opencv_imgproc.LINE_AA,
                    0
            );

            // Add an ellipse to simulate mask curvature.
            int centerY = rectTop + (int) ((height - rectTop) * 0.58);
            Point centre = new Point(width / 2, centerY);
            Size axes = new Size((int) (width * 0.48), (int) ((height - rectTop) * 0.55));
            opencv_imgproc.ellipse(
                    image,
                    centre,
                    axes,
                    0,
                    0,
                    360,
                    MASK_COLOUR,
                    opencv_imgproc.FILLED,
                    opencv_imgproc.LINE_AA,
                    0
            );

            // Encode augmented image back to bytes.
            BytePointer pointer = new BytePointer();
            boolean encoded = opencv_imgcodecs.imencode(".jpg", image, pointer);
            if (!encoded) {
                pointer.deallocate();
                return Optional.empty();
            }

            byte[] augmented = new byte[(int) pointer.limit()];
            pointer.get(augmented);
            pointer.deallocate();

            String originalName = trainingData.getFilename();
            String maskedName;
            if (originalName == null || originalName.isBlank()) {
                maskedName = String.format(Locale.US, "%s_masked.jpg", trainingData.getId());
            } else {
                int dotIndex = originalName.lastIndexOf('.');
                if (dotIndex > 0) {
                    maskedName = originalName.substring(0, dotIndex) + "_masked" + originalName.substring(dotIndex);
                } else {
                    maskedName = originalName + "_masked.jpg";
                }
            }

            return Optional.of(new MaskAugmentationResult(maskedName, augmented));
        } finally {
            image.close();
        }
    }

    public record MaskAugmentationResult(String filename, byte[] imageBytes) {}
}
