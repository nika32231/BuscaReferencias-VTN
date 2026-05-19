const mockReferences = [
  {
    id: "ref-001",
    title: "Look casual azul",
    src: "https://images.unsplash.com/photo-1521572267360-ee0c2909d518?auto=format&fit=crop&w=400&q=70",
  },
  {
    id: "ref-002",
    title: "Estilo urbano gris",
    src: "https://images.unsplash.com/photo-1519085360753-af0119f7cbe7?auto=format&fit=crop&w=400&q=70",
  },
  {
    id: "ref-003",
    title: "Tonos tierra",
    src: "https://images.unsplash.com/photo-1445205170230-053b83016050?auto=format&fit=crop&w=400&q=70",
  },
  {
    id: "ref-004",
    title: "Look monocromo",
    src: "https://images.unsplash.com/photo-1495385794356-15371f348c31?auto=format&fit=crop&w=400&q=70",
  },
  {
    id: "ref-005",
    title: "Base deportiva",
    src: "https://images.unsplash.com/photo-1524504388940-b1c1722653e1?auto=format&fit=crop&w=400&q=70",
  },
  {
    id: "ref-006",
    title: "Inspiracion formal",
    src: "https://images.unsplash.com/photo-1512436991641-6745cdb1723f?auto=format&fit=crop&w=400&q=70",
  },
];

function drawBaseCanvas() {
  const canvas = document.getElementById("referenceCanvas");
  const ctx = canvas.getContext("2d");

  // Vista mock para mostrar zona de analisis sin depender aun del backend.
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  ctx.fillStyle = "#151a25";
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  const gradient = ctx.createLinearGradient(0, 0, canvas.width, canvas.height);
  gradient.addColorStop(0, "#8b9dff1f");
  gradient.addColorStop(1, "#76d3aa1f");
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  ctx.strokeStyle = "#8b9dff";
  ctx.lineWidth = 2;
  ctx.strokeRect(70, 60, 250, 300);
  ctx.strokeRect(380, 85, 260, 270);

  ctx.fillStyle = "#aeb8d2";
  ctx.font = "16px Segoe UI";
  ctx.fillText("Zona color principal", 85, 50);
  ctx.fillText("Zona referencia secundaria", 385, 75);

  ctx.fillStyle = "#76d3aa";
  ctx.beginPath();
  ctx.arc(195, 215, 36, 0, Math.PI * 2);
  ctx.fill();

  ctx.fillStyle = "#8b9dff";
  ctx.beginPath();
  ctx.arc(510, 220, 36, 0, Math.PI * 2);
  ctx.fill();
}

function renderThumbnails(items) {
  const grid = document.getElementById("thumbnailGrid");
  grid.innerHTML = "";

  items.forEach((item) => {
    const card = document.createElement("article");
    card.className = "thumb-card";

    const img = document.createElement("img");
    img.src = item.src;
    img.alt = item.title;
    img.loading = "lazy";

    const caption = document.createElement("p");
    caption.textContent = `${item.id} - ${item.title}`;

    card.appendChild(img);
    card.appendChild(caption);
    grid.appendChild(card);
  });
}

async function searchReferences() {
  // Aqui ira la llamada al backend real (FastAPI/Node) cuando exista la API.
  // Ejemplo futuro: const response = await fetch('/api/search', { method: 'POST', body: ... })
  return mockReferences;
}

async function runMockSearch() {
  const status = document.getElementById("resultStatus");
  status.textContent = "Buscando referencias (mock)...";

  const items = await searchReferences();
  renderThumbnails(items);

  status.textContent = `Resultados mock cargados: ${items.length}`;
}

document.getElementById("searchButton").addEventListener("click", runMockSearch);

// Punto de arranque visual del frontend web minimo.
drawBaseCanvas();
runMockSearch();

