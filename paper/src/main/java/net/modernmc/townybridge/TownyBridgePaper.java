package net.modernmc.townybridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.function.Consumer;

public final class TownyBridgePaper extends JavaPlugin {
    private HttpServer httpServer;

    @Override
    public void onEnable() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 28645), 16);
            httpServer.createContext("/town", this::handleTownRequest);
            httpServer.createContext("/transaction", this::handleTransactionRequest);
            httpServer.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "TownyBridge-HTTP");
                thread.setDaemon(true);
                return thread;
            }));
            httpServer.start();
            getLogger().info("Towny info and bank bridge listening on 127.0.0.1:28645");
        } catch (IOException error) {
            getLogger().severe("Could not start the Towny bridge: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private void handleTownRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method not allowed.");
            return;
        }
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
            respond(exchange, 403, "Forbidden.");
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            scheduleTownLookup(() -> {
                try {
                    result.complete(townRequest(query));
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
            respond(exchange, 200, result.get(10, TimeUnit.SECONDS));
        } catch (Exception error) {
            getLogger().log(Level.WARNING, "Towny info request failed", error);
            respond(exchange, 503, "Towny info is unavailable right now. Please try again shortly.");
        }
    }

    private String townRequest(Map<String, String> query) throws ReflectiveOperationException {
        Object town;
        if (query.containsKey("uuid")) {
            Object api = townyApi();
            Object resident = api.getClass().getMethod("getResident", UUID.class)
                    .invoke(api, UUID.fromString(query.get("uuid")));
            if (resident == null) {
                return "You do not have a Towny resident profile yet.";
            }
            town = resident.getClass().getMethod("getTownOrNull").invoke(resident);
            if (town == null) {
                return "You are not currently a member of a town.";
            }
        } else if (query.containsKey("town")) {
            Object api = townyApi();
            town = api.getClass().getMethod("getTown", String.class)
                    .invoke(api, query.get("town"));
            if (town == null) {
                return "No town named '" + clean(query.get("town")) + "' was found.";
            }
        } else {
            return "Use /t, /t <town name>, or /t residents.";
        }

        String view = query.getOrDefault("view", "info");
        return switch (view.toLowerCase(java.util.Locale.ROOT)) {
            case "residents" -> formatResidents(town, false);
            case "online" -> formatResidents(town, true);
            default -> formatTown(town);
        };
    }

    private void handleTransactionRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method not allowed.");
            return;
        }
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
            respond(exchange, 403, "Forbidden.");
            return;
        }

        byte[] requestBody = exchange.getRequestBody().readNBytes(4097);
        if (requestBody.length > 4096) {
            respond(exchange, 413, "Request too large.");
            return;
        }
        Map<String, String> request = parseQuery(new String(requestBody, StandardCharsets.UTF_8));
        CompletableFuture<String> result = new CompletableFuture<>();
        AtomicBoolean pending = new AtomicBoolean(true);
        try {
            scheduleTownLookup(() -> {
                if (!pending.compareAndSet(true, false)) {
                    return;
                }
                try {
                    result.complete(transact(request));
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
            respond(exchange, 200, result.get(10, TimeUnit.SECONDS));
        } catch (Exception error) {
            pending.set(false);
            getLogger().log(Level.WARNING, "Towny bank transaction failed", error);
            respond(exchange, 503, "Towny bank transaction is unavailable right now. Please try again shortly.");
        }
    }

    private String transact(Map<String, String> request) throws ReflectiveOperationException {
        UUID playerId;
        try {
            playerId = UUID.fromString(request.getOrDefault("uuid", ""));
        } catch (IllegalArgumentException error) {
            return "Invalid player identity.";
        }

        String action = request.getOrDefault("action", "").toLowerCase(java.util.Locale.ROOT);
        boolean deposit = action.equals("deposit");
        boolean withdraw = action.equals("withdraw");
        if (!deposit && !withdraw) {
            return "Choose deposit or withdraw.";
        }

        Object api = townyApi();
        Object resident = api.getClass().getMethod("getResident", UUID.class).invoke(api, playerId);
        if (resident == null) {
            return "You do not have a Towny resident profile yet.";
        }
        Object town = resident.getClass().getMethod("getTownOrNull").invoke(resident);
        if (town == null) {
            return "You must belong to a town to use its bank.";
        }

        if (withdraw) {
            boolean isMayor = (boolean) town.getClass().getMethod("isMayor", resident.getClass()).invoke(town, resident);
            boolean hasPermission = Boolean.parseBoolean(request.getOrDefault("withdrawPermission", "false"));
            if (!isMayor && !hasPermission) {
                return "You do not have permission to withdraw from the town bank.";
            }
        }

        ClassLoader townyLoader = town.getClass().getClassLoader();
        Class<?> settingsClass = Class.forName("com.palmergames.bukkit.towny.TownySettings", true, townyLoader);
        boolean locationRestricted = (boolean) settingsClass.getMethod("isBankActionDisallowedOutsideTown").invoke(null)
                || (boolean) settingsClass.getMethod("isBankActionLimitedToBankPlots").invoke(null);
        if (locationRestricted) {
            return "Towny requires bank transactions to be made at the town bank in the Towny server.";
        }

        String amountInput = request.getOrDefault("amount", "").trim();
        int amount;
        try {
            if (amountInput.equalsIgnoreCase("all")) {
                Object account = (withdraw ? town : resident).getClass().getMethod("getAccount").invoke(withdraw ? town : resident);
                double balance = (double) account.getClass().getMethod("getHoldingBalance").invoke(account);
                amount = (int) Math.floor(balance);
            } else {
                amount = Integer.parseInt(amountInput);
            }
        } catch (NumberFormatException error) {
            return "Enter a whole-number amount or use 'all'.";
        }
        if (amount <= 0) {
            return "The amount must be greater than zero.";
        }

        Object residentAccount = resident.getClass().getMethod("getAccount").invoke(resident);
        Object townAccount = town.getClass().getMethod("getAccount").invoke(town);
        double residentBefore = (double) residentAccount.getClass().getMethod("getHoldingBalance").invoke(residentAccount);
        double townBefore = (double) townAccount.getClass().getMethod("getHoldingBalance").invoke(townAccount);

        Class<?> playerClass = Class.forName("org.bukkit.entity.Player", true, townyLoader);
        // Towny's native transaction helpers require a Bukkit Player. The caller is on another
        // backend, so supply only the resident identity; location-restricted bank modes are
        // rejected above, while Towny still applies its economy limits and transaction events.
        AtomicReference<String> townyFeedback = new AtomicReference<>();
        String residentName = (String) resident.getClass().getMethod("getName").invoke(resident);
        Object playerProxy = Proxy.newProxyInstance(townyLoader, new Class<?>[]{playerClass},
                offlinePlayerHandler(playerId, residentName, townyFeedback));
        Class<?> townClass = Class.forName("com.palmergames.bukkit.towny.object.Town", true, townyLoader);
        Class<?> residentClass = Class.forName("com.palmergames.bukkit.towny.object.Resident", true, townyLoader);
        Class<?> nationClass = Class.forName("com.palmergames.bukkit.towny.object.Nation", true, townyLoader);
        Class<?> moneyUtil = Class.forName("com.palmergames.bukkit.towny.utils.MoneyUtil", true, townyLoader);
        if (deposit) {
            moneyUtil.getMethod("townDeposit", playerClass, residentClass, townClass, nationClass, int.class)
                    .invoke(null, playerProxy, resident, town, null, amount);
        } else {
            moneyUtil.getMethod("townWithdraw", playerClass, residentClass, townClass, int.class)
                    .invoke(null, playerProxy, resident, town, amount);
        }

        double residentAfter = (double) residentAccount.getClass().getMethod("getHoldingBalance").invoke(residentAccount);
        double townAfter = (double) townAccount.getClass().getMethod("getHoldingBalance").invoke(townAccount);
        boolean sourceMoved = deposit ? residentAfter < residentBefore : townAfter < townBefore;
        boolean destinationMoved = deposit ? townAfter > townBefore : residentAfter > residentBefore;
        if (!sourceMoved || !destinationMoved) {
            String feedback = townyFeedback.get();
            if (sourceMoved != destinationMoved) {
                getLogger().severe("Towny reported a partial " + action + " for " + residentName
                        + " amount " + amount + "; check both account balances before retrying.");
                return "Towny may have only partly applied that transaction. Please contact staff before trying again.";
            }
            if (feedback != null && !feedback.isBlank()) {
                String reason = stripLegacyColors(feedback);
                getLogger().warning("Towny rejected " + action + " for " + residentName + " amount " + amount + ": " + reason);
                return reason;
            }
            getLogger().warning("Towny " + action + " for " + residentName + " amount " + amount
                    + " returned without changing either account balance.");
            return "Towny rejected that transaction. Check the amount, your balance, and town bank settings.";
        }
        String townName = (String) town.getClass().getMethod("getName").invoke(town);
        return (deposit ? "Deposited " : "Withdrew ") + amount
                + (deposit ? " into " : " from ") + clean(townName) + " town bank.";
    }

    private static InvocationHandler offlinePlayerHandler(UUID uuid, String name, AtomicReference<String> townyFeedback) {
        return (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> name;
                case "getLocale" -> java.util.Locale.US;
                case "getLocation" -> null;
                case "isOnline" -> true;
                case "hasPermission" -> false;
                case "sendMessage" -> {
                    if (args != null && args.length > 0 && args[0] != null) {
                        Object message = args[0];
                        String text = message instanceof String[] lines
                                ? String.join(" ", lines)
                                : message.toString();
                        townyFeedback.set(text);
                    }
                    yield null;
                }
                case "toString" -> "TownyBridgePlayer[" + name + "]";
                case "hashCode" -> uuid.hashCode();
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> defaultValue(method.getReturnType());
            };
        };
    }

    private static String stripLegacyColors(String message) {
        return message.replaceAll("(?i)§[0-9A-FK-OR]", "").trim();
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private void scheduleTownLookup(Runnable lookup) throws ReflectiveOperationException {
        try {
            Method getGlobalScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
            Object globalScheduler = getGlobalScheduler.invoke(null);
            Method run = getGlobalScheduler.getReturnType().getMethod("run", Plugin.class, Consumer.class);
            run.invoke(globalScheduler, this, (Consumer<Object>) task -> lookup.run());
        } catch (NoSuchMethodException unavailable) {
            Bukkit.getScheduler().runTask(this, lookup);
        }
    }

    private Object townyApi() throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
        return apiClass.getMethod("getInstance").invoke(null);
    }

    private String formatTown(Object town) throws ReflectiveOperationException {
        ClassLoader townyLoader = town.getClass().getClassLoader();
        Class<?> formatterClass = Class.forName("com.palmergames.bukkit.towny.TownyFormatter", true, townyLoader);
        Class<?> commandSenderClass = Class.forName("org.bukkit.command.CommandSender", true, townyLoader);
        Object console = Bukkit.class.getMethod("getConsoleSender").invoke(null);
        Object statusScreen = formatterClass
                .getMethod("getStatus", town.getClass(), commandSenderClass)
                .invoke(null, town, console);
        Object component = statusScreen.getClass().getMethod("getFormattedStatusScreen").invoke(statusScreen);

        Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component", true, townyLoader);
        Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.gson.GsonComponentSerializer", true, townyLoader);
        Object serializer = serializerClass.getMethod("gson").invoke(null);
        String json = (String) serializerClass.getMethod("serialize", componentClass).invoke(serializer, component);
        return "JSON:" + json;
    }

    private String formatResidents(Object town, boolean onlineOnly) throws ReflectiveOperationException {
        Collection<?> residents = (Collection<?>) town.getClass().getMethod("getResidents").invoke(town);
        ArrayList<Object> selected = new ArrayList<>();
        for (Object resident : residents) {
            if (!onlineOnly || (boolean) resident.getClass().getMethod("isOnline").invoke(resident)) {
                selected.add(resident);
            }
        }

        String heading = onlineOnly ? "Online residents" : "Residents";
        ClassLoader townyLoader = town.getClass().getClassLoader();
        Class<?> formatterClass = Class.forName("com.palmergames.bukkit.towny.TownyFormatter", true, townyLoader);
        String formatted = (String) formatterClass
                .getMethod("getFormattedTownyObjects", String.class, List.class)
                .invoke(null, heading, selected);

        Class<?> legacySerializerClass = Class.forName(
                "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer", true, townyLoader);
        Object legacySerializer = legacySerializerClass.getMethod("legacySection").invoke(null);
        Object component = legacySerializerClass.getMethod("deserialize", String.class)
                .invoke(legacySerializer, formatted);
        Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component", true, townyLoader);
        Class<?> jsonSerializerClass = Class.forName(
                "net.kyori.adventure.text.serializer.gson.GsonComponentSerializer", true, townyLoader);
        Object jsonSerializer = jsonSerializerClass.getMethod("gson").invoke(null);
        String json = (String) jsonSerializerClass.getMethod("serialize", componentClass)
                .invoke(jsonSerializer, component);
        return "JSON:" + json;
    }

    private static Map<String, String> parseQuery(String query) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(query.split("&"))
                .map(part -> part.split("=", 2))
                .filter(parts -> parts.length == 2)
                .collect(Collectors.toMap(parts -> decode(parts[0]), parts -> decode(parts[1]), (left, right) -> left));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String clean(String value) {
        return value.replaceAll("[\\r\\n\\p{Cntrl}]", "");
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
