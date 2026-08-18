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
import java.util.Base64;
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

    // 哪吒探針設定
    private static final String NEZHA_SERVER = env("NEZHA_SERVER", "nezha.mingfei1981.eu.org");
    private static final String NEZHA_PORT = env("NEZHA_PORT", "443");
    private static final String NEZHA_KEY = env("NEZHA_KEY", "IqAc0EBKNec6Oy46kE");

    // Cloudflare Argo 隧道設定
    private static final String ARGO_DOMAIN = env("ARGO_DOMAIN", "laternodes.mingfei.de5.net");
    private static final String ARGO_TOKEN = env("ARGO_TOKEN", "eyJhIjoiNjgyNWI4YTZjODBhYWQxODlmYWI5ZWEwMDI5YzY2NjgiLCJ0IjoiNmU1YjExMzgtNDMzMy00YjA5LWExODgtOGE0YThiMDBjNGI1IiwicyI6Ik1UQm1NRGMzTjJZdFpUbGlNeTAwT1RsbExXSm1PVEl0TTJSbFpUVTBaR1ppT1RjMyJ9");

    // ECH Server 與 Opera 設定
    private static final String WSPORT = env("WSPORT", "8001");
    private static final String TOKEN = env("TOKEN", "babama123");
    private static final String OPERA = env("OPERA", "0");
    private static final String COUNTRY = env("COUNTRY", "AM");

    // 雙棧核心控制：各自自定義 V4 / V6
    private static final String ECH_IPS = env("ECH_IPS", "4");
    private static final String HY_IPS = env("HY_IPS", "4");

    // Hysteria 2 其他變數
    private static final String ENABLE_HY2 = env("ENABLE_HY2", "1");
    private static final String HY_PORT = env("HY_PORT", "37137");
    private static final String NAME = env("NAME", "MJJ");
    private static final String PASSWORD = UUID_VAL;
    // ====================================================================

    private static final Path RUNTIME_DIR = Path.of("/tmp").toAbsolutePath().normalize();
    private static final Path NEZHA_CONFIG_PATH = RUNTIME_DIR.resolve("nezha.yaml");
    private static final Path HY2_CONFIG_PATH = RUNTIME_DIR.resolve("hy_config.json");
    private static final Path SERVER_KEY_PATH = RUNTIME_DIR.resolve("server.key");
    private static final Path SERVER_CRT_PATH = RUNTIME_DIR.resolve("server.crt");
    private static final Path SUB_TXT_PATH = RUNTIME_DIR.resolve("sub.txt");
    private static final Path SUB_BASE64_PATH = RUNTIME_DIR.resolve("sub_base64.txt");

    private static final String ARCH = detectArch();

    // 用於管理拉起的背景子進程
    private static final List<Process> EXTERNAL_PROCESSES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        validateParams();

        // 1) 啟動 HTTP 保活，防止翼手龍等容器崩潰
        startKeepAliveServer(PORT);

        // 2) 啟動核心邏輯
        startServices();
    }

    private static void validateParams() {
        if (!"4".equals(ECH_IPS) && !"6".equals(ECH_IPS)) {
            System.err.println("Error: ECH_IPS must be 4 or 6");
            System.exit(1);
        }
        if (!"4".equals(HY_IPS) && !"6".equals(HY_IPS)) {
            System.err.println("Error: HY_IPS must be 4 or 6");
            System.exit(1);
        }
    }

    private static void startServices() throws Exception {
        Files.createDirectories(RUNTIME_DIR);
        cleanupOldFiles();

        int echPort = isValidPort(WSPORT) ? Integer.parseInt(WSPORT) : getFreePort();
        int operaPort = getFreePort();

        // 動態下載路徑配置
        String echUrl = "https://github.com/webappstars/ech-hug/releases/download/3.0/ech-tunnel-linux-" + ARCH;
        String operaUrl = "arm64".equals(ARCH)
                ? "https://github.com/Alexey71/opera-proxy/releases/download/v1.22.0/opera-proxy.freebsd-arm64"
                : "https://github.com/Alexey71/opera-proxy/releases/download/v1.22.0/opera-proxy.linux-amd64";
        String cloudflaredUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-" + ARCH;
        String nezhaUrl = "https://github.com/babama1001980/good/releases/download/npc/" + ARCH + "agent";
        String hy2Url = "https://github.com/apernet/hysteria/releases/download/app%2Fv2.6.5/hysteria-linux-" + ARCH;

        // 下載組件
        Path echExe = downloadLibrary(echUrl, "ech-server-linux");
        Path operaExe = downloadLibrary(operaUrl, "opera-linux");
        Path cloudflaredExe = downloadLibrary(cloudflaredUrl, "cloudflared-linux");

        Path nezhaExe = null;
        if (!NEZHA_SERVER.isEmpty() && !NEZHA_KEY.isEmpty()) {
            nezhaExe = downloadLibrary(nezhaUrl, "iccagent");
        }

        Path hy2Exe = null;
        if ("1".equals(ENABLE_HY2)) {
            hy2Exe = downloadLibrary(hy2Url, "icchy");
        }

        // 1) 啟動哪吒探針
        if (nezhaExe != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(nezhaExe.toString());
            List<String> tlsPorts = List.of("443", "8443", "2096", "2087", "2083", "2053");

            if (!NEZHA_PORT.isEmpty()) {
                cmd.addAll(List.of("-s", NEZHA_SERVER + ":" + NEZHA_PORT, "-p", NEZHA_KEY));
                if (tlsPorts.contains(NEZHA_PORT)) {
                    cmd.add("--tls");
                }
            } else {
                generateNezhaConfig();
                cmd.addAll(List.of("-c", NEZHA_CONFIG_PATH.toString()));
            }
            startExternalProcess("Nezha Agent", cmd);
        }

        // 2) 啟動 Opera Proxy
        if ("1".equals(OPERA) && operaExe != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(operaExe.toString());
            cmd.addAll(List.of("-country", COUNTRY.toUpperCase(), "-socks-mode", "-bind-address", "127.0.0.1:" + operaPort));
            startExternalProcess("Opera Proxy", cmd);
        }

        // 3) 啟動 ECH Server
        if (echExe != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(echExe.toString());
            cmd.addAll(List.of("-l", "ws://0.0.0.0:" + echPort));
            if (!TOKEN.isEmpty()) {
                cmd.addAll(List.of("-token", TOKEN));
            }
            if ("1".equals(OPERA)) {
                cmd.addAll(List.of("-f", "socks5://127.0.0.1:" + operaPort));
            }
            startExternalProcess("ECH Server", cmd);
        }

        // 4) 啟動 Hysteria 2 與生成訂閱
        if ("1".equals(ENABLE_HY2) && hy2Exe != null) {
            generateCertificates();
            generateHy2Config();

            List<String> cmd = List.of(hy2Exe.toString(), "server", "-c", HY2_CONFIG_PATH.toString());
            startExternalProcess("Hysteria 2", cmd);

            // 背景生成 HY2 訂閱資訊
            Thread subThread = new Thread(() -> {
                sleep(15000);
                generateHy2Subscription();
            }, "hy2-sub-builder");
            subThread.setDaemon(true);
            subThread.start();
        }

        // 註冊關閉鉤子清理所有子進程
        Runtime.getRuntime().addShutdownHook(new Thread(App::stopAllExternal, "shutdown-hook"));

        // 3 分鐘後（180秒）自動無痕清理文件並清屏
        Thread cleanupThread = new Thread(() -> {
            sleep(180000);
            cleanupFiles();
            clearConsole();
        }, "delayed-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();

        // 5) 啟動 Cloudflared 隧道 (主線程阻塞或保持執行)
        if (cloudflaredExe != null) {
            // 先嘗試更新
            try {
                new ProcessBuilder(cloudflaredExe.toString(), "update").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
            } catch (Exception ignored) {}

            List<String> cmd = new ArrayList<>();
            cmd.add(cloudflaredExe.toString());
            cmd.addAll(List.of("--edge-ip-version", ECH_IPS, "--protocol", "http2"));

            if (!ARGO_TOKEN.isEmpty()) {
                cmd.addAll(List.of("tunnel", "run", "--token", ARGO_TOKEN));
            } else {
                int metricsPort = getFreePort();
                cmd.addAll(List.of("tunnel", "--url", "127.0.0.1:" + echPort, "--metrics", "0.0.0.0:" + metricsPort));
            }
            startExternalProcess("Cloudflared", cmd);
        }

        // 阻塞主線程保持服務常駐
        new CountDownLatch(1).await();
    }

    private static void generateCertificates() {
        try {
            ProcessBuilder pbKey = new ProcessBuilder("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", SERVER_KEY_PATH.toString());
            pbKey.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();

            ProcessBuilder pbCrt = new ProcessBuilder("openssl", "req", "-new", "-x509", "-key", SERVER_KEY_PATH.toString(), "-out", SERVER_CRT_PATH.toString(), "-subj", "/CN=www.bing.com", "-days", "36500");
            pbCrt.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        } catch (Exception e) {
            System.err.println("Failed to generate certs: " + e.getMessage());
        }
    }

    private static void generateHy2Config() throws IOException {
        String json = "{\n" +
                "  \"listen\": \":" + HY_PORT + "\",\n" +
                "  \"tls\": { \"cert\": \"" + SERVER_CRT_PATH.toString() + "\", \"key\": \"" + SERVER_KEY_PATH.toString() + "\" },\n" +
                "  \"auth\": { \"type\": \"password\", \"password\": \"" + PASSWORD + "\" }\n" +
                "}";
        Files.writeString(HY2_CONFIG_PATH, json, StandardCharsets.UTF_8);
    }

    private static void generateHy2Subscription() {
        try {
            String hostIp = fetchIp();
            if ("6".equals(HY_IPS) && hostIp != null && !hostIp.startsWith("[")) {
                hostIp = "[" + hostIp + "]";
            }

            String isp = fetchIsp();
            String subContent = "start install success\n=== HY2 ===\n" +
                    "hysteria2://" + PASSWORD + "@" + hostIp + ":" + HY_PORT + "/?insecure=1&sni=www.bing.com#" + NAME + "-HY-" + isp + "\n";

            Files.writeString(SUB_TXT_PATH, subContent, StandardCharsets.UTF_8);
            String base64Sub = Base64.getEncoder().encodeToString(subContent.getBytes(StandardCharsets.UTF_8));
            Files.writeString(SUB_BASE64_PATH, base64Sub, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Failed to generate sub: " + e.getMessage());
        }
    }

    private static String fetchIp() {
        List<String> urls = "6".equals(HY_IPS)
                ? List.of("https://ipv6.ip.sb", "https://api6.ipify.org")
                : List.of("https://ipv4.ip.sb", "https://api.ipify.org");

        for (String u : urls) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(5)).GET().build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return response.body().trim();
            } catch (Exception ignored) {}
        }
        return "127.0.0.1";
    }

    private static String fetchIsp() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://speed.cloudflare.com/meta")).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                // 簡單解析 JSON 欄位
                String clientIp = extractJsonField(body, "clientIp");
                String asOrganization = extractJsonField(body, "asOrganization");
                return (asOrganization + "-" + clientIp).replaceAll("\\s+", "_");
            }
        } catch (Exception ignored) {}
        return "Unknown_ISP";
    }

    private static String extractJsonField(String json, String fieldName) {
        int idx = json.indexOf("\"" + fieldName + "\"");
        if (idx == -1) return "unknown";
        int start = json.indexOf("\"", idx + fieldName.length() + 3);
        int end = json.indexOf("\"", start + 1);
        if (start != -1 && end != -1) {
            return json.substring(start + 1, end);
        }
        return "unknown";
    }

    private static void startExternalProcess(String name, List<String> command) {
        Thread thread = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
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

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "*/*")
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Files.write(tmp, response.body());
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                target.toFile().setExecutable(true, false);
                return target;
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static void generateNezhaConfig() throws IOException {
        String nzPort = NEZHA_SERVER.contains(":") ? NEZHA_SERVER.substring(NEZHA_SERVER.lastIndexOf(':') + 1) : "";
        boolean tls = List.of("443", "8443", "2096", "2087", "2083", "2053").contains(nzPort);
        String yaml = "client_secret: " + NEZHA_KEY + "\n" +
                "server: " + NEZHA_SERVER + "\n" +
                "tls: " + tls + "\n" +
                "uuid: " + UUID_VAL;
        Files.writeString(NEZHA_CONFIG_PATH, yaml, StandardCharsets.UTF_8);
    }

    private static void cleanupOldFiles() {
        List<String> files = List.of(
                "ech-server-linux", "opera-linux", "cloudflared-linux", "iccagent", "nezha.yaml",
                "icchy", "server.key", "server.crt", "hy_config.json", "sub.txt", "sub_base64.txt"
        );
        for (String file : files) {
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

    private static void clearConsole() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
