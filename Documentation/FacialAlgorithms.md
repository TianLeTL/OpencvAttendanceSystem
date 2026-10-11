# Facial Algorithms & Data Flow

This document maps the end‑to‑end flow for every face‑related capability in the Smart Attendance System. Each section traces the pipeline from the browser, through our JavaScript helpers, into Spring controllers/services, and finally down to the ONNX/OpenCV components that make the decision.

---

## 1. Face Recognition (Attendance Matching)

1. **HTML / JS entry point**  
   - `mark-attendance.html` is the operator console. When a proctor selects a session, the page calls `POST /api/recognition/start` and opens an `EventSource` on `GET /api/recognition/events?sessionId=…` to receive SSE events (`recognition.ready`, `recognition.overlay`, review queues, etc.).  
   - The “Start camera” button captures frames from `navigator.mediaDevices.getUserMedia`. Every few hundred milliseconds the JS serialises the current `<canvas>` frame to base64 and posts it to `POST /api/recognition/frame`.

2. **RecognitionController** (`smart-attendance-system/src/main/java/com/smartattendance/controller/RecognitionController.java`)  
   - Validates session access, ensures the session is OPEN, and checks that the face profile cache contains data.  
   - Calls `faceRecognitionService.analyseFrame(base64)` to obtain `FrameAnalysisResult`. The result is immediately passed to `AutomaticAttendanceService.applyFrame`, and the response to the browser is `202 Accepted` so the UI can keep streaming frames.

3. **FaceRecognitionService** (`service/recognition/FaceRecognitionService.java`)  
   - **ONNX models**: YuNet detector (`face_detection_yunet_2023mar.onnx`) and SFace recogniser (`face_recognition_sface_2021dec.onnx`) are loaded via OpenCV (JavaCV).  
   - **Profile cache**: On startup and whenever training data changes, embeddings for every student are built. Each profile stores two averaged vectors (masked & unmasked) so the matching stage can prefer the correct template.  
   - **Frame analysis**:  
     1. Base64 payload → byte[] → OpenCV `Mat`.  
     2. YuNet detects faces, returning `[x, y, w, h, score, landmarks…]`. Low-score detections (<0.7) are discarded.  
     3. Each detection is aligned (`recognizer.alignCrop`) and embedded (`recognizer.feature`). The vectors are L2-normalised and compared against cached embeddings via cosine similarity.  
     4. For each face we keep the top 3 candidates, record detection score, eye-openness metric, and bounding box.

4. **AutomaticAttendanceService** (`service/recognition/AutomaticAttendanceService.java`)  
   - Receives the frame analysis, groups faces via `MultiFaceTracker`, filters candidates to only those in the session roster, and enforces cooldowns (no rematch within 30 seconds).  
   - Confidence thresholds: configurable recognition threshold vs. review lower bound (50%). Above the threshold → auto mark attendance (with SSE `recognition.auto-marked`). Between 50% and the threshold → enqueue for manual review (`recognition.review`). Below → skip with reason (`LOW_CONFIDENCE`, `NOT_IN_SESSION`, etc.).  
   - SSE events are published via `RecognitionEventService` so the browser can update overlays, review queue, and audit log live.

**Net effect**: HTML streams frames → JS posts to `/frame` → `FaceRecognitionService` (YuNet + SFace ONNX) produces matches → `AutomaticAttendanceService` writes attendance and emits SSE overlays back to the UI.

---

## 2. Training Photo Validation (“Face Validation”)

This runs whenever an admin uploads/captures photos in `students.html` or when an admin replaces images in `student-view.html`.

1. **Frontend flow**  
   - JS bundles selected/captured images into a `FormData` POST to `/api/students/{id}/photos`.  
   - After the upload finishes, the response includes per-photo metadata (`maskConfidence`, `autoMaskSuggestion`, etc.) that drives the UI prompts.

2. **StudentController → StudentService**  
   - `StudentController.addPhotosToStudent` streams the multipart payload, and for every photo it calls `StudentService.saveTrainingData`.
   - The first step inside `saveTrainingData` is `trainingPhotoValidator.validate(imageBytes)`; invalid photos immediately throw an `IllegalArgumentException` with a human-readable reason that the UI surfaces.

3. **TrainingPhotoValidator** (`service/TrainingPhotoValidator.java`)  
   Validation steps:
   - Decode JPEG/PNG and ensure minimum resolution 320×320.
   - Convert to grayscale and compute mean brightness (must sit between 45 and 210). This avoids pitch-black or overexposed frames.
   - Run `FaceRecognitionService.analyseFrame` once more—not for recognition, but to re-use YuNet’s detection score, bounding box, and landmark metadata.
   - Enforce: exactly one face, detection confidence ≥0.90, bounding-box aspect ratio in 0.65–1.55, and face coverage ≥5 % of the frame.  
   - Because the validator relies on YuNet/SFace, it inherently rejects non-human shapes or background-only uploads: no ML “classifier” is needed beyond the detector’s score + geometric heuristics.

4. **Outcome**  
   - Successful validations return sharpness/brightness/coverage stats. These are stored alongside the training photo for later auditing.  
   - Failed validations bubble a descriptive error back to the JS toast (e.g., “Multiple faces detected” or “Face occupies only 3 % of frame”).

---

## 3. Mask Recognition & Confirmation

Mask status is inferred whenever new photos arrive (same POST flow as above) and whenever students replace their photos via `student-view.html`.

1. **Frontend prompts** (`students.html` / `student-view.html`)  
   - After an upload, JS inspects each photo’s `maskConfidence` + `autoMaskSuggestion`.  
   - If confidence ≥95 % the UI auto-marks it as masked; if <5 % it auto-marks as unmasked. Values between 5–95 % queue the photo for the **mask confirmation modal** (the user answers “Yes, wearing a mask” or “No”). This ensures borderline cases are audited.

2. **Server-side detection** (`service/MaskDetectionService.java`)  
   - **Primary classifier (ONNX)**: we load `models/mask_detector.onnx`, a MobileNetV2 binary from ChandrikaDeb7’s Face-Mask-Detection project. The photo (whole frame) is resized to 224×224, normalised to `[-1,1]`, and run through OpenCV DNN. The output logits represent `[mask_prob, no_mask_prob]`. If `mask_prob ≥ 0.95`, we accept the verdict with that probability as confidence.  
   - **Fallback heuristic**: when the ONNX model is unavailable or produces low confidence, we drop down to the HSV-based logic:
     1. Crop the lower 42 % of the face (approximate nose → chin).
     2. Compute the ratio of skin-tone pixels (HSV between `[0,48,70]` and `[25,255,255]`); if the lower face has too little skin (`<32 %`), that’s evidence of a mask.
     3. Compute the ratio of cyan/teal pixels (mask colour band `[75,30,60]` to `[110,255,255]`), weighting it 75 % vs. 25 % for the skin signal.
     4. Combine the scores to produce a 0–1 confidence and a boolean suggestion.

3. **StudentService integration**  
   - After validation, `MaskDetectionService.evaluate` is invoked.  
   - `StudentService.PhotoSaveResult` stores `hasRealMask`, `autoMaskSuggestion`, and the raw probability so the UI can instruct the user. Real masks trigger synthetic augmentation (stored as `maskVariant` training images) to boost recognition when students appear masked in class.

4. **End-to-end summary**  
   HTML upload → JS POST → Spring controller → `TrainingPhotoValidator` → `MaskDetectionService` (ONNX + fallback) → DB.  
   The browser’s modal ensures that anything between 5 % and 95 % confidence is manually confirmed, which is key for demoing the feature.

---

## Quick Reference

| Capability | Frontend hook | API / Controller | Service & Models |
|------------|---------------|------------------|------------------|
| Face recognition (live attendance) | `mark-attendance.html` camera loop & SSE log | `POST /api/recognition/frame`, `GET /api/recognition/events` (`RecognitionController`) | `FaceRecognitionService` (YuNet + SFace ONNX), `AutomaticAttendanceService`, `RecognitionEventService` |
| Training photo validation | `students.html` / `student-view.html` photo upload | `POST /api/students/{id}/photos` (`StudentController`) | `TrainingPhotoValidator` (brightness/coverage checks + YuNet) |
| Mask recognition | same upload endpoints | same controller | `MaskDetectionService` (MobileNet ONNX + HSV heuristic) + UI prompt queue |

These three pipelines share the same capture stack (HTML canvas → JS base64 → Spring controllers) but diverge in the specialised services they invoke. This document should give you the context needed to describe each algorithm clearly on presentation slides. Let me know if you need diagrams or Mermaid snippets illustrating the flow.
