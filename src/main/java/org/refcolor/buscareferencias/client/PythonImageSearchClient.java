package org.refcolor.buscareferencias.client;

import org.refcolor.buscareferencias.core.FeatureFlags;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Resolver centralizado para lanzar Python desde Java en Windows.
 * Prioridades:
 * 1) python.path / PYTHON_PATH / APP_PYTHON_PATH
 * 2) venv local (.venv / venv / env)
 * 3) py -3
 * 4) python
 * 5) python3
 * 6) rutas comunes de instalación en Windows
 */
public final class PythonImageSearchClient {
	private static final Logger logger = LoggerFactory.getLogger(PythonImageSearchClient.class);

	private static final int DEFAULT_PROBE_TIMEOUT_SECONDS = 8;
	private static final int DEFAULT_SCRIPT_TIMEOUT_SECONDS = 60;
	private static final String STORE_ALIAS_HINT = "Microsoft Store";
	private static final String STORE_ALIAS_HINT_2 = "run without arguments to install";
	private static final String STORE_ALIAS_HINT_3 = "CreateProcess error=2";

	private static volatile ResolvedPython cachedPython;
	private static volatile CommandResult cachedProbeResult;
	private static volatile boolean environmentLogged;
	private static volatile boolean unavailableLogged;
	private static volatile String unavailableReason = "";
	private static volatile DetectionState detectionState = DetectionState.UNKNOWN;
	private static final Set<String> rejectedCommandPrefixes = ConcurrentHashMap.newKeySet();

	private PythonImageSearchClient() {
	}

	private enum DetectionState {
		UNKNOWN,
		AVAILABLE,
		UNAVAILABLE
	}

	public record ResolvedPython(List<String> commandPrefix, String source, String versionText) {
		public ResolvedPython {
			commandPrefix = List.copyOf(commandPrefix);
		}
	}

	public record CommandResult(List<String> command, boolean started, int exitCode, String stdout, String stderr, String error) {
		public CommandResult {
			command = command == null ? List.of() : List.copyOf(command);
			stdout = stdout == null ? "" : stdout;
			stderr = stderr == null ? "" : stderr;
			error = error == null ? "" : error;
		}

		public boolean succeeded() {
			return started && exitCode == 0;
		}
	}

	public record SetupStep(String name, CommandResult result) {
	}

	public record SetupResult(boolean success, List<SetupStep> steps, String summary) {
		public SetupResult {
			steps = steps == null ? List.of() : List.copyOf(steps);
			summary = summary == null ? "" : summary;
		}
	}

	public static synchronized CommandResult probePythonVersion() {
		logEnvironmentSnapshotOnce();

		if (cachedPython != null && isResolvedPythonValid(cachedPython)) {
			detectionState = DetectionState.AVAILABLE;
			return cachedProbeResult != null ? cachedProbeResult : new CommandResult(cachedPython.commandPrefix(), true, 0, cachedPython.versionText(), "", "");
		}

		if (cachedPython != null) {
			logger.warn("[PYTHON] La ruta cacheada dejó de ser válida y será descartada.");
			clearPythonCache();
		}

		if (detectionState == DetectionState.UNAVAILABLE) {
			return cachedProbeResult != null ? cachedProbeResult : new CommandResult(List.of(), false, -1, "", "", unavailableReason);
		}

		List<Candidate> candidates = buildCandidates();
		CommandResult lastFailure = null;

		for (Candidate candidate : candidates) {
			if (!rejectedCommandPrefixes.add(String.join("\u0000", candidate.commandPrefix()))) {
				continue;
			}

			CommandResult result = validateCandidate(candidate);
			if (isUsablePython(result)) {
				String versionText = extractVersionText(result.stdout(), result.stderr());
				cachedPython = new ResolvedPython(candidate.commandPrefix(), candidate.source(), versionText);
				cachedProbeResult = result;
				detectionState = DetectionState.AVAILABLE;
				logger.info("[PYTHON] Python detectado correctamente:\n{}", String.join(" ", candidate.commandPrefix()));
				return result;
			}

			lastFailure = result;
			logger.debug("[PYTHON] Candidato descartado: {} | exitCode={} | error={} | stderr={}",
					candidate.source(), result.exitCode(), trim(result.error()), trim(result.stderr()));
		}

		if (lastFailure == null) {
			lastFailure = new CommandResult(List.of(), false, -1, "", "", "No se encontraron candidatos de Python");
		}
		markPythonUnavailable(lastFailure, "Python no encontrado. Se utilizará fallback visual.");
		return lastFailure;
	}

	public static Optional<ResolvedPython> resolvePython() {
		probePythonVersion();
		return Optional.ofNullable(cachedPython);
	}

	public static CommandResult runPythonCommand(List<String> args, Path workingDirectory, int timeoutSeconds) {
		Optional<ResolvedPython> resolved = resolvePython();
		if (resolved.isEmpty()) {
			if (cachedProbeResult != null) {
				return cachedProbeResult;
			}
			return new CommandResult(List.of(), false, -1, "", "", unavailableReason.isBlank() ? "No se encontró Python en este sistema" : unavailableReason);
		}

		List<String> command = new ArrayList<>(resolved.get().commandPrefix());
		command.addAll(args);
		CommandResult result = runCommand(command, workingDirectory, timeoutSeconds);
		if (!result.started() || result.exitCode() != 0) {
			if (result.error().toLowerCase(Locale.ROOT).contains("createprocess error=2") || result.error().toLowerCase(Locale.ROOT).contains("no such file")) {
				clearPythonCache();
				probePythonVersion();
				Optional<ResolvedPython> retry = Optional.ofNullable(cachedPython);
				if (retry.isPresent()) {
					List<String> retryCommand = new ArrayList<>(retry.get().commandPrefix());
					retryCommand.addAll(args);
					return runCommand(retryCommand, workingDirectory, timeoutSeconds);
				}
			}
		}
		return result;
	}

	/**
	 * Lanza pose_analyzer.py en modo batch: un único proceso Python que carga el modelo
	 * una sola vez y analiza todas las imágenes en secuencia.
	 * Escribe las rutas en stdin (una por línea) y lee los resultados JSON de stdout
	 * (una línea JSON por imagen). Devuelve la lista de líneas JSON recibidas.
	 */
	public static List<String> runBatchScript(Path scriptPath, List<String> imagePaths, int timeoutSeconds) {
		return runBatchScript(scriptPath, imagePaths, timeoutSeconds, null);
	}

	/**
	 * Same as {@link #runBatchScript(Path, List, int)} but calls {@code onImageDone} with the
	 * running count of results received so far — once per JSON line returned by Python.
	 * This allows callers to report real per-image progress while the process streams output.
	 */
	public static List<String> runBatchScript(Path scriptPath, List<String> imagePaths, int timeoutSeconds,
	                                           Consumer<Integer> onImageDone) {
		if (scriptPath == null || imagePaths == null || imagePaths.isEmpty()) {
			return List.of();
		}
		Optional<ResolvedPython> resolved = resolvePython();
		if (resolved.isEmpty()) {
			logger.warn("[PYTHON] Python no disponible para análisis batch.");
			return List.of();
		}

		List<String> command = new ArrayList<>(resolved.get().commandPrefix());
		command.add(scriptPath.toAbsolutePath().toString());
		command.add("--batch");

		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(scriptPath.getParent().toFile());

		try {
			Process process = pb.start();
			List<String> results = new ArrayList<>();

			// Hilo dedicado a escribir rutas en stdin y cerrar el stream
			Thread stdinWriter = new Thread(() -> {
				try (var writer = new java.io.OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
					for (String path : imagePaths) {
						writer.write(path);
						writer.write('\n');
					}
				} catch (IOException ignored) {}
			}, "batch-stdin-writer");

			// Hilo dedicado a vaciar stderr (evita bloqueos de buffer)
			StringBuilder stderrBuf = new StringBuilder();
			Thread stderrReader = readStreamAsync(process.getErrorStream(), stderrBuf);

			stdinWriter.start();
			stderrReader.start();

			// Leer stdout línea a línea (cada línea = un JSON de resultado)
			try (var reader = new java.io.BufferedReader(
					new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				int doneCount = 0;
				while ((line = reader.readLine()) != null) {
					String trimmed = line.trim();
					if (!trimmed.isEmpty()) {
						results.add(trimmed);
						doneCount++;
						if (onImageDone != null) onImageDone.accept(doneCount);
					}
				}
			}

			joinQuietly(stdinWriter);
			joinQuietly(stderrReader);
			boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				logger.warn("[PYTHON] Batch timeout después de {} s analizando {} imágenes", timeoutSeconds, imagePaths.size());
			}
			if (!stderrBuf.isEmpty()) {
				logger.debug("[PYTHON] Batch stderr: {}", trim(stderrBuf.toString()));
			}
			logger.info("[PYTHON] Batch completado: {} resultados de {} imágenes solicitadas", results.size(), imagePaths.size());
			return results;
		} catch (IOException e) {
			logger.error("[PYTHON] Error de E/S en batch: {}", e.toString());
			return List.of();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			logger.error("[PYTHON] Batch interrumpido: {}", e.toString());
			return List.of();
		}
	}

	public static CommandResult runPythonScript(Path scriptPath, List<String> args, Path workingDirectory, int timeoutSeconds) {
		if (scriptPath == null) {
			return new CommandResult(List.of(), false, -1, "", "", "El script de Python no fue encontrado");
		}

		List<String> scriptArgs = new ArrayList<>();
		scriptArgs.add(scriptPath.toAbsolutePath().toString());
		if (args != null) {
			scriptArgs.addAll(args);
		}
		Path cwd = workingDirectory != null ? workingDirectory : scriptPath.getParent();
		return runPythonCommand(scriptArgs, cwd, timeoutSeconds);
	}

	public static SetupResult setupVirtualEnvironment() {
		return setupVirtualEnvironment(Paths.get("").toAbsolutePath().normalize(), true);
	}

	public static SetupResult setupVirtualEnvironment(Path projectRoot, boolean installRequirements) {
		Path root = projectRoot != null ? projectRoot.toAbsolutePath().normalize() : Paths.get("").toAbsolutePath().normalize();
		List<SetupStep> steps = new ArrayList<>();

		Optional<ResolvedPython> resolved = resolvePython();
		if (resolved.isEmpty()) {
			return new SetupResult(false, steps, unavailableReason.isBlank()
					? "Python no disponible para crear el entorno virtual"
					: unavailableReason);
		}

		CommandResult createVenv = runCommand(concat(resolved.get().commandPrefix(), List.of("-m", "venv", ".venv")), root, 300);
		steps.add(new SetupStep("create-venv", createVenv));
		if (!createVenv.succeeded()) {
			return new SetupResult(false, steps, "No se pudo crear .venv");
		}

		Path venvPython = resolveVenvPython(root);
		if (venvPython == null) {
			return new SetupResult(false, steps, "No se pudo localizar el Python del entorno virtual");
		}

		steps.add(new SetupStep("upgrade-pip", runCommand(List.of(venvPython.toString(), "-m", "pip", "install", "--upgrade", "pip"), root, 600)));

		Path requirements = root.resolve("requirements.txt");
		if (installRequirements && Files.isRegularFile(requirements)) {
			steps.add(new SetupStep("install-requirements", runCommand(List.of(venvPython.toString(), "-m", "pip", "install", "-r", requirements.toString()), root, 900)));
		}


		boolean ok = steps.stream().allMatch(step -> step.result().succeeded());
		String summary = ok ? "Entorno Python preparado correctamente" : "El auto-setup de Python terminó con errores";
		return new SetupResult(ok, steps, summary);
	}

	public static Path resolveProjectScript(String scriptName) {
		Path script = org.refcolor.buscareferencias.utils.ProjectPaths.resolveScript(scriptName);
		if (script != null) {
			logger.info("[PYTHON] Script encontrado en: {}", script);
			return script;
		}
		logger.warn("[PYTHON] No se encontro {} (buscado en Python/ y raiz del proyecto)", scriptName);
		return null;
	}

	private static List<Candidate> buildCandidates() {
		Map<String, Candidate> candidates = new LinkedHashMap<>();

		addExplicitPathCandidates(candidates, System.getenv("APP_PYTHON_PATH"), "APP_PYTHON_PATH");
		addExplicitPathCandidates(candidates, System.getenv("PYTHON_PATH"), "PYTHON_PATH");
		addExplicitPathCandidates(candidates, System.getProperty("python.path"), "python.path (system property)");
		addExplicitPathCandidates(candidates, readConfigFilePythonPath().toString(), "config.properties/settings.json");
		addExplicitPathCandidates(candidates, FeatureFlags.getRaw("python.path"), "app.properties");

		addVenvCandidates(candidates);
		discoverWhereCandidates(candidates, "python", "where python");
		discoverWhereLauncher(candidates, "py", "where py");
		addCandidate(candidates, List.of("py", "-3"), "py -3");
		addCandidate(candidates, List.of("python"), "python");
		addCandidate(candidates, List.of("python3"), "python3");

		return new ArrayList<>(candidates.values());
	}

	private static void addExplicitPathCandidates(Map<String, Candidate> candidates, String rawPath, String source) {
		for (String candidate : expandExplicitCandidates(rawPath)) {
			addCandidate(candidates, List.of(candidate), source);
		}
	}

	private static List<String> readConfigFilePythonPath() {
		List<Path> configFiles = new ArrayList<>();
		Path configProperties = findConfigFile("config.properties");
		Path settingsJson = findConfigFile("settings.json");
		if (configProperties != null) configFiles.add(configProperties);
		if (settingsJson != null) configFiles.add(settingsJson);

		for (Path file : configFiles) {
			if (file == null) continue;
			try {
				if (file.getFileName().toString().endsWith(".properties")) {
					Properties props = new Properties();
					try (var in = Files.newInputStream(file)) {
						props.load(in);
					}
					String value = firstNonBlank(props.getProperty("python.path"), props.getProperty("pythonPath"), props.getProperty("pythonExecutable"));
					if (isNonBlank(value)) return List.of(value.trim());
				} else if (file.getFileName().toString().endsWith(".json")) {
					String text = Files.readString(file, StandardCharsets.UTF_8);
					JSONObject json = new JSONObject(text);
					String value = extractPythonPathFromJson(json);
					if (isNonBlank(value)) return List.of(value.trim());
				}
			} catch (Exception e) {
				logger.debug("[PYTHON] No se pudo leer configuración {}: {}", file, e.toString());
			}
		}

		return List.of();
	}

	private static List<String> expandExplicitCandidates(String rawPath) {
		if (!isNonBlank(rawPath)) {
			return List.of();
		}

		String value = rawPath.trim();
		List<String> out = new ArrayList<>();
		try {
			Path path = Paths.get(value);
			if (Files.isRegularFile(path)) {
				out.add(path.toAbsolutePath().normalize().toString());
				return out;
			}
			if (Files.isDirectory(path)) {
				List<Path> nested = List.of(
						path.resolve("python.exe"),
						path.resolve("Scripts").resolve("python.exe"),
						path.resolve("bin").resolve("python")
				);
				for (Path nestedPath : nested) {
					if (Files.isRegularFile(nestedPath)) {
						out.add(nestedPath.toAbsolutePath().normalize().toString());
					}
				}
			}
		} catch (Exception ignored) {
			// No era una ruta válida; se probará como comando si corresponde.
		}

		out.add(value);
		return out.stream().distinct().toList();
	}

	private static String extractPythonPathFromJson(JSONObject json) {
		if (json == null) return null;
		String direct = firstNonBlank(json.optString("pythonPath", null), json.optString("python.path", null), json.optString("pythonExecutable", null));
		if (isNonBlank(direct)) return direct;

		if (json.has("python") && json.get("python") instanceof JSONObject nested) {
			return firstNonBlank(nested.optString("path", null), nested.optString("pythonPath", null), nested.optString("pythonExecutable", null));
		}
		return null;
	}

	private static Path findConfigFile(String fileName) {
		Path cwd = Paths.get("").toAbsolutePath().normalize();
		List<Path> roots = new ArrayList<>();
		roots.add(cwd);
		Path parent = cwd.getParent();
		if (parent != null) roots.add(parent);
		Path grandParent = parent != null ? parent.getParent() : null;
		if (grandParent != null) roots.add(grandParent);

		for (Path root : roots) {
			Path candidate = root.resolve(fileName).normalize();
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	private static void addVenvCandidates(Map<String, Candidate> candidates) {
		Path cwd = Paths.get("").toAbsolutePath().normalize();
		List<Path> roots = new ArrayList<>();
		roots.add(cwd);
		Path parent = cwd.getParent();
		if (parent != null) roots.add(parent);
		Path grandParent = parent != null ? parent.getParent() : null;
		if (grandParent != null) roots.add(grandParent);

		for (Path root : roots) {
			addCandidate(candidates, root.resolve(".venv").resolve("Scripts").resolve("python.exe").toString(), ".venv/Scripts/python.exe");
			addCandidate(candidates, root.resolve("venv").resolve("Scripts").resolve("python.exe").toString(), "venv/Scripts/python.exe");
			addCandidate(candidates, root.resolve("env").resolve("Scripts").resolve("python.exe").toString(), "env/Scripts/python.exe");
			addCandidate(candidates, root.resolve(".venv").resolve("bin").resolve("python").toString(), ".venv/bin/python");
			addCandidate(candidates, root.resolve("venv").resolve("bin").resolve("python").toString(), "venv/bin/python");
		}
	}

	private static void discoverWhereCandidates(Map<String, Candidate> candidates, String executableName, String source) {
		CommandResult whereResult = runWhere(executableName);
		if (!whereResult.succeeded()) {
			return;
		}

		for (String line : splitLines(whereResult.stdout())) {
			String candidate = line.trim();
			if (!candidate.isEmpty()) {
				addCandidate(candidates, List.of(candidate), source);
			}
		}
	}

	private static void discoverWhereLauncher(Map<String, Candidate> candidates, String executableName, String source) {
		CommandResult whereResult = runWhere(executableName);
		if (whereResult.succeeded()) {
			addCandidate(candidates, List.of(executableName, "-3"), source);
		}
	}

	private static CommandResult runWhere(String executableName) {
		if (isWindows()) {
			return runCommand(List.of("cmd", "/c", "where", executableName), null, DEFAULT_PROBE_TIMEOUT_SECONDS);
		}
		return runCommand(List.of("which", "-a", executableName), null, DEFAULT_PROBE_TIMEOUT_SECONDS);
	}

	private static void addCandidate(Map<String, Candidate> candidates, String rawPath, String source) {
		if (!isNonBlank(rawPath)) return;
		addCandidate(candidates, List.of(rawPath.trim()), source);
	}

	private static void addCandidate(Map<String, Candidate> candidates, List<String> commandPrefix, String source) {
		if (commandPrefix == null || commandPrefix.isEmpty()) return;
		String key = String.join("\u0000", commandPrefix);
		candidates.putIfAbsent(key, new Candidate(List.copyOf(commandPrefix), source));
	}

	private static CommandResult runCommand(List<String> command, Path workingDirectory, int timeoutSeconds) {
		ProcessBuilder pb = new ProcessBuilder(command);
		if (workingDirectory != null) {
			pb.directory(workingDirectory.toFile());
		}

		try {
			Process process = pb.start();
			StringBuilder stdout = new StringBuilder();
			StringBuilder stderr = new StringBuilder();

			Thread outThread = readStreamAsync(process.getInputStream(), stdout);
			Thread errThread = readStreamAsync(process.getErrorStream(), stderr);
			outThread.start();
			errThread.start();

			boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				joinQuietly(outThread);
				joinQuietly(errThread);
				return new CommandResult(command, true, -1, stdout.toString(), stderr.toString(), "Timeout ejecutando: " + String.join(" ", command));
			}

			joinQuietly(outThread);
			joinQuietly(errThread);

			int exitCode = process.exitValue();
			return new CommandResult(command, true, exitCode, stdout.toString(), stderr.toString(), "");
		} catch (IOException e) {
			return new CommandResult(command, false, -1, "", "", e.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new CommandResult(command, false, -1, "", "", e.toString());
		}
	}

	private static CommandResult validateCandidate(Candidate candidate) {
		if (candidate == null || candidate.commandPrefix() == null || candidate.commandPrefix().isEmpty()) {
			return new CommandResult(List.of(), false, -1, "", "", "Candidato vacío");
		}

		String first = candidate.commandPrefix().get(0);
		if (looksLikeFilesystemPath(first) && !Files.exists(Paths.get(first))) {
			return new CommandResult(candidate.commandPrefix(), false, -1, "", "", "El ejecutable no existe: " + first);
		}

		List<String> versionCommand = new ArrayList<>(candidate.commandPrefix());
		versionCommand.add("--version");
		return runCommand(versionCommand, null, DEFAULT_PROBE_TIMEOUT_SECONDS);
	}

	private static boolean isResolvedPythonValid(ResolvedPython resolved) {
		if (resolved == null || resolved.commandPrefix() == null || resolved.commandPrefix().isEmpty()) {
			return false;
		}
		if (looksLikeFilesystemPath(resolved.commandPrefix().get(0)) && !Files.exists(Paths.get(resolved.commandPrefix().get(0)))) {
			return false;
		}
		CommandResult result = validateCandidate(new Candidate(resolved.commandPrefix(), resolved.source()));
		return isUsablePython(result);
	}

	private static Thread readStreamAsync(InputStream stream, StringBuilder target) {
		return new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (!target.isEmpty()) {
						target.append(System.lineSeparator());
					}
					target.append(line);
				}
			} catch (Exception ignored) {
				// La lectura de salida no debe romper el flujo principal.
			}
		}, "python-stream-reader");
	}

	private static void joinQuietly(Thread t) {
		try {
			t.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static boolean isUsablePython(CommandResult result) {
		if (result == null || !result.started()) return false;
		if (result.exitCode() != 0) return false;

		String combined = (result.stdout() + "\n" + result.stderr()).toLowerCase(Locale.ROOT);
		if (combined.contains(STORE_ALIAS_HINT.toLowerCase(Locale.ROOT))) return false;
		if (combined.contains(STORE_ALIAS_HINT_2.toLowerCase(Locale.ROOT))) return false;
		if (combined.contains(STORE_ALIAS_HINT_3.toLowerCase(Locale.ROOT))) return false;

		String version = extractVersionText(result.stdout(), result.stderr()).toLowerCase(Locale.ROOT);
		return version.contains("python");
	}

	private static boolean looksLikeFilesystemPath(String value) {
		if (!isNonBlank(value)) return false;
		String v = value.trim();
		return v.contains("/") || v.contains("\\") || v.matches("^[A-Za-z]:.*");
	}

	private static List<String> splitLines(String text) {
		if (!isNonBlank(text)) {
			return List.of();
		}
		String[] lines = text.split("\\R");
		List<String> out = new ArrayList<>();
		for (String line : lines) {
			if (isNonBlank(line)) {
				out.add(line.trim());
			}
		}
		return out;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static void logEnvironmentSnapshot() {
		logger.info("[PYTHON] PATH actual: {}", System.getenv("PATH"));
		logger.info("[PYTHON] PYTHON_PATH: {}", maskPath(readEnvOrNull("PYTHON_PATH")));
		logger.info("[PYTHON] APP_PYTHON_PATH: {}", maskPath(readEnvOrNull("APP_PYTHON_PATH")));
		String sysProp = System.getProperty("python.path");
		logger.info("[PYTHON] python.path (system property): {}", maskPath(sysProp));
		logger.info("[PYTHON] config.properties/settings.json: {}", describeConfigLocations());
	}

	private static void logEnvironmentSnapshotOnce() {
		if (!environmentLogged) {
			environmentLogged = true;
			logEnvironmentSnapshot();
		}
	}

	private static String describeConfigLocations() {
		Path configProperties = findConfigFile("config.properties");
		Path settingsJson = findConfigFile("settings.json");
		return "config.properties=" + (configProperties != null ? configProperties : "<no>") +
				", settings.json=" + (settingsJson != null ? settingsJson : "<no>");
	}

	private static void markPythonUnavailable(CommandResult result, String message) {
		cachedProbeResult = result;
		unavailableReason = message;
		detectionState = DetectionState.UNAVAILABLE;
		if (!unavailableLogged) {
			unavailableLogged = true;
			logger.warn("[PYTHON] {}", message);
		}
	}

	private static void clearPythonCache() {
		cachedPython = null;
		cachedProbeResult = null;
		detectionState = DetectionState.UNKNOWN;
		rejectedCommandPrefixes.clear();
	}

	private static String readEnvOrNull(String key) {
		String value = System.getenv(key);
		return isNonBlank(value) ? value.trim() : null;
	}

	private static String maskPath(String path) {
		return isNonBlank(path) ? path.trim() : "<no definido>";
	}

	private static boolean isNonBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}

	private static String extractVersionText(String stdout, String stderr) {
		String primary = firstNonBlank(stdout, stderr);
		return primary.isBlank() ? "<sin versión>" : primary.trim();
	}

	private static String firstNonBlank(String a, String b) {
		if (isNonBlank(a)) return a;
		if (isNonBlank(b)) return b;
		return "";
	}

	private static String firstNonBlank(String a, String b, String c) {
		if (isNonBlank(a)) return a;
		if (isNonBlank(b)) return b;
		if (isNonBlank(c)) return c;
		return "";
	}

	private static Path resolveVenvPython(Path root) {
		List<Path> candidates = List.of(
				root.resolve(".venv").resolve("Scripts").resolve("python.exe"),
				root.resolve("venv").resolve("Scripts").resolve("python.exe"),
				root.resolve(".venv").resolve("bin").resolve("python"),
				root.resolve("venv").resolve("bin").resolve("python")
		);
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	private static List<String> concat(List<String> a, List<String> b) {
		List<String> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	private static String trim(String value) {
		if (value == null) return "";
		String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
		if (normalized.length() <= 300) return normalized;
		return normalized.substring(0, 300) + "...";
	}

	private record Candidate(List<String> commandPrefix, String source) {
	}
}
