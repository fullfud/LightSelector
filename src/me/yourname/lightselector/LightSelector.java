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
        configFile = new File(getDataFolder(), "selected_lights.yml");
        if (!configFile.exists()) saveResource("selected_lights.yml", false);
        config = YamlConfiguration.loadConfiguration(configFile);
        startParticleRunnable();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        Player player = (Player) sender;
        if (cmd.getName().equalsIgnoreCase("lightmode")) {
            if (args.length == 0) {
                player.sendMessage(ChatColor.RED + "Usage: /lightmode [save|toggle|on|off]");
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "save":
                    saveSelectedLights(player);
                    selectionMode.remove(player.getUniqueId());
                    break;
                case "toggle":
                    if (selectionMode.contains(player.getUniqueId())) {
                        selectionMode.remove(player.getUniqueId());
                        player.sendMessage(ChatColor.RED + "Selection mode disabled!");
                    } else {
                        selectionMode.add(player.getUniqueId());
                        player.sendMessage(ChatColor.GREEN + "Selection mode enabled! Left-click lights to select");
                    }
                    break;
                case "on":
                    toggleLights(player, true);
                    break;
                case "off":
                    toggleLights(player, false);
                    break;
                default:
                    player.sendMessage(ChatColor.RED + "Invalid subcommand!");
            }
            return true;
        }
        return false;
    }

    @EventHandler
    public void onBlockClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!selectionMode.contains(player.getUniqueId())) return;

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            if (block.getType() == Material.LIGHT) {
                Levelled data = (Levelled) block.getBlockData();
                toggleLightSelection(player, block.getLocation(), data.getLevel());
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (selectionMode.contains(player.getUniqueId()) && 
            event.getBlock().getType() == Material.LIGHT) {
            event.setCancelled(true);
            toggleLightSelection(player, event.getBlock().getLocation(), 
                ((Levelled) event.getBlock().getBlockData()).getLevel());
        }
    }

    private void toggleLightSelection(Player player, Location loc, int level) {
        Map<Location, Integer> lights = selectedLights.computeIfAbsent(
            player.getUniqueId(), k -> new HashMap<>()
        );
        
        if (lights.containsKey(loc)) {
            lights.remove(loc);
            player.sendMessage(ChatColor.RED + "Light deselected");
        } else {
            lights.put(loc, level);
            player.sendMessage(ChatColor.GREEN + "Light selected (Level: " + level + ")");
        }
    }

    private void saveSelectedLights(Player player) {
        try {
            Map<Location, Integer> lights = selectedLights.get(player.getUniqueId());
            if (lights == null || lights.isEmpty()) {
                player.sendMessage(ChatColor.RED + "No lights selected!");
                return;
            }

            List<String> entries = new ArrayList<>();
            for (Map.Entry<Location, Integer> entry : lights.entrySet()) {
                entries.add(locToString(entry.getKey()) + ":" + entry.getValue());
            }
            
            config.set("lights." + player.getUniqueId(), entries);
            config.save(configFile);
            lights.clear();
            player.sendMessage(ChatColor.GREEN + "Saved " + entries.size() + " lights!");
        } catch (IOException e) {
            player.sendMessage(ChatColor.RED + "Error saving lights!");
            e.printStackTrace();
        }
    }

    private void toggleLights(Player player, boolean enable) {
        List<String> entries = config.getStringList("lights." + player.getUniqueId());
        if (entries.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No saved lights found!");
            return;
        }

        int changed = 0;
        for (String entry : entries) {
            String[] parts = entry.split(":");
            Location loc = stringToLoc(parts[0], player.getWorld());
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
        player.sendMessage(ChatColor.GREEN + "Toggled " + changed + " lights!");
    }

    private void startParticleRunnable() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : selectionMode) {
                    Player player = getServer().getPlayer(uuid);
                    if (player != null) showSelectedParticles(player);
                }
            }
        }.runTaskTimer(this, 0, 10);
    }

    private void showSelectedParticles(Player player) {
        Map<Location, Integer> lights = selectedLights.get(player.getUniqueId());
        if (lights != null) {
            for (Location loc : lights.keySet()) {
                player.spawnParticle(Particle.VILLAGER_HAPPY, 
                    loc.clone().add(0.5, 0.5, 0.5), 
                    2, 0.2, 0.2, 0.2, 0);
            }
        }
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