const DEFAULT_PROVIDERS = ["pixabay", "pexels", "unsplash", "bing", "flickr", "playwright", "pinterest"];
const STORAGE_BACKEND_KEY = "buscareferencias.backendUrl";
const STORAGE_PALETTE_KEY = "buscareferencias.activePart";

const ANATOMY_PARTS = [
  { id: "HEAD", label: "Cabeza", hex: "#B71C1C" },
  { id: "TORSO", label: "Torso", hex: "#0D47A1" },
  { id: "ARMS", label: "Brazos", hex: "#FBC02D" },
  { id: "FOREARMS", label: "Antebrazos", hex: "#E65100" },
  { id: "HANDS", label: "Manos", hex: "#4A148C" },
  { id: "THIGHS", label: "Muslos", hex: "#1B5E20" },
  { id: "CALVES", label: "Pantorrillas", hex: "#006064" },
  { id: "FEET", label: "Pies", hex: "#880E4F" },
];

const HSV_PARTS = ANATOMY_PARTS.map((part) => ({ ...part, hsv: hexToHsv(part.hex) }));

const state = {
  backendUrl: localStorage.getItem(STORAGE_BACKEND_KEY) || "",
  activePart: localStorage.getItem(STORAGE_PALETTE_KEY) || "HEAD",
  tool: "draw",
  drawing: false,
  pointerId: null,
  lastPoint: null,
  terms: [],
  pose: null,
  connected: false,
  capabilities: null,
  undoStack: [],
  redoStack: [],
  canvasReady: false,
};

const els = {};

function $(id) {
  return document.getElementById(id);
}

function normalizeBackendUrl(raw) {
  const value = (raw || "").trim();
  if (!value) return "";
  return value.replace(/\/+$/, "");
}

function getCurrentPart() {
  return HSV_PARTS.find((part) => part.id === state.activePart) || HSV_PARTS[0];
}

function setTool(tool) {
  state.tool = tool;
  els.drawToolButton.classList.toggle("tool-button-active", tool === "draw");
  els.eraseToolButton.classList.toggle("tool-button-active", tool === "erase");
  els.analysisStatus.textContent = tool === "erase" ? "Modo borrado activo" : "Modo dibujo activo";
}

function setConnectionStatus(kind, text) {
  els.connectionBadge.className = `badge badge-${kind}`;
  els.connectionBadge.textContent = text;
}

function setResultStatus(text) {
  els.resultStatus.textContent = text;
}

function setPoseSummary(text) {
  els.poseSummary.textContent = text;
}

function setModeSummary(text) {
  els.modeSummary.textContent = text;
}

function updateTermsList(terms) {
  els.termsList.innerHTML = "";
  if (!terms.length) {
    const li = document.createElement("li");
    li.className = "term-empty";
    li.textContent = "No hay términos generados todavía.";
    els.termsList.appendChild(li);
    return;
  }

  terms.forEach((term) => {
    const li = document.createElement("li");
    li.className = "term-chip";
    li.textContent = term;
    els.termsList.appendChild(li);
  });
}

function renderPalette() {
  els.paletteGrid.innerHTML = "";
  ANATOMY_PARTS.forEach((part) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "palette-button";
    btn.dataset.part = part.id;
    btn.innerHTML = `
      <span class="palette-swatch" style="background:${part.hex}"></span>
      <span class="palette-text">
        <strong>${part.label}</strong>
        <small>${part.hex}</small>
      </span>
    `;
    btn.setAttribute("aria-pressed", String(part.id === state.activePart));
    btn.addEventListener("click", () => {
      state.activePart = part.id;
      localStorage.setItem(STORAGE_PALETTE_KEY, part.id);
      renderPalette();
      if (state.tool === "draw") {
        setTool("draw");
      }
    });
    els.paletteGrid.appendChild(btn);
  });
}

function renderResults(results) {
  els.thumbnailGrid.innerHTML = "";

  if (!results.length) {
    const empty = document.createElement("article");
    empty.className = "empty-state";
    empty.innerHTML = `
      <h3>Sin resultados</h3>
      <p>Conecta un backend real y vuelve a lanzar la búsqueda para ver miniaturas auténticas aquí.</p>
    `;
    els.thumbnailGrid.appendChild(empty);
    return;
  }

  results.forEach((item) => {
    const scorePercent = normalizeSimilarity(item.similarity ?? item.score ?? 0);
    const card = document.createElement("article");
    card.className = "thumb-card";
    card.tabIndex = 0;

    const img = document.createElement("img");
    img.src = item.thumbnailUrl || item.thumbnail_url || item.originalUrl || item.sourceUrl || item.cachedPath || "";
    img.alt = item.title || item.provider || "Referencia";
    img.loading = "lazy";

    const body = document.createElement("div");
    body.className = "thumb-body";

    const title = document.createElement("h3");
    title.textContent = item.title || item.provider || "Referencia";

    const meta = document.createElement("p");
    meta.className = "thumb-meta";
    meta.textContent = `${item.provider || "online"} · ${scorePercent.toFixed(0)}%`;
    meta.classList.add(scorePercent >= 60 ? "score-good" : "score-bad");

    const source = document.createElement("p");
    source.className = "thumb-source";
    source.textContent = item.sourceUrl || item.sourcePageUrl || item.originalUrl || "";

    body.append(title, meta, source);
    card.append(img, body);

    const openSource = () => {
      const target = item.sourceUrl || item.sourcePageUrl || item.originalUrl;
      if (target) {
        window.open(target, "_blank", "noopener,noreferrer");
      }
    };

    card.addEventListener("click", openSource);
    card.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        openSource();
      }
    });

    els.thumbnailGrid.appendChild(card);
  });
}

function normalizeSimilarity(value) {
  const numeric = Number(value) || 0;
  if (numeric <= 1) return numeric * 100;
  return Math.max(0, Math.min(100, numeric));
}

function rgbToHsv(r, g, b) {
  const rn = r / 255;
  const gn = g / 255;
  const bn = b / 255;
  const max = Math.max(rn, gn, bn);
  const min = Math.min(rn, gn, bn);
  const delta = max - min;

  let hue = 0;
  if (delta !== 0) {
    if (max === rn) {
      hue = 60 * (((gn - bn) / delta) % 6);
    } else if (max === gn) {
      hue = 60 * ((bn - rn) / delta + 2);
    } else {
      hue = 60 * ((rn - gn) / delta + 4);
    }
  }
  if (hue < 0) hue += 360;

  const saturation = max === 0 ? 0 : delta / max;
  const brightness = max;
  return { hue, saturation, brightness };
}

function hexToHsv(hex) {
  const clean = hex.replace("#", "");
  const r = parseInt(clean.slice(0, 2), 16);
  const g = parseInt(clean.slice(2, 4), 16);
  const b = parseInt(clean.slice(4, 6), 16);
  return rgbToHsv(r, g, b);
}

function hueDiff(a, b) {
  const diff = Math.abs(a - b);
  return diff > 180 ? 360 - diff : diff;
}

function isSimilarHsv(sample, target) {
  return (
    hueDiff(sample.hue, target.hue) < 8 &&
    sample.saturation > 0.2 &&
    target.saturation > 0.2 &&
    sample.brightness > 0.2 &&
    target.brightness > 0.2
  );
}

function canvasPoint(event) {
  const rect = els.referenceCanvas.getBoundingClientRect();
  return {
    x: ((event.clientX - rect.left) / rect.width) * els.referenceCanvas.width,
    y: ((event.clientY - rect.top) / rect.height) * els.referenceCanvas.height,
  };
}

function getCanvasContext() {
  return els.referenceCanvas.getContext("2d", { willReadFrequently: true });
}

function resizeCanvas() {
  const canvas = els.referenceCanvas;
  const ctx = getCanvasContext();
  const wrapper = canvas.parentElement;
  const targetWidth = Math.max(320, Math.floor(wrapper.clientWidth));
  const targetHeight = Math.max(360, Math.floor(targetWidth * 0.66));

  const oldImage = canvas.width > 0 && canvas.height > 0 ? canvas.toDataURL() : null;
  canvas.width = targetWidth;
  canvas.height = targetHeight;

  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  if (oldImage) {
    const image = new Image();
    image.onload = () => {
      ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
      drawCanvasFrame();
    };
    image.src = oldImage;
  } else {
    drawCanvasFrame();
  }

  state.undoStack.length = 0;
  state.redoStack.length = 0;
  state.canvasReady = true;
}

function drawCanvasFrame() {
  const canvas = els.referenceCanvas;
  const ctx = getCanvasContext();
  ctx.save();
  ctx.strokeStyle = "#b0b7c3";
  ctx.lineWidth = 1.5;
  ctx.strokeRect(0.75, 0.75, Math.max(0, canvas.width - 1.5), Math.max(0, canvas.height - 1.5));
  ctx.restore();
}

function pushUndoSnapshot() {
  const canvas = els.referenceCanvas;
  const ctx = getCanvasContext();
  state.undoStack.push(ctx.getImageData(0, 0, canvas.width, canvas.height));
  if (state.undoStack.length > 24) {
    state.undoStack.shift();
  }
}

function restoreSnapshot(snapshot) {
  if (!snapshot) return;
  const ctx = getCanvasContext();
  ctx.putImageData(snapshot, 0, 0);
  drawCanvasFrame();
}

function clearCanvas() {
  const canvas = els.referenceCanvas;
  const ctx = getCanvasContext();
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  drawCanvasFrame();
}

function drawLine(from, to) {
  const ctx = getCanvasContext();
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.lineWidth = state.tool === "erase" ? 24 : 6;
  if (state.tool === "erase") {
    ctx.strokeStyle = "#ffffff";
  } else {
    ctx.strokeStyle = getCurrentPart().hex;
  }
  ctx.beginPath();
  ctx.moveTo(from.x, from.y);
  ctx.lineTo(to.x, to.y);
  ctx.stroke();
  drawCanvasFrame();
}

function beginStroke(event) {
  if (!state.canvasReady) return;
  state.drawing = true;
  state.pointerId = event.pointerId;
  els.referenceCanvas.setPointerCapture(event.pointerId);
  state.lastPoint = canvasPoint(event);
  state.redoStack.length = 0;
  pushUndoSnapshot();
  event.preventDefault();
}

function moveStroke(event) {
  if (!state.drawing || state.pointerId !== event.pointerId) return;
  const next = canvasPoint(event);
  drawLine(state.lastPoint, next);
  state.lastPoint = next;
  event.preventDefault();
}

function endStroke(event) {
  if (state.pointerId !== event.pointerId) return;
  state.drawing = false;
  state.pointerId = null;
  state.lastPoint = null;
  state.redoStack.length = 0;
  event.preventDefault();
}

function generateTermsFromPose(pose) {
  const terms = new Set();
  const joints = pose?.joints || {};
  const head = joints.HEAD;
  const torso = joints.TORSO;
  const hands = joints.HANDS;
  const feet = joints.FEET;
  const hasHead = Boolean(head);
  const hasTorso = Boolean(torso);
  const hasLegs = Boolean(joints.THIGHS || joints.CALVES || feet);
  const hasArms = Boolean(joints.ARMS || joints.FOREARMS || hands);

  let frame = "";
  if (hasHead && hasTorso && hasLegs) frame = "full body";
  else if (hasHead && hasTorso) frame = "upper body";
  else if (hasTorso && hasLegs) frame = "lower body";
  else if (hasHead) frame = "portrait reference";

  let action = "";
  if (hasHead && hands && hands.y < head.y) action = "arms raised";
  else if (hasTorso && hands && hands.y < torso.y) action = "arms up";
  else if (hasArms) action = "dynamic pose";

  let posture = "";
  if (torso && feet) {
    const verticalDist = Math.abs(feet.y - torso.y);
    posture = verticalDist < 120 ? "sitting" : "standing";
  }

  let anatomy = "anatomy reference";
  if (hasHead && hasTorso && hasLegs) anatomy = "full body anatomy reference";
  else if (hasHead && hasTorso) anatomy = "upper body anatomy reference";
  else if (hasTorso && hasLegs) anatomy = "lower body anatomy reference";

  const base = `${frame} ${posture} ${action}`.trim() || "human pose";
  terms.add(`${base} reference pinterest`);
  terms.add(`${base} anatomy reference google images`);
  terms.add(`${base} figure reference pexels`);
  terms.add(`${anatomy} pinterest`);
  terms.add(`${posture || "standing"} pose reference`);
  terms.add(`${action || "human pose"} anatomy reference`);

  return Array.from(terms);
}

function analyzeCanvas() {
  if (!state.canvasReady) return null;

  const canvas = els.referenceCanvas;
  const ctx = getCanvasContext();
  const { data, width, height } = ctx.getImageData(0, 0, canvas.width, canvas.height);

  const accum = new Map();
  HSV_PARTS.forEach((part) => {
    accum.set(part.id, { x: 0, y: 0, count: 0, label: part.label });
  });

  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const idx = (y * width + x) * 4;
      const alpha = data[idx + 3];
      if (alpha < 128) continue;

      const sample = rgbToHsv(data[idx], data[idx + 1], data[idx + 2]);
      for (const part of HSV_PARTS) {
        if (isSimilarHsv(sample, part.hsv)) {
          const current = accum.get(part.id);
          current.x += x / width;
          current.y += y / height;
          current.count += 1;
          break;
        }
      }
    }
  }

  const joints = {};
  const partsFound = [];
  accum.forEach((value, key) => {
    if (value.count > 0) {
      joints[key] = {
        x: value.x / value.count,
        y: value.y / value.count,
        label: value.label,
        count: value.count,
      };
      partsFound.push(value.label);
    }
  });

  const pose = {
    joints,
    partsFound,
    embedding: [],
    poseAngles: {},
  };

  const terms = generateTermsFromPose(pose);
  state.pose = pose;
  state.terms = terms;
  updateTermsList(terms);
  setPoseSummary(partsFound.length ? `${partsFound.length} partes: ${partsFound.join(", ")}` : "No se detectaron colores anatómicos");
  els.analysisStatus.textContent = partsFound.length
    ? "Análisis completado desde el canvas"
    : "Dibuja con los colores de la paleta para obtener términos reales";

  return pose;
}

function buildSearchPayload() {
  const pose = state.pose || analyzeCanvas();
  return {
    terms: state.terms.length ? state.terms : generateTermsFromPose(pose || { joints: {} }),
    poseData: pose,
    providers: DEFAULT_PROVIDERS,
    limit: 24,
    sessionId: `web-${Date.now()}`,
  };
}

async function fetchJson(path, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), options.timeoutMs || 30000);
  try {
    const response = await fetch(`${state.backendUrl}${path}`, {
      ...options,
      signal: controller.signal,
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        ...(options.headers || {}),
      },
    });
    const text = await response.text();
    let body = null;
    if (text) {
      try {
        body = JSON.parse(text);
      } catch {
        body = text;
      }
    }
    if (!response.ok) {
      const error = new Error(`HTTP ${response.status}`);
      error.status = response.status;
      error.response = body;
      throw error;
    }
    return body;
  } finally {
    clearTimeout(timeout);
  }
}

async function connectBackend() {
  state.backendUrl = normalizeBackendUrl(els.backendUrl.value);
  localStorage.setItem(STORAGE_BACKEND_KEY, state.backendUrl);

  if (!state.backendUrl) {
    state.connected = false;
    state.capabilities = null;
    setConnectionStatus("neutral", "Sin backend");
    els.connectionHint.textContent = "Introduce una URL pública del backend para obtener resultados reales.";
    setModeSummary("Frontend web activo; backend no configurado");
    return false;
  }

  setConnectionStatus("neutral", "Conectando...");
  els.connectionHint.textContent = "Comprobando /health y /api/v1/capabilities...";

  try {
    const [health, capabilities] = await Promise.all([
      fetchJson("/health", { timeoutMs: 15000 }),
      fetchJson("/api/v1/capabilities", { timeoutMs: 15000 }).catch(() => null),
    ]);

    state.connected = true;
    state.capabilities = capabilities;
    setConnectionStatus("good", "Conectado");
    els.connectionHint.textContent = `${health.service || "Backend"} · cache ${health.cacheDir || "n/a"}`;
    setModeSummary(capabilities ? `API real: ${capabilities.providers?.length || 0} proveedores` : "API real disponible");
    return true;
  } catch (error) {
    state.connected = false;
    state.capabilities = null;
    setConnectionStatus("bad", "Sin conexión");
    els.connectionHint.textContent = "No se pudo contactar con el backend. Revisa la URL o el despliegue.";
    setModeSummary("Frontend listo, backend no disponible");
    return false;
  }
}

async function searchWithBackend() {
  if (!state.backendUrl) {
    setResultStatus("Configura un backend real para mostrar resultados auténticos.");
    return;
  }

  if (!state.pose && !state.terms.length) {
    analyzeCanvas();
  }

  const payload = buildSearchPayload();
  if (!payload.terms.length) {
    setResultStatus("No hay términos suficientes para buscar.");
    return;
  }

  setResultStatus("Buscando referencias reales...");
  els.analysisStatus.textContent = "Consultando el backend real";
  els.thumbnailGrid.innerHTML = "";

  const endpoints = ["/search", "/api/v1/search/references"];
  let lastError = null;

  for (const endpoint of endpoints) {
    try {
      const results = await fetchJson(endpoint, {
        method: "POST",
        body: JSON.stringify(payload),
        timeoutMs: 90000,
      });
      const normalizedResults = Array.isArray(results) ? results : [];
      renderResults(normalizedResults);
      setResultStatus(normalizedResults.length ? `${normalizedResults.length} referencias reales cargadas` : "El backend no devolvió resultados");
      return;
    } catch (error) {
      lastError = error;
      if (error?.status === 404 || error?.message === "HTTP 404") {
        continue;
      }
    }
  }

  const detail = lastError?.response ? JSON.stringify(lastError.response).slice(0, 180) : lastError?.message || "Error desconocido";
  setResultStatus(`Error consultando backend: ${detail}`);
}

function wireEvents() {
  els.connectButton.addEventListener("click", connectBackend);
  els.backendUrl.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      connectBackend();
    }
  });

  els.drawToolButton.addEventListener("click", () => setTool("draw"));
  els.eraseToolButton.addEventListener("click", () => setTool("erase"));
  els.clearButton.addEventListener("click", () => {
    pushUndoSnapshot();
    clearCanvas();
    state.redoStack.length = 0;
    state.pose = null;
    state.terms = [];
    updateTermsList([]);
    setPoseSummary("Lienzo limpio");
    setResultStatus("Lienzo limpiado. Analiza de nuevo para generar términos.");
  });
  els.undoButton.addEventListener("click", () => {
    if (!state.undoStack.length) return;
    const current = getCanvasContext().getImageData(0, 0, els.referenceCanvas.width, els.referenceCanvas.height);
    state.redoStack.push(current);
    restoreSnapshot(state.undoStack.pop());
  });
  els.redoButton.addEventListener("click", () => {
    if (!state.redoStack.length) return;
    const current = getCanvasContext().getImageData(0, 0, els.referenceCanvas.width, els.referenceCanvas.height);
    state.undoStack.push(current);
    restoreSnapshot(state.redoStack.pop());
  });
  els.analyzeButton.addEventListener("click", () => {
    analyzeCanvas();
    setResultStatus("Análisis del canvas completado.");
  });
  els.searchButton.addEventListener("click", async () => {
    analyzeCanvas();
    await searchWithBackend();
  });

  const canvas = els.referenceCanvas;
  canvas.addEventListener("pointerdown", beginStroke);
  canvas.addEventListener("pointermove", moveStroke);
  canvas.addEventListener("pointerup", endStroke);
  canvas.addEventListener("pointercancel", endStroke);
  canvas.addEventListener("pointerleave", (event) => {
    if (state.drawing) {
      endStroke(event);
    }
  });

  window.addEventListener("resize", () => {
    resizeCanvas();
  });
}

async function bootstrap() {
  els.backendUrl = $("backendUrl");
  els.connectButton = $("connectButton");
  els.connectionBadge = $("connectionBadge");
  els.connectionHint = $("connectionHint");
  els.referenceCanvas = $("referenceCanvas");
  els.paletteGrid = $("paletteGrid");
  els.drawToolButton = $("drawToolButton");
  els.eraseToolButton = $("eraseToolButton");
  els.clearButton = $("clearButton");
  els.undoButton = $("undoButton");
  els.redoButton = $("redoButton");
  els.analyzeButton = $("analyzeButton");
  els.searchButton = $("searchButton");
  els.analysisStatus = $("analysisStatus");
  els.poseSummary = $("poseSummary");
  els.modeSummary = $("modeSummary");
  els.termsList = $("termsList");
  els.thumbnailGrid = $("thumbnailGrid");
  els.resultStatus = $("resultStatus");

  els.backendUrl.value = state.backendUrl;
  renderPalette();
  setTool(state.tool);
  updateTermsList([]);
  setModeSummary("Canvas local + backend remoto");
  setConnectionStatus("neutral", state.backendUrl ? "Pendiente" : "Desconectado");
  setResultStatus("Aún no se han cargado resultados reales.");

  resizeCanvas();
  wireEvents();

  if (state.backendUrl) {
    await connectBackend();
  }
}

bootstrap().catch((error) => {
  console.error("Bootstrap failed", error);
  setResultStatus(`Error inicializando la interfaz: ${error.message}`);
});

