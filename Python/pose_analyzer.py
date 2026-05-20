import cv2
import sys
import json
import os
import urllib.request
import numpy as np

import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_FILE = os.path.join(SCRIPT_DIR, "pose_landmarker_lite.task")
MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
    "pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
)

_DETECTOR = None


def ensure_model():
    if os.path.isfile(MODEL_FILE):
        return MODEL_FILE
    try:
        os.makedirs(SCRIPT_DIR, exist_ok=True)
        urllib.request.urlretrieve(MODEL_URL, MODEL_FILE)
        return MODEL_FILE
    except Exception as e:
        return {"error": f"No se pudo descargar el modelo MediaPipe: {e}"}


def get_detector():
    global _DETECTOR
    if _DETECTOR is not None:
        return _DETECTOR

    model_path = ensure_model()
    if isinstance(model_path, dict):
        raise RuntimeError(model_path.get("error", "Modelo no disponible"))

    base_options = python.BaseOptions(model_asset_path=model_path)
    options = vision.PoseLandmarkerOptions(
        base_options=base_options,
        running_mode=vision.RunningMode.IMAGE,
        num_poses=1,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    )
    _DETECTOR = vision.PoseLandmarker.create_from_options(options)
    return _DETECTOR


def preprocess_image(image_path):
    image = cv2.imread(image_path)
    if image is None:
        return None, None, False

    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    is_drawing = np.mean(gray) > 200

    if is_drawing:
        _, thresh = cv2.threshold(gray, 240, 255, cv2.THRESH_BINARY_INV)
        kernel_clean = np.ones((2, 2), np.uint8)
        clean = cv2.morphologyEx(thresh, cv2.MORPH_OPEN, kernel_clean)
        kernel_dilate = np.ones((5, 5), np.uint8)
        dilated = cv2.dilate(clean, kernel_dilate, iterations=1)
        blurred = cv2.GaussianBlur(dilated, (5, 5), 0)
        processed = cv2.cvtColor(blurred, cv2.COLOR_GRAY2RGB)
    else:
        processed = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)

    return image, processed, is_drawing


def analyze_image(image_path):
    try:
        image, processed, is_drawing = preprocess_image(image_path)
        if image is None:
            return {"error": f"Could not read image at {image_path}"}

        detector = get_detector()
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=processed)
        results = detector.detect(mp_image)

        response = {
            "landmarks": {},
            "embedding": [],
            "pose_angles": {},
            "debug": {
                "is_drawing": bool(is_drawing),
                "points_found": 0,
                "avg_confidence": 0.0,
                "api": "tasks",
            },
        }

        if not results.pose_landmarks:
            return response

        landmarks = results.pose_landmarks[0]
        confidences = []
        for i, landmark in enumerate(landmarks):
            visibility = getattr(landmark, "visibility", 1.0)
            response["landmarks"][str(i)] = {
                "x": landmark.x,
                "y": landmark.y,
                "z": landmark.z,
                "visibility": visibility,
            }
            confidences.append(visibility)

        response["debug"]["points_found"] = len(landmarks)
        response["debug"]["avg_confidence"] = float(np.mean(confidences)) if confidences else 0.0
        response["embedding"] = generate_pose_embedding(landmarks)
        response["pose_angles"] = generate_pose_angles(landmarks)

        try:
            gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
            contour_img = cv2.cvtColor(processed, cv2.COLOR_RGB2GRAY) if is_drawing else cv2.Canny(gray, 50, 150)
            contours, _ = cv2.findContours(contour_img, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            if contours:
                largest = max(contours, key=cv2.contourArea)
                response["debug"]["hu_moments"] = cv2.HuMoments(cv2.moments(largest)).flatten().tolist()
        except Exception as e:
            response["debug"]["contour_error"] = str(e)

        return response
    except Exception as e:
        return {"error": str(e)}


def generate_pose_embedding(landmarks):
    embedding = []

    def get_angle(p1, p2, p3):
        a = np.array([p1.x, p1.y])
        b = np.array([p2.x, p2.y])
        c = np.array([p3.x, p3.y])
        ba = a - b
        bc = c - b
        cosine_angle = np.dot(ba, bc) / (np.linalg.norm(ba) * np.linalg.norm(bc) + 1e-6)
        return float(np.arccos(np.clip(cosine_angle, -1.0, 1.0)))

    try:
        embedding.append(get_angle(landmarks[11], landmarks[13], landmarks[15]))
        embedding.append(get_angle(landmarks[12], landmarks[14], landmarks[16]))
        embedding.append(get_angle(landmarks[23], landmarks[25], landmarks[27]))
        embedding.append(get_angle(landmarks[24], landmarks[26], landmarks[28]))
        embedding.append(get_angle(landmarks[23], landmarks[11], landmarks[13]))
        embedding.append(get_angle(landmarks[24], landmarks[12], landmarks[14]))
    except Exception:
        pass

    return embedding


def generate_pose_angles(landmarks):
    angles = {}

    def get_angle(p1, p2, p3):
        a = np.array([p1.x, p1.y])
        b = np.array([p2.x, p2.y])
        c = np.array([p3.x, p3.y])
        ba = a - b
        bc = c - b
        cosine_angle = np.dot(ba, bc) / (np.linalg.norm(ba) * np.linalg.norm(bc) + 1e-6)
        return float(np.degrees(np.arccos(np.clip(cosine_angle, -1.0, 1.0))))

    def safe_angle(a, b, c):
        try:
            return get_angle(a, b, c)
        except Exception:
            return None

    try:
        angles["left_arm"] = safe_angle(landmarks[11], landmarks[13], landmarks[15])
        angles["right_arm"] = safe_angle(landmarks[12], landmarks[14], landmarks[16])
        angles["left_leg"] = safe_angle(landmarks[23], landmarks[25], landmarks[27])
        angles["right_leg"] = safe_angle(landmarks[24], landmarks[26], landmarks[28])
        angles["left_torso"] = safe_angle(landmarks[23], landmarks[11], landmarks[13])
        angles["right_torso"] = safe_angle(landmarks[24], landmarks[12], landmarks[14])
    except Exception:
        pass

    return {k: v for k, v in angles.items() if v is not None}


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "--batch":
        # Modo batch: lee rutas de stdin (una por línea), carga el modelo UNA sola vez
        # y analiza todas las imágenes en secuencia. Imprime un JSON por línea.
        for line in sys.stdin:
            path = line.strip()
            if not path:
                continue
            result = analyze_image(path)
            result["_path"] = path
            print(json.dumps(result), flush=True)
    elif len(sys.argv) < 2:
        print(json.dumps({"error": "No image path provided"}))
    else:
        print(json.dumps(analyze_image(sys.argv[1])))
