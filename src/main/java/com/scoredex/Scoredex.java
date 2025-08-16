package com.scoredex;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

public class Scoredex implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("scoredex");
    private static final String MOD_ID = "scoredex";
    private static final int DEFAULT_PORT = 8080;
    private static final int UPDATE_INTERVAL_MINUTES = 5;
    private HttpServer server;
    private final Map<String, String> uuidToNameCache = new HashMap<>();
    private final Path configPath;
    private Config config;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private BufferedImage currentGeneralScoreboardImage;
    private BufferedImage currentShinyScoreboardImage;
    private BufferedImage currentLegendaryScoreboardImage;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private MinecraftServer minecraftServer;
    private final Set<String> legendaries = new HashSet<>();

    private static class Config {
        int port = DEFAULT_PORT;
        int updateIntervalMinutes = UPDATE_INTERVAL_MINUTES;
        String imageTitle = "Scoreboard - Pokémon Capturés";
        String shinyImageTitle = "Scoreboard - Shinies Capturés";
        String legendaryImageTitle = "Scoreboard - Légendaires Capturés";
        String timeZone = "Europe/Paris";
        boolean autoDetectDataFolder = true;
        String manualDataFolderPath = "";
        int maxPlayers = 100;
        int rowsPerColumn = 10;
        boolean showLastUpdate = true;
        String lastUpdateText = "Dernière mise à jour :";
        Map<String, String> colors = Map.of(
                "background", "#141414",
                "titleBackground", "#3232C8",
                "titleText", "#FFFFFF",
                "topPlayerText", "#FFFFFF",
                "firstPlaceBackground", "#FFD700",
                "secondPlaceBackground", "#C0C0C0",
                "thirdPlaceBackground", "#CD7F32",
                "text", "#FFFFFF",
                "footerText", "#FFFF00"
        );
    }

    public Scoredex() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("scoredex.json");
        loadConfig();
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Initialisation du mod Scoredex...");

        try (InputStream inputStream = Scoredex.class.getClassLoader().getResourceAsStream("assets/scoredex/legendaries.json");
             InputStreamReader reader = new InputStreamReader(inputStream)) {
            JsonArray jsonArray = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : jsonArray)
            {
                legendaries.add(element.getAsString());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            registerCommands(dispatcher);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Serveur Minecraft démarré - Initialisation du scoreboard...");
            this.minecraftServer = server;
            startWebServer();
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    updateScoreboard();
                    updateScoreboardScores();
                } catch (Exception e) {
                    LOGGER.error("Erreur pendant la mise à jour du scoreboard", e);
                }
            }, 0, config.updateIntervalMinutes, TimeUnit.MINUTES);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Serveur Minecraft s'arrête - Fermeture du serveur web...");
            stopWebServer();
            scheduler.shutdown();
        });

        LOGGER.info("Scoredex initialisé avec succès ! Serveur web sera disponible sur le port {}", config.port);
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("reloadscoredex")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(this::reloadConfigCommand));
    }

    private int reloadConfigCommand(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        try {
            loadConfig();
            context.getSource().sendFeedback(() -> Text.of("La configuration Scoredex a été rechargée avec succès."), true);
            updateScoreboard();
            updateScoreboardScores();
        } catch (Exception e) {
            context.getSource().sendError(Text.of("Erreur lors du rechargement de la configuration : " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    private void loadConfig() {
        try {
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath);
                config = gson.fromJson(content, Config.class);
                LOGGER.info("Configuration chargée depuis {}", configPath);
            } else {
                config = new Config();
                saveConfig();
                LOGGER.info("Configuration par défaut créée et sauvegardée dans {}", configPath);
            }
        } catch (Exception e) {
            LOGGER.error("Erreur lors du chargement de la configuration", e);
            config = new Config();
        }
    }

    private void saveConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, gson.toJson(config));
        } catch (IOException e) {
            LOGGER.error("Erreur lors de la sauvegarde de la configuration", e);
        }
    }

    private void startWebServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(config.port), 0);
            server.createContext("/", new ScoreboardHandler());
            server.createContext("/api/", new ScoreboardApiHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            LOGGER.info("Serveur web démarré sur le port {}", config.port);
        } catch (IOException e) {
            LOGGER.error("Erreur lors du démarrage du serveur web", e);
        }
    }

    private void stopWebServer() {
        if (server != null) {
            server.stop(0);
            LOGGER.info("Serveur web arrêté");
        }
    }

    private Path findCobblemonDataFolder() {
        if (!config.autoDetectDataFolder && !config.manualDataFolderPath.isEmpty()) {
            Path manualPath = Paths.get(config.manualDataFolderPath);
            if (Files.exists(manualPath)) {
                return manualPath;
            } else {
                LOGGER.warn("Le chemin manuel spécifié n'existe pas: {}", config.manualDataFolderPath);
            }
        }
        try {
            if (minecraftServer != null) {
                Path worldPath = minecraftServer.getSavePath(WorldSavePath.ROOT).toAbsolutePath();
                String worldPathString = worldPath.toString().replaceAll("\\\\.$", "");
                LOGGER.info("Chemin du monde Minecraft (corrigé): {}", worldPathString);
                worldPath = Paths.get(worldPathString);
                Path cobblemonPath = worldPath.resolve("cobblemonplayerdata");
                if (Files.exists(cobblemonPath) && Files.isDirectory(cobblemonPath)) {
                    LOGGER.info("Dossier de données Cobblemon trouvé: {}", cobblemonPath);
                    return cobblemonPath;
                }
                try {
                    Optional<Path> foundPath = Files.walk(worldPath, 3)
                            .filter(p -> p.getFileName().toString().equals("cobblemonplayerdata"))
                            .filter(Files::isDirectory)
                            .findFirst();
                    if (foundPath.isPresent()) {
                        LOGGER.info("Dossier de données Cobblemon trouvé: {}", foundPath.get());
                        return foundPath.get();
                    }
                } catch (IOException e) {
                    LOGGER.error("Erreur lors de la recherche du dossier cobblemonplayerdata", e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Erreur lors de la détection du dossier de données", e);
        }
        LOGGER.warn("Impossible de trouver le dossier cobblemonplayerdata automatiquement. " +
                "Veuillez spécifier le chemin manuellement dans le fichier de configuration.");
        return null;
    }

    private void updateScoreboard() {
        LOGGER.info("Mise à jour du scoreboard...");
        try {
            Path dataFolderPath = findCobblemonDataFolder();
            if (dataFolderPath == null || !Files.exists(dataFolderPath)) {
                LOGGER.error("Le dossier de données Cobblemon n'a pas été trouvé.");
                return;
            }
            List<PlayerScore> scores = collectPlayerScores(dataFolderPath);
            currentGeneralScoreboardImage = generateScoreboardImage(scores, ScoreType.GENERAL);
            currentShinyScoreboardImage = generateScoreboardImage(scores, ScoreType.SHINY);
            currentLegendaryScoreboardImage = generateScoreboardImage(scores, ScoreType.LEGENDARY);
            LOGGER.info("Scoreboard mis à jour avec {} joueurs", scores.size());
        } catch (Exception e) {
            LOGGER.error("Erreur lors de la mise à jour du scoreboard", e);
        }
    }

    private void updateScoreboardScores() {
        if (minecraftServer == null) return;

        try {
            ServerScoreboard serverScoreboard = minecraftServer.getScoreboard();
            ScoreboardObjective objective = serverScoreboard.getNullableObjective("capturedPokemonCount");

            if (objective == null) {
                objective = serverScoreboard.addObjective(
                        "capturedPokemonCount",
                        ScoreboardCriterion.DUMMY,
                        Text.literal("Pokémon Capturés"),
                        ScoreboardCriterion.RenderType.INTEGER,
                        true,
                        null
                );
            }

            Path dataFolderPath = findCobblemonDataFolder();
            if (dataFolderPath == null) return;

            List<PlayerScore> scores = collectPlayerScores(dataFolderPath);

            for (PlayerScore playerScore : scores) {
                ScoreHolder holder = ScoreHolder.fromName(playerScore.name());
                ScoreAccess access = serverScoreboard.getOrCreateScore(holder, objective);
                if (access.getScore() != playerScore.score()) {
                    access.setScore(playerScore.score());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Erreur lors de la mise à jour silencieuse des scores du scoreboard", e);
        }
    }

    private List<PlayerScore> collectPlayerScores(Path dataFolderPath) throws IOException {
        List<PlayerScore> scores = new ArrayList<>();
        Files.walk(dataFolderPath)
                .filter(path -> path.toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        LOGGER.info("Lecture du fichier: " + path);
                        JsonObject data = JsonParser.parseReader(new FileReader(path.toFile())).getAsJsonObject();
                        if (data.has("uuid")) {
                            String uuid = data.get("uuid").getAsString();
                            String playerName = getPlayerName(uuid);
                            if (playerName != null) {
                                JsonObject advancementData = data.getAsJsonObject("advancementData");
                                if (advancementData != null && advancementData.has("totalCaptureCount")) {

                                    JsonObject aspectsCollected = advancementData.getAsJsonObject("aspectsCollected");
                                    int capturedCount = 0;
                                    int shinyCount = advancementData.get("totalShinyCaptureCount").getAsInt();
                                    int legendaryCount = 0;

                                    List<String> genders = List.of("genderless", "male", "female");
                                    for (String key : aspectsCollected.keySet()) {
                                        if (legendaries.contains(key)) legendaryCount++;

                                        int i = 0;
                                        for (JsonElement v : aspectsCollected.get(key).getAsJsonArray()) {
                                            String value = v.getAsString().trim().toLowerCase();
                                            LOGGER.info("Raw value: '" + v.getAsString() + "'");
                                            if (!genders.contains(value)) {
                                                i++;
                                            }
                                        }
                                        if (i == 0) i = 1;
                                        capturedCount += i;
                                    }

                                    scores.add(new PlayerScore(playerName, capturedCount, shinyCount, legendaryCount));
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error("Erreur lors de la lecture du fichier {}", path, e);
                    }
                });
        return scores;
    }

    private String getPlayerName(String uuid) {
        if (uuidToNameCache.containsKey(uuid)) {
            return uuidToNameCache.get(uuid);
        }
        if (minecraftServer != null) {
            Optional<String> playerName = minecraftServer.getPlayerManager().getPlayerList().stream()
                    .filter(player -> player.getUuidAsString().equals(uuid))
                    .map(player -> player.getName().getString())
                    .findFirst();
            if (playerName.isPresent()) {
                uuidToNameCache.put(uuid, playerName.get());
                return playerName.get();
            }
            try {
                Optional<String> cachedName = minecraftServer.getUserCache().getByUuid(UUID.fromString(uuid))
                        .map(gameProfile -> gameProfile.getName());
                if (cachedName.isPresent()) {
                    uuidToNameCache.put(uuid, cachedName.get());
                    return cachedName.get();
                }
            } catch (Exception e) {
                LOGGER.warn("Erreur lors de la récupération du nom depuis le UserCache pour l'UUID {}", uuid, e);
            }
        }
        String uuidWithoutDashes = uuid.replace("-", "");
        String apiUrl = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuidWithoutDashes;
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    JsonObject response = JsonParser.parseReader(in).getAsJsonObject();
                    if (response.has("name")) {
                        String name = response.get("name").getAsString();
                        uuidToNameCache.put(uuid, name);
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Erreur lors de la récupération du nom pour l'UUID {}", uuid, e);
        }
        return null;
    }

    private int countRewards(JsonObject data) {
        try {
            if (data.has("party") && data.get("party").isJsonObject()) {
                JsonObject party = data.getAsJsonObject("party");
                if (party.has("completedQuests") && party.get("completedQuests").isJsonObject()) {
                    JsonObject completedQuests = party.getAsJsonObject("completedQuests");
                    if (completedQuests.has("Cobbledex_RewardHistory") && completedQuests.get("Cobbledex_RewardHistory").isJsonArray()) {
                        return completedQuests.getAsJsonArray("Cobbledex_RewardHistory").size();
                    }
                }
            }
            return 0;
        } catch (Exception e) {
            LOGGER.error("Erreur lors du comptage des récompenses", e);
            return 0;
        }
    }

    private void sortPlayerScores(List<PlayerScore> scores, ScoreType scoreType) {
        switch (scoreType) {
            case SHINY:
                scores.sort(Comparator.comparingInt(PlayerScore::shinyScore).reversed());
                break;
            case LEGENDARY:
                scores.sort(Comparator.comparingInt(PlayerScore::legendaryScore).reversed());
                break;
            case GENERAL:
            default:
                scores.sort(Comparator.comparingInt(PlayerScore::score).reversed());
                break;
        }
    }
    
    private BufferedImage generateScoreboardImage(List<PlayerScore> unsortedScores, ScoreType scoreType) {
        List<PlayerScore> scores = new ArrayList<>(unsortedScores);
        sortPlayerScores(scores, scoreType);

        int maxPlayers = Math.min(config.maxPlayers, scores.size());
        int columns = (int) Math.ceil((double) maxPlayers / config.rowsPerColumn);
        int width = 600 * columns;
        int height = 150 + config.rowsPerColumn * 50;
        
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        Map<String, String> colors = config.colors;
        Color backgroundColor = Color.decode(colors.get("background"));
        Color titleBackgroundColor = Color.decode(colors.get("titleBackground"));
        Color titleTextColor = Color.decode(colors.get("titleText"));
        Color topPlayerTextColor = Color.decode(colors.get("topPlayerText"));
        Color firstPlaceBackground = Color.decode(colors.get("firstPlaceBackground"));
        Color secondPlaceBackground = Color.decode(colors.get("secondPlaceBackground"));
        Color thirdPlaceBackground = Color.decode(colors.get("thirdPlaceBackground"));
        Color textColor = Color.decode(colors.get("text"));
        Color footerTextColor = Color.decode(colors.get("footerText"));

        g2d.setColor(backgroundColor);
        g2d.fillRect(0, 0, width, height);

        int headerHeight = 80;
        g2d.setColor(titleBackgroundColor);
        g2d.fillRect(0, 0, width, headerHeight);

        g2d.setColor(titleTextColor);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 28));
        FontMetrics headerFontMetrics = g2d.getFontMetrics();

        String title = switch (scoreType) {
            case SHINY -> config.shinyImageTitle;
            case LEGENDARY -> config.legendaryImageTitle;
            default -> config.imageTitle;
        };

        int titleWidth = headerFontMetrics.stringWidth(title);
        g2d.drawString(title, (width - titleWidth) / 2, headerHeight / 2 + headerFontMetrics.getAscent() / 2);

        g2d.setFont(new Font("SansSerif", Font.PLAIN, 24));
        FontMetrics scoreFontMetrics = g2d.getFontMetrics();
        int yOffset = headerHeight + 10;

        for (int i = 0; i < maxPlayers; i++) {
            int rank = i + 1;
            PlayerScore playerScore = scores.get(i);

            int column = i / config.rowsPerColumn;
            int xOffset = column * 600;
            int yPosition = yOffset + (i % config.rowsPerColumn) * 50;

            if (rank == 1) {
                g2d.setColor(firstPlaceBackground);
            } else if (rank == 2) {
                g2d.setColor(secondPlaceBackground);
            } else if (rank == 3) {
                g2d.setColor(thirdPlaceBackground);
            } else {
                g2d.setColor(backgroundColor);
            }
            g2d.fillRect(xOffset + 20, yPosition - 5, 560, 45);

            g2d.setColor(rank <= 3 ? topPlayerTextColor : textColor);
            String scoreText = String.format("%d. %s: %d", rank, playerScore.name(), playerScore.getScore(scoreType));
            g2d.drawString(scoreText, xOffset + 30, yPosition + scoreFontMetrics.getAscent());
        }

        if (config.showLastUpdate) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
            LocalDateTime now = LocalDateTime.now(ZoneId.of(config.timeZone));
            String footerText = config.lastUpdateText + " " + formatter.format(now);
            g2d.setColor(footerTextColor);
            int footerWidth = scoreFontMetrics.stringWidth(footerText);
            g2d.drawString(footerText, (width - footerWidth) / 2, height - 20);
        }

        g2d.dispose();
        return image;
    }
    
    private class ScoreboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                BufferedImage image = null;
                if (exchange.getRequestURI().getPath().endsWith("/scoreboard.png")) {
                    image = currentGeneralScoreboardImage;
                } else if (exchange.getRequestURI().getPath().endsWith("/shiny.png")) {
                    image = currentShinyScoreboardImage;
                } else if (exchange.getRequestURI().getPath().endsWith("/legendary.png")) {
                    image = currentLegendaryScoreboardImage;
                }

                if (image == null) {
                    exchange.sendResponseHeaders(503, 0);
                    exchange.getResponseBody().close();
                    return;
                }

                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.getResponseHeaders().add("Cache-Control", "no-cache, no-store, must-revalidate");
                exchange.sendResponseHeaders(200, 0);
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "PNG", baos);
                byte[] imageBytes = baos.toByteArray();
                
                OutputStream os = exchange.getResponseBody();
                os.write(imageBytes);
                os.close();
            } catch (Exception e) {
                LOGGER.error("Erreur lors de la gestion de la requête HTTP", e);
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().close();
            }
        }
    }
    
    private class ScoreboardApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Path dataFolderPath = findCobblemonDataFolder();
                if (dataFolderPath == null || !Files.exists(dataFolderPath)) {
                    sendJsonResponse(exchange, 404, Collections.singletonMap("error", "Data folder not found"));
                    return;
                }

                ScoreType scoreType = null;
                if (exchange.getRequestURI().getPath().endsWith("/scoreboard")) {
                    scoreType = ScoreType.GENERAL;
                } else if (exchange.getRequestURI().getPath().endsWith("/shiny")) {
                    scoreType = ScoreType.SHINY;
                } else if (exchange.getRequestURI().getPath().endsWith("/legendary")) {
                    scoreType = ScoreType.LEGENDARY;
                }
                
                List<PlayerScore> scores = collectPlayerScores(dataFolderPath);
                sortPlayerScores(scores, scoreType);
                
                List<Map<String, Object>> scoreList = new ArrayList<>();
                for (int i = 0; i < scores.size(); i++) {
                    PlayerScore score = scores.get(i);
                    Map<String, Object> playerData = new HashMap<>();
                    playerData.put("rank", i + 1);
                    playerData.put("name", score.name());
                    playerData.put("score", score.getScore(scoreType));
                    scoreList.add(playerData);
                }
                
                Map<String, Object> response = new HashMap<>();
                response.put("lastUpdate", LocalDateTime.now(ZoneId.of(config.timeZone)).toString());
                response.put("scores", scoreList);
                
                sendJsonResponse(exchange, 200, response);
            } catch (Exception e) {
                LOGGER.error("Erreur lors de la gestion de la requête API", e);
                sendJsonResponse(exchange, 500, Collections.singletonMap("error", "Internal server error"));
            }
        }
        
        private void sendJsonResponse(HttpExchange exchange, int statusCode, Map<String, Object> data) throws IOException {
            String response = gson.toJson(data);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    private record PlayerScore(String name, int score, int shinyScore, int legendaryScore) {
        int getScore(ScoreType scoreType) {
            return switch (scoreType) {
                case SHINY -> shinyScore;
                case LEGENDARY -> legendaryScore;
                default -> score;
            };
        }
    }

    private enum ScoreType {
        GENERAL,
        SHINY,
        LEGENDARY
    }

}