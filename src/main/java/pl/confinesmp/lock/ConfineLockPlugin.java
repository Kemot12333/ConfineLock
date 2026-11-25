package pl.confinesmp.lock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ConfineLock
 *
 * /confinelock   -> od tej chwili kazdy, kto zginie w ConfineSMP, dostaje blokade wejscia.
 * /confineunlock -> wylacza blokade i odblokowuje wszystkich (lub jednego gracza).
 */
public final class ConfineLockPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String BYPASS_PERMISSION = "confinesmp.bypass";

    /** UUID -> ostatni znany nick zablokowanego gracza. */
    private final Map<UUID, String> locked = new LinkedHashMap<>();
    private final Set<String> confineWorlds = new HashSet<>();

    private boolean active = false;
    private boolean onlyCountInConfine = true;
    private String fallbackWorld = "world";
    private File dataFile;

    // ------------------------------------------------------------------
    //  Cykl zycia pluginu
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        getServer().getPluginManager().registerEvents(this, this);

        for (String name : new String[]{"confinelock", "confineunlock", "confinelist"}) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(this);
                command.setTabCompleter(this);
            }
        }

        getLogger().info("ConfineLock wlaczony. Blokada aktywna: " + active
                + ", zablokowanych graczy: " + locked.size());
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ------------------------------------------------------------------
    //  Konfiguracja i dane
    // ------------------------------------------------------------------

    private void loadSettings() {
        reloadConfig();
        confineWorlds.clear();
        for (String world : getConfig().getStringList("confine-worlds")) {
            confineWorlds.add(world.toLowerCase(Locale.ROOT));
        }
        if (confineWorlds.isEmpty()) {
            confineWorlds.add("confinesmp");
        }
        fallbackWorld = getConfig().getString("fallback-world", "world");
        onlyCountInConfine = getConfig().getBoolean("only-count-deaths-in-confine-world", true);
    }

    private void loadData() {
        locked.clear();
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        active = yaml.getBoolean("active", false);
        ConfigurationSection section = yaml.getConfigurationSection("blocked");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    locked.put(UUID.fromString(key), section.getString(key, "?"));
                } catch (IllegalArgumentException ignored) {
                    // uszkodzony wpis - pomijamy
                }
            }
        }
    }

    private void saveData() {
        if (dataFile == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("active", active);
        for (Map.Entry<UUID, String> entry : locked.entrySet()) {
            yaml.set("blocked." + entry.getKey(), entry.getValue());
        }
        try {
            yaml.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Nie udalo sie zapisac data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Wiadomosci
    // ------------------------------------------------------------------

    private Component color(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    private Component format(String key, String... pairs) {
        String text = getConfig().getString("messages." + key, "&cBrak wiadomosci: " + key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        return color(getConfig().getString("messages.prefix", "") + text);
    }

    private void send(CommandSender to, String key, String... pairs) {
        to.sendMessage(format(key, pairs));
    }

    // ------------------------------------------------------------------
    //  Narzedzia
    // ------------------------------------------------------------------

    private boolean isConfineWorld(World world) {
        return world != null && confineWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    /** Czy gracz ma w tej chwili blokade wejscia do ConfineSMP. */
    private boolean isLocked(Player player) {
        return locked.containsKey(player.getUniqueId()) && !player.hasPermission(BYPASS_PERMISSION);
    }

    /** Spawn swiata lobby (albo pierwszy swiat, ktory nie jest ConfineSMP). */
    private Location fallbackLocation() {
        World world = Bukkit.getWorld(fallbackWorld);
        if (world == null || isConfineWorld(world)) {
            world = null;
            for (World candidate : Bukkit.getWorlds()) {
                if (!isConfineWorld(candidate)) {
                    world = candidate;
                    break;
                }
            }
        }
        return world == null ? null : world.getSpawnLocation();
    }

    /** Jesli zablokowany gracz stoi w ConfineSMP - wyrzuc go do lobby. */
    private void enforce(Player player) {
        if (!player.isOnline() || !isLocked(player) || !isConfineWorld(player.getWorld())) {
            return;
        }
        Location lobby = fallbackLocation();
        if (lobby != null) {
            player.teleport(lobby);
            send(player, "kicked-out");
        }
    }

    // ------------------------------------------------------------------
    //  Eventy
    // ------------------------------------------------------------------

    /** Smierc gracza podczas aktywnej blokady = blokada wejscia. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!active) {
            return;
        }
        Player player = event.getEntity();
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return;
        }
        if (onlyCountInConfine && !isConfineWorld(player.getWorld())) {
            return;
        }
        if (locked.putIfAbsent(player.getUniqueId(), player.getName()) != null) {
            return; // juz zablokowany
        }
        saveData();

        send(player, "you-are-locked");
        if (getConfig().getBoolean("broadcast-lock", true)) {
            Bukkit.broadcast(format("broadcast-lock", "{player}", player.getName()));
        }
        getLogger().info(player.getName() + " zginal - blokada wejscia do ConfineSMP.");
    }

    /** Zablokowany gracz nie odradza sie w ConfineSMP, tylko w lobby. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!isLocked(player)) {
            return;
        }
        if (isConfineWorld(event.getRespawnLocation().getWorld())) {
            Location lobby = fallbackLocation();
            if (lobby != null) {
                event.setRespawnLocation(lobby);
            }
        }
        // zabezpieczenie, gdyby inny plugin nadpisal respawn
        Bukkit.getScheduler().runTaskLater(this, () -> enforce(player), 2L);
    }

    /** Glowna blokada: anuluje kazda teleportacje DO swiata ConfineSMP. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!isLocked(player)) {
            return;
        }
        Location to = event.getTo();
        if (to == null || !isConfineWorld(to.getWorld())) {
            return;
        }
        if (isConfineWorld(event.getFrom().getWorld())) {
            return; // ruch wewnatrz ConfineSMP - nie dotyczy wejscia
        }
        event.setCancelled(true);
        send(player, "blocked-enter");
    }

    /** Zabezpieczenie: jesli jakis plugin przeniosl gracza bez eventu teleportu. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (isLocked(player) && isConfineWorld(player.getWorld())) {
            send(player, "blocked-enter");
            Bukkit.getScheduler().runTask(this, () -> enforce(player));
        }
    }

    /** Gracz wylogowal sie w ConfineSMP, a w miedzyczasie dostal blokade. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (locked.containsKey(player.getUniqueId())) {
            locked.put(player.getUniqueId(), player.getName()); // aktualizacja nicku
        }
        Bukkit.getScheduler().runTaskLater(this, () -> enforce(player), 5L);
    }

    // ------------------------------------------------------------------
    //  Komendy
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "confinelock" -> handleLock(sender);
            case "confineunlock" -> handleUnlock(sender, args);
            case "confinelist" -> handleList(sender);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handleLock(CommandSender sender) {
        if (active) {
            send(sender, "lock-already-enabled");
            return;
        }
        active = true;
        saveData();
        send(sender, "lock-enabled");
        getLogger().info("Blokada WLACZONA przez " + sender.getName());
    }

    private void handleUnlock(CommandSender sender, String[] args) {
        // /confineunlock <gracz> - odblokowanie jednej osoby
        if (args.length >= 1) {
            UUID found = null;
            for (Map.Entry<UUID, String> entry : locked.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(args[0])) {
                    found = entry.getKey();
                    break;
                }
            }
            if (found == null) {
                send(sender, "player-not-locked", "{player}", args[0]);
                return;
            }
            locked.remove(found);
            saveData();
            send(sender, "player-unlocked", "{player}", args[0]);
            Player online = Bukkit.getPlayer(found);
            if (online != null) {
                send(online, "you-were-unlocked");
            }
            return;
        }

        // /confineunlock - koniec blokady dla wszystkich
        if (!active && locked.isEmpty()) {
            send(sender, "lock-not-active");
            return;
        }
        int count = locked.size();
        Set<UUID> ids = new HashSet<>(locked.keySet());
        active = false;
        locked.clear();
        saveData();

        send(sender, "lock-disabled", "{count}", String.valueOf(count));
        for (UUID id : ids) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                send(online, "you-were-unlocked");
            }
        }
        getLogger().info("Blokada WYLACZONA przez " + sender.getName() + " (odblokowano " + count + ").");
    }

    private void handleList(CommandSender sender) {
        send(sender, active ? "status-active" : "status-inactive");
        if (locked.isEmpty()) {
            send(sender, "list-empty");
            return;
        }
        send(sender, "list-header", "{count}", String.valueOf(locked.size()));
        for (String name : locked.values()) {
            send(sender, "list-entry", "{player}", name);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("confineunlock") && args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return locked.values().stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
