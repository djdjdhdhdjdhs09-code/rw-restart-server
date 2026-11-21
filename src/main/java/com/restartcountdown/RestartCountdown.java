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
        if (task != null) {
            task.cancel();
            task = null;
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

    // ---- Countdown ------------------------------------------------------------

    private void start(CommandSender starter) {
        remaining = Math.max(1, getConfig().getInt("countdown-seconds", 30));
        getLogger().info("Restart countdown started by " + starter.getName() + " (" + remaining + "s).");
        task = getServer().getScheduler().runTaskTimer(this, this::tick, 0L, 20L);
    }

    private void tick() {
        if (remaining <= 0) {
            finish();
            return;
        }
        announce(remaining);
        remaining--;
    }

    private void announce(int seconds) {
        FileConfiguration c = getConfig();
        List<Player> players = List.copyOf(getServer().getOnlinePlayers());

        if (c.getBoolean("chat.enabled", true) && c.getIntegerList("chat.at").contains(seconds)) {
            Component chat = fmt("chat", seconds);
            getServer().getConsoleSender().sendMessage(chat);
            players.forEach(p -> p.sendMessage(chat));
        }

        if (c.getBoolean("actionbar.enabled", true)) {
            Component bar = fmt("actionbar", seconds);
            players.forEach(p -> p.sendActionBar(bar));
        }

        if (c.getBoolean("title.enabled", true) && seconds <= c.getInt("title.from", 10)) {
            Title title = Title.title(
                    fmt("title", seconds),
                    fmt("subtitle", seconds),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)));
            players.forEach(p -> p.showTitle(title));
        }

        if (c.getBoolean("sound.enabled", true) && seconds <= c.getInt("sound.from", 10)) {
            try {
                Sound sound = Sound.sound(
                        Key.key(c.getString("sound.name", "block.note_block.pling")),
                        Sound.Source.MASTER,
                        (float) c.getDouble("sound.volume", 1.0),
                        (float) c.getDouble("sound.pitch", 1.0));
                players.forEach(p -> p.playSound(sound));
            } catch (RuntimeException ex) {
                if (!soundWarned) {
                    soundWarned = true;
                    getLogger().warning("Invalid sound.name in config.yml: " + ex.getMessage());
                }
            }
        }
    }

    private void finish() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        shuttingDown = true;
        Component kick = fmt("kick", 0);
        for (Player p : List.copyOf(getServer().getOnlinePlayers())) {
            p.kick(kick);
        }
        getLogger().info("Countdown finished, stopping the server.");
        getServer().shutdown();
    }

    // ---- /cancelrestart -------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (task == null) {
            sender.sendMessage(fmt("not-running", 0));
            return true;
        }
        task.cancel();
        task = null;
        getServer().broadcast(fmt("cancelled", 0));
        getLogger().info("Restart countdown cancelled by " + sender.getName() + ".");
        return true;
    }

    // ---- Helpers --------------------------------------------------------------

    private Component fmt(String key, int seconds) {
        String raw = getConfig().getString("messages." + key, "");
        return MM.deserialize(raw, Placeholder.unparsed("seconds", Integer.toString(seconds)));
    }
  }
