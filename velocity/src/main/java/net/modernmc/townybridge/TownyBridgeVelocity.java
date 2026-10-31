package net.modernmc.townybridge;

import java.io.IOException;
import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

public final class TownyBridgeVelocity {
    private static final String BRIDGE_PATH = "Towny";
    private static final int BRIDGE_PORT = 28645;
    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public TownyBridgeVelocity(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }

        String command = event.getCommand().trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String[] parts = command.split("\\s+");
        if (parts.length == 0 || !(parts[0].equalsIgnoreCase("t") || parts[0].equalsIgnoreCase("town"))) {
            return;
        }

        Optional<RegisteredServer> current = player.getCurrentServer().map(connection -> connection.getServer());
        if (current.map(server -> server.getServerInfo().getName().equalsIgnoreCase(BRIDGE_PATH)).orElse(false)) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        UUID playerId = player.getUniqueId();
        if (parts.length >= 2 && parts[1].equalsIgnoreCase("spawn")) {
            teleportToTownSpawn(player, playerId, command);
            return;
        }
        if (parts.length > 1 && (parts[1].equalsIgnoreCase("deposit") || parts[1].equalsIgnoreCase("withdraw"))) {
            if (parts.length != 3 || !isValidAmount(parts[2])) {
                player.sendMessage(Component.text("Usage: /t " + parts[1].toLowerCase(java.util.Locale.ROOT) + " <whole number|all>"));
                return;
            }
            boolean withdrawPermission = player.hasPermission("towny.command.town.withdraw");
            String body = "uuid=" + encode(playerId.toString())
                    + "&action=" + encode(parts[1].toLowerCase(java.util.Locale.ROOT))
                    + "&amount=" + encode(parts[2])
                    + "&withdrawPermission=" + withdrawPermission;
            CompletableFuture.supplyAsync(() -> executeTransaction(body))
                    .completeOnTimeout("Towny bank transaction is unavailable right now. Please try again shortly.", 12000, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> {
                        logger.warn("Towny bank transaction request failed", error);
                        return "Towny bank transaction is unavailable right now. Please try again shortly.";
                    })
                    .thenAccept(message -> proxy.getPlayer(playerId)
                            .ifPresent(online -> online.sendMessage(Component.text(message))));
            return;
        }

        String request = buildRequest(playerId, parts);
        if (request == null) {
            player.sendMessage(Component.text("That Towny action is only available on the Towny server."));
            return;
        }
        CompletableFuture.supplyAsync(() -> queryTown(request))
                .completeOnTimeout("Towny info is unavailable right now. Please try again shortly.", 6500, TimeUnit.MILLISECONDS)
                .exceptionally(error -> {
                    logger.debug("Towny info request failed", error);
                    return "Towny info is unavailable right now. Please try again shortly.";
                })
                .thenAccept(message -> {
                    Component output = message.startsWith("JSON:")
                            ? GsonComponentSerializer.gson().deserialize(message.substring(5))
                            : Component.text(message);
                    proxy.getPlayer(playerId).ifPresent(online -> online.sendMessage(output));
                });
    }

    private static boolean isValidAmount(String amount) {
        if (amount.equalsIgnoreCase("all")) {
            return true;
        }
        try {
            return Integer.parseInt(amount) > 0;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private void teleportToTownSpawn(Player player, UUID playerId, String command) {
        Optional<RegisteredServer> townyServer = proxy.getServer(BRIDGE_PATH);
        if (townyServer.isEmpty()) {
            player.sendMessage(Component.text("The Towny server is unavailable right now. Please try again shortly."));
            return;
        }

        player.createConnectionRequest(townyServer.get()).connect().thenAccept(result -> {
            proxy.getPlayer(playerId).ifPresent(online -> {
                if (result.isSuccessful()) {
                    online.spoofChatInput(command);
                } else {
                    online.sendMessage(Component.text("Could not connect you to the Towny server right now. Please try again shortly."));
                }
            });
        }).exceptionally(error -> {
            logger.warn("Could not connect a player to the Towny server for a spawn request", error);
            proxy.getPlayer(playerId).ifPresent(online -> online.sendMessage(
                    Component.text("Could not connect you to the Towny server right now. Please try again shortly.")));
            return null;
        });
    }

    private String buildRequest(UUID playerId, String[] parts) {
        String view = "info";
        String target = null;
        if (parts.length == 1) {
            return "uuid=" + encode(playerId.toString());
        }

        String action = parts[1].toLowerCase(java.util.Locale.ROOT);
        if (action.equals("residents") || action.equals("resident") || action.equals("res") || action.equals("reslist")) {
            view = "residents";
            if (parts.length > 2) {
                target = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
            }
        } else if (action.equals("online")) {
            view = "online";
            if (parts.length > 2) {
                target = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
            }
        } else if (action.equals("claim") || action.equals("unclaim") || action.equals("new")
                || action.equals("delete") || action.equals("leave") || action.equals("join")) {
            return null;
        } else {
            target = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        }

        String subject = target == null
                ? "uuid=" + encode(playerId.toString())
                : "town=" + encode(target);
        return subject + "&view=" + encode(view);
    }

    private String queryTown(String parameter) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + BRIDGE_PORT + "/town?" + parameter).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(5500);
            connection.setRequestProperty("Accept", "text/plain; charset=utf-8");
            if (connection.getResponseCode() != 200) {
                logger.warn("Towny bridge returned HTTP {}", connection.getResponseCode());
                return "Towny info is unavailable right now. Please try again shortly.";
            }
            try (var input = connection.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException error) {
            logger.warn("Could not reach the Towny info bridge", error);
            return "Towny info is unavailable right now. Please try again shortly.";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String executeTransaction(String body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + BRIDGE_PORT + "/transaction")
                    .toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(11000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (var output = connection.getOutputStream()) {
                output.write(payload);
            }
            if (connection.getResponseCode() != 200) {
                logger.warn("Towny transaction bridge returned HTTP {}", connection.getResponseCode());
                return "Towny bank transaction is unavailable right now. Please try again shortly.";
            }
            try (var input = connection.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException error) {
            logger.warn("Could not reach the Towny transaction bridge", error);
            return "Towny bank transaction is unavailable right now. Please try again shortly.";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
