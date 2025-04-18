package me.yourname.lightselector;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Levelled;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class LightSelector extends JavaPlugin implements Listener {

    private final Set<UUID> selectionMode = new HashSet<>();
    private final Map<UUID, Map<Location, Integer>> selectedLights = new HashMap<>();
    private File configFile;
    private YamlConfiguration config;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("lightmode").setExecutor(this);
        configFile = new File(getDataFolder(), "lights_data.yml");
        if (!configFile.exists()) saveResource("lights_data.yml", false);
        config = YamlConfiguration.loadConfiguration(configFile);
        startParticleRunnable();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("lightmode")) {
            if (sender instanceof BlockCommandSender) {
                return handleCommandBlock(sender, args);
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players and command blocks can use this command!");
                return true;
            }
            return handlePlayerCommand((Player) sender, args);
        }
        return false;
    }

    private boolean handlePlayerCommand(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "Usage: /lightmode [save|toggle|on|off]");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "save":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /lightmode save [add|replace]");
                    return true;
                }
                saveSelectedLights(player, args[1]);
                selectionMode.remove(player.getUniqueId());
                break;
            case "toggle":
                toggleSelectionMode(player);
                break;
            case "on":
                toggleWorldLights(player.getWorld(), true);
                player.sendMessage(ChatColor.GREEN + "Turned on light for world " + player.getWorld().getName());
                break;
            case "off":
                toggleWorldLights(player.getWorld(), false);
                player.sendMessage(ChatColor.GREEN + "Turned off light for world " + player.getWorld().getName());
                break;
            default:
                player.sendMessage(ChatColor.RED + "Unknown command");
        }
        return true;
    }

    private boolean handleCommandBlock(CommandSender sender, String[] args) {
        BlockCommandSender cmdBlock = (BlockCommandSender) sender;
        World world = cmdBlock.getBlock().getWorld();

        if (args.length == 0 || (!args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off"))) {
            sender.sendMessage("Only on/off are allowed for command blocks");
            return true;
        }

        toggleWorldLights(world, args[0].equalsIgnoreCase("on"));
        return true;
    }

    private void toggleSelectionMode(Player player) {
        if (selectionMode.contains(player.getUniqueId())) {
            selectionMode.remove(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "Selection mode is off");
        } else {
            selectionMode.add(player.getUniqueId());
            player.sendMessage(ChatColor.GREEN + "Selection mode is on! Use Left Mouse Button");
        }
    }

    @EventHandler
    public void onBlockClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!selectionMode.contains(player.getUniqueId())) return;

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            handleLightSelection(player, event.getClickedBlock());
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (selectionMode.contains(player.getUniqueId())) {
            handleLightSelection(player, event.getBlock());
            event.setCancelled(true);
        }
    }

    private void handleLightSelection(Player player, Block block) {
        if (block.getType() == Material.LIGHT) {
            Levelled data = (Levelled) block.getBlockData();
            toggleLightSelection(player, block.getLocation(), data.getLevel());
        }
    }

    private void toggleLightSelection(Player player, Location loc, int level) {
        Map<Location, Integer> lights = selectedLights.computeIfAbsent(
            player.getUniqueId(), k -> new HashMap<>()
        );
        
        if (lights.containsKey(loc)) {
            lights.remove(loc);
            player.sendMessage(ChatColor.RED + "Deleted from selection");
        } else {
            lights.put(loc, level);
            player.sendMessage(ChatColor.GREEN + "Added to selection (Level: " + level + ")");
        }
    }

    private void saveSelectedLights(Player player, String mode) {
        try {
            Map<Location, Integer> lights = selectedLights.get(player.getUniqueId());
            if (lights == null || lights.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Nothing is selected!");
                return;
            }

            World world = player.getWorld();
            String worldKey = "worlds." + world.getName();
            List<String> newEntries = new ArrayList<>();

            for (Map.Entry<Location, Integer> entry : lights.entrySet()) {
                Location loc = entry.getKey();
                if (!loc.getWorld().equals(world)) continue;
                newEntries.add(locToString(loc) + ":" + entry.getValue());
            }

            List<String> existing = config.getStringList(worldKey);
            Set<String> updated = new HashSet<>();

            if (mode.equalsIgnoreCase("add")) {
                updated.addAll(existing);
                updated.addAll(newEntries);
            } else {
                updated.addAll(newEntries);
            }

            config.set(worldKey, new ArrayList<>(updated));
            config.save(configFile);
            lights.clear();
            player.sendMessage(ChatColor.GREEN + "Saved " + newEntries.size() + " lightblocks (" + mode + " mode)");
        } catch (IOException e) {
            player.sendMessage(ChatColor.RED + "Error at saving!");
            e.printStackTrace();
        }
    }

    private void toggleWorldLights(World world, boolean enable) {
        List<String> entries = config.getStringList("worlds." + world.getName());
        int changed = 0;

        for (String entry : entries) {
            String[] parts = entry.split(":");
            Location loc = stringToLoc(parts[0], world);
            if (loc == null) continue;

            int level = parts.length > 1 ? Integer.parseInt(parts[1]) : 15;
            Block block = loc.getBlock();

            if (enable) {
                block.setType(Material.LIGHT);
                Levelled data = (Levelled) block.getBlockData();
                data.setLevel(level);
                block.setBlockData(data);
            } else {
                block.setType(Material.AIR);
            }
            changed++;
        }
    }

    private void startParticleRunnable() {
        new BukkitRunnable() {
            @Override
            public void run() {
                selectionMode.forEach(uuid -> {
                    Player player = getServer().getPlayer(uuid);
                    if (player != null) showParticles(player);
                });
            }
        }.runTaskTimer(this, 0, 10);
    }

    private void showParticles(Player player) {
        Map<Location, Integer> lights = selectedLights.get(player.getUniqueId());
        if (lights == null) return;

        lights.keySet().forEach(loc -> {
            player.spawnParticle(Particle.VILLAGER_HAPPY, 
                loc.clone().add(0.5, 0.5, 0.5), 
                2, 0.2, 0.2, 0.2, 0);
        });
    }

    private String locToString(Location loc) {
        return loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    private Location stringToLoc(String str, World world) {
        String[] parts = str.split(";");
        try {
            return new Location(world,
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
        } catch (Exception e) {
            return null;
        }
    }
}
