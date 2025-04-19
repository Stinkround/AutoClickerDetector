package net.blazecraftserver.autoclickerdetection;

import org.bukkit.BanList;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.block.Action;


import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public final class Autoclicker_detection extends JavaPlugin implements Listener {

    private Map<UUID, Deque<Long>> clickTimes;
    private Map<UUID, Integer> violationLevels;
    private FileConfiguration config;
    private String banMessage;
    private boolean detectionEnabled;
    private final Map<UUID, String> awaitingInput = new ConcurrentHashMap<>();

    // Configurable thresholds
    private int maxCps;
    private double minStdDev;
    private int minClicks;
    private int maxConsecutive;
    private double tolerance;
    private int violationThreshold;
    private long timeWindow;

    @Override
    public void onEnable() {
        clickTimes = new ConcurrentHashMap<>();
        violationLevels = new ConcurrentHashMap<>();
        saveDefaultConfig();
        config = getConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("acd").setExecutor(this);
        getLogger().info("AutoclickerDetector enabled.");
    }

    @Override
    public void onDisable() {
        clickTimes.clear();
        violationLevels.clear();
        awaitingInput.clear();
        getLogger().info("AutoclickerDetector disabled.");
    }

    private void loadConfig() {
        detectionEnabled = config.getBoolean("enabled", true);
        maxCps = config.getInt("max_cps", 20);
        minStdDev = config.getDouble("min_std_dev", 3.0);
        minClicks = config.getInt("min_clicks", 15);
        maxConsecutive = config.getInt("max_consecutive", 8);
        tolerance = config.getDouble("tolerance", 3.0);
        violationThreshold = config.getInt("violation_threshold", 2);
        timeWindow = config.getLong("time_window", 10000);
        banMessage = config.getString("ban_message", "[WATCHDOG CHEAT DETECTION] Exploiting - Autoclicking");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command is for players only.");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("autoclicker.admin")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }
        openConfigUI(player);
        return true;
    }

    private void openConfigUI(Player player) {
        Inventory inventory = getServer().createInventory(null, 27,  ChatColor.DARK_AQUA + "Autoclicker Config");
        inventory.setItem(9, createItem(detectionEnabled ? Material.GREEN_CONCRETE : Material.RED_CONCRETE, ChatColor.GREEN + "Toggle Detection", detectionEnabled ? "Enabled" : "Disabled", detectionEnabled ? "Click to disable" : "Click to enable"));
        inventory.setItem(10, createItem(Material.PAPER, ChatColor.YELLOW + "Max CPS", String.valueOf(maxCps), "Click to set (integer)"));
        inventory.setItem(11, createItem(Material.PAPER, ChatColor.YELLOW + "Min Std Dev", String.valueOf(minStdDev), "Click to set (double)"));
        inventory.setItem(12, createItem(Material.PAPER, ChatColor.YELLOW + "Min Clicks", String.valueOf(minClicks), "Click to set (integer)"));
        inventory.setItem(13, createItem(Material.PAPER, ChatColor.YELLOW + "Max Consecutive", String.valueOf(maxConsecutive), "Click to set (integer)"));
        inventory.setItem(14, createItem(Material.PAPER, ChatColor.YELLOW + "Tolerance", String.valueOf(tolerance), "Click to set (double)"));
        inventory.setItem(15, createItem(Material.PAPER, ChatColor.YELLOW + "Violation Threshold", String.valueOf(violationThreshold), "Click to set (integer)"));
        inventory.setItem(16, createItem(Material.PAPER, ChatColor.YELLOW + "Time Window", String.valueOf(timeWindow), "Click to set (long)"));
        inventory.setItem(17, createItem(Material.BOOK, ChatColor.YELLOW + "Ban Message", banMessage, "Click to set (text)"));
        player.openInventory(inventory);
    }

    private ItemStack createItem(Material material, String name, String value, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Current: " + value, ChatColor.GRAY + action));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player) || !event.getView().getTitle().equals(ChatColor.DARK_AQUA + "Autoclicker Config")) {
            return;
        }
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String rawName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (rawName.equals("Toggle Detection")) {
            detectionEnabled = !detectionEnabled;
            config.set("enabled", detectionEnabled);
            saveConfig();
            player.sendMessage(ChatColor.GREEN + "Detection " + (detectionEnabled ? "enabled" : "disabled") + ".");
            openConfigUI(player);
        } else {
            String configKey = switch (rawName) {
                case "Max CPS" -> "max_cps";
                case "Min Std Dev" -> "min_std_dev";
                case "Min Clicks" -> "min_clicks";
                case "Max Consecutive" -> "max_consecutive";
                case "Tolerance" -> "tolerance";
                case "Violation Threshold" -> "violation_threshold";
                case "Time Window" -> "time_window";
                case "Ban Message" -> "ban_message";
                default -> null;
            };
            if (configKey != null) {
                awaitingInput.put(player.getUniqueId(), configKey);
                player.closeInventory();
                player.sendMessage(ChatColor.YELLOW + "Enter new value for " + configKey + " in chat (type 'cancel' to abort).");
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String configKey = awaitingInput.remove(player.getUniqueId());
        if (configKey == null) return;
        event.setCancelled(true);
        String input = event.getMessage().trim();
        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage(ChatColor.RED + "Config change cancelled.");
            return;
        }
        try {
            switch (configKey) {
                case "max_cps":
                case "min_clicks":
                case "max_consecutive":
                case "violation_threshold":
                    int intValue = Integer.parseInt(input);
                    if (intValue <= 0) throw new NumberFormatException("Value must be positive.");
                    config.set(configKey, intValue);
                    break;
                case "min_std_dev":
                case "tolerance":
                    double doubleValue = Double.parseDouble(input);
                    if (doubleValue <= 0) throw new NumberFormatException("Value must be positive.");
                    config.set(configKey, doubleValue);
                    break;
                case "time_window":
                    long longValue = Long.parseLong(input);
                    if (longValue <= 0) throw new NumberFormatException("Value must be positive.");
                    config.set(configKey, longValue);
                    break;
                case "ban_message":
                    config.set(configKey, input);
                    break;
            }
            saveConfig();
            loadConfig();
            player.sendMessage(ChatColor.GREEN + "Set " + configKey + " to " + input + ".");
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid input: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!detectionEnabled) return;
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            recordClick(player);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!detectionEnabled) return;
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            recordClick(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clickTimes.remove(uuid);
        violationLevels.remove(uuid);
        awaitingInput.remove(uuid);
    }

    private void recordClick(Player player) {
        UUID uuid = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Deque<Long> playerClicks = clickTimes.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        playerClicks.addLast(currentTime);
        cleanOldTimestamps(playerClicks, currentTime);
        if (playerClicks.size() >= minClicks) {
            analyzeClicks(player, playerClicks, currentTime);
        }
    }

    private void cleanOldTimestamps(Deque<Long> clicks, long currentTime) {
        while (!clicks.isEmpty() && clicks.getFirst() < currentTime - timeWindow) {
            clicks.removeFirst();
        }
    }

    private void analyzeClicks(Player player, Deque<Long> clicks, long currentTime) {
        List<Long> timestamps = new ArrayList<>(clicks);
        int cps = calculateCps(timestamps, currentTime);
        List<Double> intervals = calculateIntervals(timestamps);

        if (cps > maxCps) {
            flagPlayer(player, "CPS exceeded threshold: " + cps);
            return;
        }

        if (!intervals.isEmpty()) {
            double stdDev = calculateStdDev(intervals);
            if (stdDev < minStdDev && cps > maxCps / 2) {
                flagPlayer(player, "Low standard deviation: " + stdDev + " with CPS: " + cps);
                return;
            }

            int maxConsecutiveSimilar = checkConsecutiveIntervals(intervals);
            if (maxConsecutiveSimilar > maxConsecutive) {
                flagPlayer(player, "Too many consecutive similar intervals: " + maxConsecutiveSimilar);
            }
        }
    }

    private int calculateCps(List<Long> timestamps, long currentTime) {
        long oneSecondAgo = currentTime - 1000;
        return (int) timestamps.stream().filter(t -> t > oneSecondAgo).count();
    }

    private List<Double> calculateIntervals(List<Long> timestamps) {
        List<Double> intervals = new ArrayList<>();
        for (int i = 1; i < timestamps.size(); i++) {
            intervals.add((double) (timestamps.get(i) - timestamps.get(i - 1)));
        }
        return intervals;
    }

    private double calculateStdDev(List<Double> intervals) {
        if (intervals.size() < 2) return Double.MAX_VALUE;
        double sum = 0;
        for (double interval : intervals) {
            sum += interval;
        }
        double mean = sum / intervals.size();
        double sumSqDiff = 0;
        for (double interval : intervals) {
            sumSqDiff += Math.pow(interval - mean, 2);
        }
        double variance = sumSqDiff / (intervals.size() - 1);
        return Math.sqrt(variance);
    }

    private int checkConsecutiveIntervals(List<Double> intervals) {
        int maxConsecutive = 0;
        int currentConsecutive = 1;
        for (int i = 1; i < intervals.size(); i++) {
            if (Math.abs(intervals.get(i) - intervals.get(i - 1)) < tolerance) {
                currentConsecutive++;
                maxConsecutive = Math.max(maxConsecutive, currentConsecutive);
            } else {
                currentConsecutive = 1;
            }
        }
        return maxConsecutive;
    }

    private void flagPlayer(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        int violations = violationLevels.getOrDefault(uuid, 0) + 1;
        violationLevels.put(uuid, violations);
        getLogger().warning("Player " + player.getName() + " flagged for autoclicking: " + reason + " (Violation level: " + violations + ")");

        if (violations >= violationThreshold) {
            getServer().getBanList(BanList.Type.NAME).addBan(
                    player.getName(),
                    banMessage,
                    null,
                    "Watchdog"
            );
            player.kickPlayer(banMessage);
            getServer().broadcastMessage("Player " + player.getName() + " has been permanently banned for: " + banMessage);
            getLogger().severe("Player " + player.getName() + " permanently banned for autoclicking.");
            violationLevels.put(uuid, 0);
        }
    }
}
