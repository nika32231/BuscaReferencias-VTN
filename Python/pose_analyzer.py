import cv2
import mediapipe as mp
import sys
import json
import numpy as np
from mediapipe.tasks import python
from mediapipe.tasks.python import vision

def analyze_image(image_path):
    # Intentar usar el API moderno de Tasks si es posible
    try:
        # Configuración para Pose Landmarker
        base_options = python.BaseOptions(model_asset_path='pose_landmarker_heavy.task')
        options = vision.PoseLandmarkerOptions(
            base_options=base_options,
            output_segmentation_masks=True
        )
        
        # Como no tenemos el archivo .task descargado, caeremos en el API tradicional
        # pero con una estructura preparada para el futuro.
        return analyze_legacy(image_path)
    except Exception as e:
        return analyze_legacy(image_path)

def analyze_legacy(image_path):
    mp_pose = mp.solutions.pose
    pose = mp_pose.Pose(
        static_image_mode=True, 
        model_complexity=2,
        min_detection_confidence=0.5
    )
    
    image = cv2.imread(image_path)
    if image is None:
        return {"error": f"Could not read image at {image_path}"}
    
    # --- PREPROCESAMIENTO PARA DIBUJOS ---
    # Convertir a escala de grises
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Intentar detectar si es un dibujo (alto contraste, pocos colores)
    # Si el fondo es mayoritariamente blanco o claro
    is_drawing = np.mean(gray) > 200
    
    processed_image = image.copy()
    if is_drawing:
        # Binarización invertida para que las líneas sean blancas sobre fondo negro (mejor para MP)
        # Usamos threshold adaptativo o fijo para limpiar el fondo
        _, thresh = cv2.threshold(gray, 240, 255, cv2.THRESH_BINARY_INV)
        
        # Eliminar ruido pequeño
        kernel_clean = np.ones((2,2), np.uint8)
        clean = cv2.morphologyEx(thresh, cv2.MORPH_OPEN, kernel_clean)
        
        # Dilatación para engrosar líneas de monigotes finos (ayuda a MediaPipe)
        kernel_dilate = np.ones((5,5), np.uint8)
        dilated = cv2.dilate(clean, kernel_dilate, iterations=1)
        
        # Esqueletización opcional (aquí la evitamos para no perder volumen necesario para MP)
        # Pero podemos añadir un "suavizado" de bordes
        blurred = cv2.GaussianBlur(dilated, (5,5), 0)
        
        # Volver a RGB (MediaPipe espera 3 canales)
        processed_image = cv2.cvtColor(blurred, cv2.COLOR_GRAY2RGB)
    else:
        processed_image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)

    results = pose.process(processed_image)
    
    response = {
        "landmarks": {}, 
        "embedding": [], 
        "pose_angles": {},
        "debug": {
            "is_drawing": bool(is_drawing),
            "points_found": 0,
            "avg_confidence": 0.0
        }
    }
    
    if results.pose_landmarks:
        confidences = []
        for i, landmark in enumerate(results.pose_landmarks.landmark):
            response["landmarks"][i] = {
                "x": landmark.x,
                "y": landmark.y,
                "z": landmark.z,
                "visibility": landmark.visibility
            }
            confidences.append(landmark.visibility)
        
        response["debug"]["points_found"] = len(results.pose_landmarks.landmark)
        response["debug"]["avg_confidence"] = float(np.mean(confidences))
        
        # Generar un embedding "manual" basado en ángulos y distancias
        response["embedding"] = generate_pose_embedding(results.pose_landmarks.landmark)
        response["pose_angles"] = generate_pose_angles(results.pose_landmarks.landmark)
        
        # --- GENERAR IMAGEN DE DEBUG ---
        try:
            debug_img = processed_image.copy()
            mp_drawing = mp.solutions.drawing_utils
            mp_drawing_styles = mp.solutions.drawing_styles
            mp_drawing.draw_landmarks(
                debug_img,
                results.pose_landmarks,
                mp_pose.POSE_CONNECTIONS,
                landmark_drawing_spec=mp_drawing_styles.get_default_pose_landmarks_style()
            )
            # Guardar imagen de debug
            debug_path = image_path.replace(".", "_debug.")
            cv2.imwrite(debug_path, cv2.cvtColor(debug_img, cv2.COLOR_RGB2BGR))
            response["debug"]["viz_path"] = debug_path
        except:
            pass
    
    # Análisis de contornos (Híbrido OpenCV)
    try:
        # Usar la imagen binarizada si es dibujo, o crear una
        if is_drawing:
            # Reutilizar thresh que ya tenemos en el scope de analyze_legacy si fuera posible, 
            # pero por legibilidad lo recalculamos o usamos dilated
            contour_img = cv2.cvtColor(processed_image, cv2.COLOR_RGB2GRAY)
        else:
            contour_img = cv2.Canny(gray, 50, 150)
            
        contours, _ = cv2.findContours(contour_img, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if contours:
            largest_contour = max(contours, key=cv2.contourArea)
            # Momentos de Hu para similitud de forma (invariante a escala/rotación)
            hu_moments = cv2.HuMoments(cv2.moments(largest_contour)).flatten().tolist()
            # Log de momentos para debug
            response["debug"]["hu_moments"] = hu_moments
            
            # Centroide del contorno principal
            M = cv2.moments(largest_contour)
            if M["m00"] != 0:
                cx = int(M["m10"] / M["m00"]) / image.shape[1]
                cy = int(M["m01"] / M["m00"]) / image.shape[0]
                response["debug"]["contour_centroid"] = {"x": cx, "y": cy}
    except Exception as e:
        response["debug"]["contour_error"] = str(e)
            
    return response

def generate_pose_embedding(landmarks):
    """Genera un vector de características basado en ángulos y distancias normalizadas."""
    embedding = []
    # Usamos puntos clave para ángulos (hombros, codos, muñecas, caderas, rodillas, tobillos)
    # MediaPipe Pose Landmarks: https://developers.google.com/mediapipe/solutions/vision/pose_landmarker
    
    def get_angle(p1, p2, p3):
        a = np.array([p1.x, p1.y])
        b = np.array([p2.x, p2.y])
        c = np.array([p3.x, p3.y])
        ba = a - b
        bc = c - b
        cosine_angle = np.dot(ba, bc) / (np.linalg.norm(ba) * np.linalg.norm(bc) + 1e-6)
        angle = np.arccos(np.clip(cosine_angle, -1.0, 1.0))
        return float(angle)

    # Ángulos de brazos y piernas
    try:
        # Brazos
        embedding.append(get_angle(landmarks[11], landmarks[13], landmarks[15])) # Izq
        embedding.append(get_angle(landmarks[12], landmarks[14], landmarks[16])) # Der
        # Piernas
        embedding.append(get_angle(landmarks[23], landmarks[25], landmarks[27])) # Izq
        embedding.append(get_angle(landmarks[24], landmarks[26], landmarks[28])) # Der
        # Torso-Brazo
        embedding.append(get_angle(landmarks[23], landmarks[11], landmarks[13]))
        embedding.append(get_angle(landmarks[24], landmarks[12], landmarks[14]))
    except:
        pass
        
    return embedding

def generate_pose_angles(landmarks):
    """Genera un diccionario de ángulos explícitos para cada segmento principal."""
    angles = {}

    def safe_angle(a, b, c):
        try:
            return get_angle(a, b, c)
        except:
            return None

    def get_angle(p1, p2, p3):
        a = np.array([p1.x, p1.y])
        b = np.array([p2.x, p2.y])
        c = np.array([p3.x, p3.y])
        ba = a - b
        bc = c - b
        cosine_angle = np.dot(ba, bc) / (np.linalg.norm(ba) * np.linalg.norm(bc) + 1e-6)
        angle = np.arccos(np.clip(cosine_angle, -1.0, 1.0))
        return float(np.degrees(angle))

    try:
        angles["left_arm"] = safe_angle(landmarks[11], landmarks[13], landmarks[15])
        angles["right_arm"] = safe_angle(landmarks[12], landmarks[14], landmarks[16])
        angles["left_leg"] = safe_angle(landmarks[23], landmarks[25], landmarks[27])
        angles["right_leg"] = safe_angle(landmarks[24], landmarks[26], landmarks[28])
        angles["left_torso"] = safe_angle(landmarks[23], landmarks[11], landmarks[13])
        angles["right_torso"] = safe_angle(landmarks[24], landmarks[12], landmarks[14])
    except:
        pass

    return {k: v for k, v in angles.items() if v is not None}

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"error": "No image path provided"}))
    else:
        result = analyze_image(sys.argv[1])
        print(json.dumps(result))
