package com.restartcountdown;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Intercepts "stop" / "restart" (console, panel, or in-game), counts down for players,
 * then stops the server. Uses only the public Paper / Adventure API.
 */
public final class RestartCountdown extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private BukkitTask task;
    private BukkitTask animTask;
    private int animStep;
    private int total;
    private int remaining;
    private boolean shuttingDown;
    private boolean soundWarned;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        stopTasks();
    }

    private void stopTasks() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (animTask != null) {
            animTask.cancel();
            animTask = null;
        }
    }

    // ---- Command interception -------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        if (intercept(event.getSender(), event.getCommand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("restartcountdown.admin")) {
            return;
        }
        if (intercept(player, event.getMessage().substring(1))) {
            event.setCancelled(true);
        }
    }

    /** Returns true if the command was taken over by the countdown. */
    private boolean intercept(CommandSender sender, String raw) {
        if (shuttingDown) {
            return false;
        }
        String[] parts = raw.trim().split("\\s+");
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1); // minecraft:stop -> stop
        }
        if (!watchedCommands().contains(label)) {
            return false;
        }
        String bypass = getConfig().getString("bypass-argument", "now");
        if (parts.length > 1 && parts[1].equalsIgnoreCase(bypass)) {
            return false; // "stop now" -> let the real command run
        }
        if (task != null) {
            sender.sendMessage(fmt("already-running", remaining));
            return true;
        }
        if (getConfig().getBoolean("skip-if-empty", false) && getServer().getOnlinePlayers().isEmpty()) {
            return false;
        }
        start(sender);
        return true;
    }

    private List<String> watchedCommands() {
        return getConfig().getStringList("intercepted-commands").stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }
