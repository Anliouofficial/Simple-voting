package com.example.votePlugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.UUID;

public class VotePlugin extends JavaPlugin implements Listener {

    private final Map<UUID, VoteSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, VoteType> voteTypes = new HashMap<>();

    private FileConfiguration config;
    private int cooldownTime;
    private int voteDuration;
    private String cooldownMessage;
    private String voteStartedMessage;
    private String voteResultMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPlugin();
        getServer().getPluginManager().registerEvents(this, this);

        getServer().getCommandMap().register("voteplugin", new Command("vote") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return onCommand(sender, this, label, args);
            }

            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                return Collections.emptyList();
            }
        });
    }

    private void reloadPlugin() {
        reloadConfig();
        loadConfig();
    }

    private void loadConfig() {
        config = getConfig();

        cooldownTime = config.getInt("cooldown-time", 300) * 1000;
        voteDuration = config.getInt("vote-duration", 30);
        cooldownMessage = parseColor(config.getString("messages.cooldown", "&c请等待 %d 秒后再发起新的投票"));
        voteStartedMessage = parseColor(config.getString("messages.vote-started", "&a新投票已发起！类型：%s 理由：%s"));
        voteResultMessage = parseColor(config.getString("messages.vote-result", "&e投票结果：%d 同意，%d 反对。%s"));

        loadVoteTypes();
    }

    private void loadVoteTypes() {
        ConfigurationSection typesSection = config.getConfigurationSection("vote-types");
        voteTypes.clear();

        if (typesSection != null) {
            for (String key : typesSection.getKeys(false)) {
                ConfigurationSection typeSection = typesSection.getConfigurationSection(key);
                if (typeSection != null) {
                    VoteType type = new VoteType(
                            typeSection.getString("command"),
                            typeSection.getDouble("pass-percent", 50.0),
                            typeSection.getString("permission"),
                            parseColor(typeSection.getString("description", ""))
                    );
                    voteTypes.put(key.toLowerCase(), type);
                }
            }
        }
    }

    private String parseColor(String text) {
        return text != null ? ChatColor.translateAlternateColorCodes('&', text) : "";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家可以使用这个命令！");
            return true;
        }

        Player player = (Player) sender;
        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        UUID playerId = player.getUniqueId();
        if (checkCooldown(player, playerId)) return true;

        String voteKey = args[0].toLowerCase();
        if (!voteTypes.containsKey(voteKey)) {
            player.sendMessage(ChatColor.RED + "无效的投票类型，可用类型: " + String.join(", ", voteTypes.keySet()));
            return true;
        }

        return processVote(player, playerId, voteKey, args);
    }

    private void sendUsage(Player player) {
        player.sendMessage(ChatColor.RED + "用法: /vote <类型> [理由]");
        player.sendMessage(ChatColor.GOLD + "可用类型: " + String.join(", ", voteTypes.keySet()));
    }

    private boolean checkCooldown(Player player, UUID playerId) {
        if (cooldowns.containsKey(playerId)) {
            long remaining = (cooldowns.get(playerId) + cooldownTime - System.currentTimeMillis()) / 1000;
            if (remaining > 0) {
                player.sendMessage(String.format(cooldownMessage, remaining));
                return true;
            }
        }
        return false;
    }

    private boolean processVote(Player player, UUID playerId, String voteKey, String[] args) {
        VoteType type = voteTypes.get(voteKey);
        if (type.getPermission() != null && !player.hasPermission(type.getPermission())) {
            player.sendMessage(ChatColor.RED + "你没有权限发起此类投票");
            return true;
        }

        String reason = args.length > 1 ?
                String.join(" ", Arrays.copyOfRange(args, 1, args.length)) :
                type.getDescription();
        startVote(player, voteKey, reason, type);
        return true;
    }

    private void startVote(Player initiator, String typeKey, String reason, VoteType type) {
        UUID initiatorId = initiator.getUniqueId();
        activeSessions.put(initiatorId, new VoteSession(initiator, type));
        cooldowns.put(initiatorId, System.currentTimeMillis());

        broadcastVote(initiator, typeKey, reason);
        scheduleVoteExpiration(initiatorId);
    }

    private void broadcastVote(Player initiator, String typeKey, String reason) {
        String message = String.format(voteStartedMessage, typeKey, reason);
        sendVoteButtons(initiator, message);
    }

    private void sendVoteButtons(Player initiator, String message) {
        World world = initiator.getWorld();
        String acceptCmd = "/voteaccept " + initiator.getUniqueId();
        String rejectCmd = "/votereject " + initiator.getUniqueId();

        Consumer<Player> sendButtons = player -> {
            player.sendMessage(message);
            TextComponent accept = createButton("[同意]", ChatColor.GREEN, acceptCmd);
            TextComponent reject = createButton("[拒绝]", ChatColor.RED, rejectCmd);
            player.spigot().sendMessage(accept, new TextComponent(" "), reject);
        };

        world.getPlayers().forEach(sendButtons);
    }

    private TextComponent createButton(String text, ChatColor color, String command) {
        TextComponent button = new TextComponent(text);
        button.setColor(color.asBungee());
        button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        return button;
    }

    private void scheduleVoteExpiration(UUID initiatorId) {
        new BukkitRunnable() {
            @Override
            public void run() {
                VoteSession session = activeSessions.remove(initiatorId);
                if (session != null) {
                    announceResults(session);
                }
            }
        }.runTaskLater(this, voteDuration * 20L);
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String cmd = event.getMessage().toLowerCase();
        if (cmd.startsWith("/voteaccept ") || cmd.startsWith("/votereject ")) {
            event.setCancelled(true);
            handleVoteResponse(event.getPlayer(), cmd.split(" "));
        }
    }

    private void handleVoteResponse(Player voter, String[] parts) {
        if (parts.length < 2) return;

        try {
            UUID initiatorId = UUID.fromString(parts[1]);
            VoteSession session = activeSessions.get(initiatorId);

            if (session == null || !voter.getWorld().equals(session.getWorld())) {
                voter.sendMessage(ChatColor.RED + "投票已过期！");
                return;
            }

            boolean isAgree = parts[0].equalsIgnoreCase("/voteaccept");
            if (session.addVote(voter.getUniqueId(), isAgree)) {
                // 添加点击音效
                voter.playSound(voter.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                voter.sendMessage(ChatColor.GREEN + "投票已记录！");
            } else {
                voter.sendMessage(ChatColor.RED + "你已经投过票了！");
            }
        } catch (IllegalArgumentException e) {
            voter.sendMessage(ChatColor.RED + "无效的投票指令！");
        }
    }

    private void announceResults(VoteSession session) {
        World world = session.getWorld();
        int agree = session.getAgreeCount();
        int disagree = session.getDisagreeCount();
        int total = agree + disagree;

        double percent = total == 0 ? 0 : (agree * 100.0) / total;
        boolean passed = percent >= session.getType().getPassPercent();
        String result = passed ? ChatColor.GREEN + "投票通过！" : ChatColor.RED + "投票未通过！";

        String message = String.format(voteResultMessage, agree, disagree, result);
        world.getPlayers().forEach(p -> p.sendMessage(message));

        if (passed) {
            executeVoteCommand(session);
        }
    }

    private void executeVoteCommand(VoteSession session) {
        String rawCommand = session.getType().getCommand()
                .replace("%player%", session.getInitiator().getName())
                .replace("%world%", session.getWorld().getName());

        if (rawCommand.startsWith("console:")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rawCommand.substring(8).trim());
        } else {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rawCommand);
        }
    }

    private static class VoteSession {
        private final Player initiator;
        private final World world;
        private final VoteType type;
        private final Set<UUID> voted = new HashSet<>();
        private int agree = 0;
        private int disagree = 0;

        public VoteSession(Player initiator, VoteType type) {
            this.initiator = initiator;
            this.world = initiator.getWorld();
            this.type = type;
        }

        public boolean addVote(UUID voter, boolean isAgree) {
            if (voted.add(voter)) {
                if (isAgree) agree++;
                else disagree++;
                return true;
            }
            return false;
        }

        public int getAgreeCount() { return agree; }
        public int getDisagreeCount() { return disagree; }
        public World getWorld() { return world; }
        public VoteType getType() { return type; }
        public Player getInitiator() { return initiator; }
    }

    private static class VoteType {
        private final String command;
        private final double passPercent;
        private final String permission;
        private final String description;

        public VoteType(String command, double passPercent, String permission, String description) {
            this.command = command;
            this.passPercent = passPercent;
            this.permission = permission;
            this.description = description;
        }

        public String getCommand() { return command; }
        public double getPassPercent() { return passPercent; }
        public String getPermission() { return permission; }
        public String getDescription() { return description; }
    }
}