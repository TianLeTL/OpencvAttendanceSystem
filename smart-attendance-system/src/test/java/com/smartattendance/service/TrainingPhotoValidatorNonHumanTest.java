package com.smartattendance.service;

import com.smartattendance.repository.StudentRepository;
import com.smartattendance.repository.TrainingDataRepository;
import com.smartattendance.service.recognition.FaceRecognitionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Basic integration test ensuring the training photo validator
 * rejects non-human images (e.g. the sample dog photos in testpics/).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrainingPhotoValidatorNonHumanTest {

    private TrainingPhotoValidator validator;

    @BeforeAll
    void setUp() {
        TrainingDataRepository trainingDataRepository = Mockito.mock(TrainingDataRepository.class);
        StudentRepository studentRepository = Mockito.mock(StudentRepository.class);
        Mockito.when(studentRepository.findAll()).thenReturn(Collections.emptyList());

        FaceRecognitionService faceRecognitionService = new FaceRecognitionService(trainingDataRepository, studentRepository);
        faceRecognitionService.initialise();

        // reuse faceRecognitionService instance inside validator
        validator = new TrainingPhotoValidator(faceRecognitionService);
    }

    @Test
    @DisplayName("Validator should reject non-human images in testpics directory")
    void nonHumanImagesAreRejected() throws IOException {
        Path testPicsDir = Path.of("testpics");
        if (!Files.exists(testPicsDir)) {
            testPicsDir = Path.of("..", "testpics");
        }
        assertTrue(Files.exists(testPicsDir), "testpics directory must exist for this test");

        try (var stream = Files.list(testPicsDir)) {
            var images = stream.filter(Files::isRegularFile).toList();
            assertFalse(images.isEmpty(), "Expected non-human sample images in testpics directory");

            for (Path image : images) {
                byte[] bytes = Files.readAllBytes(image);
                TrainingPhotoValidator.ValidationResult result = validator.validate(bytes);
                assertFalse(result.valid(), "Expected image " + image.getFileName() + " to be rejected");
            }
        }
    }
}
