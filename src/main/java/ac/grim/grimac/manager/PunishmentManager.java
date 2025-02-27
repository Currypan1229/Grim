package ac.grim.grimac.manager;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.config.ConfigReloadable;
import ac.grim.grimac.api.events.CommandExecuteEvent;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.events.packets.ProxyAlertMessenger;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import io.github.retrooper.packetevents.util.folia.FoliaScheduler;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

public class PunishmentManager implements ConfigReloadable {
    GrimPlayer player;
    List<PunishGroup> groups = new ArrayList<>();
    String experimentalSymbol = "*";
    private String alertString;
    private boolean testMode;
    private boolean printToConsole;
    private String proxyAlertString = "";

    public PunishmentManager(GrimPlayer player) {
        this.player = player;
    }

    @Override
    public void reload(ConfigManager config) {
        List<String> punish = config.getStringListElse("Punishments", new ArrayList<>());
        experimentalSymbol = config.getStringElse("experimental-symbol", "*");
        alertString = config.getStringElse("alerts-format", "%prefix% &f%player% &bfailed &f%check_name% &f(x&c%vl%&f) &7%verbose%");
        testMode = config.getBooleanElse("test-mode", false);
        printToConsole = config.getBooleanElse("verbose.print-to-console", false);
        proxyAlertString = config.getStringElse("alerts-format-proxy", "%prefix% &f[&cproxy&f] &f%player% &bfailed &f%check_name% &f(x&c%vl%&f) &7%verbose%");
        try {
            groups.clear();

            // To support reloading
            for (AbstractCheck check : player.checkManager.allChecks.values()) {
                check.setEnabled(false);
            }

            for (Object s : punish) {
                LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>) s;

                List<String> checks = (List<String>) map.getOrDefault("checks", new ArrayList<>());
                List<String> commands = (List<String>) map.getOrDefault("commands", new ArrayList<>());
                int removeViolationsAfter = (int) map.getOrDefault("remove-violations-after", 300);

                List<ParsedCommand> parsed = new ArrayList<>();
                List<AbstractCheck> checksList = new ArrayList<>();
                List<AbstractCheck> excluded = new ArrayList<>();
                for (String command : checks) {
                    command = command.toLowerCase(Locale.ROOT);
                    boolean exclude = false;
                    if (command.startsWith("!")) {
                        exclude = true;
                        command = command.substring(1);
                    }
                    for (AbstractCheck check : player.checkManager.allChecks.values()) { // o(n) * o(n)?
                        if (check.getCheckName() != null &&
                                (check.getCheckName().toLowerCase(Locale.ROOT).contains(command)
                                        || check.getAlternativeName().toLowerCase(Locale.ROOT).contains(command))) { // Some checks have equivalent names like AntiKB and AntiKnockback
                            if (exclude) {
                                excluded.add(check);
                            } else {
                                checksList.add(check);
                                check.setEnabled(true);
                            }
                        }
                    }
                    for (AbstractCheck check : excluded) checksList.remove(check);
                }

                for (String command : commands) {
                    String firstNum = command.substring(0, command.indexOf(":"));
                    String secondNum = command.substring(command.indexOf(":"), command.indexOf(" "));

                    int threshold = Integer.parseInt(firstNum);
                    int interval = Integer.parseInt(secondNum.substring(1));
                    String commandString = command.substring(command.indexOf(" ") + 1);

                    parsed.add(new ParsedCommand(threshold, interval, commandString));
                }

                groups.add(new PunishGroup(checksList, parsed, removeViolationsAfter));
            }
        } catch (Exception e) {
            LogUtil.error("Error while loading punishments.yml! This is likely your fault!");
            e.printStackTrace();
        }
    }

    private String replaceAlertPlaceholders(String original, int vl, PunishGroup group, Check check, String alertString, String verbose) {

        original = original
                .replace("[alert]", alertString)
                .replace("[proxy]", alertString)
                .replace("%check_name%", check.getDisplayName())
                .replace("%experimental%", check.isExperimental() ? experimentalSymbol : "")
                .replace("%vl%", Integer.toString(vl))
                .replace("%verbose%", verbose)
                .replace("%description%", check.getDescription());

        original = MessageUtil.replacePlaceholders(player, original);

        return original;
    }

    public boolean handleAlert(GrimPlayer player, String verbose, Check check) {
        boolean sentDebug = false;

        // Check commands
        for (PunishGroup group : groups) {
            if (group.getChecks().contains(check)) {
                final int vl = getViolations(group, check);
                final int violationCount = group.getViolations().size();
                for (ParsedCommand command : group.getCommands()) {
                    String cmd = replaceAlertPlaceholders(command.getCommand(), vl, group, check, alertString, verbose);

                    // Verbose that prints all flags
                    if (!GrimAPI.INSTANCE.getAlertManager().getEnabledVerbose().isEmpty() && command.command.equals("[alert]")) {
                        sentDebug = true;
                        for (Player bukkitPlayer : GrimAPI.INSTANCE.getAlertManager().getEnabledVerbose()) {
                            MessageUtil.sendMessage(bukkitPlayer, MessageUtil.miniMessage(cmd));
                        }
                        if (printToConsole) {
                            LogUtil.console(MessageUtil.miniMessage(cmd)); // Print verbose to console
                        }
                    }

                    if (violationCount >= command.getThreshold()) {
                        // 0 means execute once
                        // Any other number means execute every X interval
                        boolean inInterval = command.getInterval() == 0 ? (command.executeCount == 0) : (violationCount % command.getInterval() == 0);
                        if (inInterval) {
                            CommandExecuteEvent executeEvent = new CommandExecuteEvent(player, check, cmd);
                            Bukkit.getPluginManager().callEvent(executeEvent);
                            if (executeEvent.isCancelled()) continue;

                            if (command.command.equals("[webhook]")) {
                                GrimAPI.INSTANCE.getDiscordManager().sendAlert(player, verbose, check.getDisplayName(), vl);
                            } else if (command.command.equals("[proxy]")) {
                                ProxyAlertMessenger.sendPluginMessage(replaceAlertPlaceholders(command.getCommand(), vl, group, check, proxyAlertString, verbose));
                            } else {
                                if (command.command.equals("[alert]")) {
                                    sentDebug = true;
                                    if (testMode) { // secret test mode
                                        player.user.sendMessage(MessageUtil.miniMessage(cmd));
                                        continue;
                                    }
                                    cmd = "grim sendalert " + cmd; // Not test mode, we can add the command prefix
                                }

                                String finalCmd = cmd;
                                FoliaScheduler.getGlobalRegionScheduler().run(GrimAPI.INSTANCE.getPlugin(), (dummy) ->
                                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd));
                            }
                        }

                        command.setExecuteCount(command.getExecuteCount() + 1);
                    }
                }
            }
        }
        return sentDebug;
    }

    public void handleViolation(Check check) {
        for (PunishGroup group : groups) {
            if (group.getChecks().contains(check)) {
                long currentTime = System.currentTimeMillis();

                group.violations.put(currentTime, check);
                // Remove violations older than the defined time in the config
                group.violations.entrySet().removeIf(time -> currentTime - time.getKey() > group.removeViolationsAfter);
            }
        }
    }

    private int getViolations(PunishGroup group, Check check) {
        int vl = 0;
        for (Check value : group.violations.values()) {
            if (value == check) vl++;
        }
        return vl;
    }

}

class PunishGroup {
    @Getter
    List<AbstractCheck> checks;
    @Getter
    List<ParsedCommand> commands;
    @Getter
    public Map<Long, Check> violations = new HashMap<>();
    @Getter
    int removeViolationsAfter;

    public PunishGroup(List<AbstractCheck> checks, List<ParsedCommand> commands, int removeViolationsAfter) {
        this.checks = checks;
        this.commands = commands;
        this.removeViolationsAfter = removeViolationsAfter * 1000;
    }
}

class ParsedCommand {
    @Getter
    int threshold;
    @Getter
    int interval;
    @Getter
    @Setter
    int executeCount;
    @Getter
    String command;

    public ParsedCommand(int threshold, int interval, String command) {
        this.threshold = threshold;
        this.interval = interval;
        this.command = command;
    }
}
