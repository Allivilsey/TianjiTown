package cn.tianji.town.paper;

import cn.tianji.town.integrations.residence.ResidenceSmokeTest;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

final class PhaseZeroCommand implements CommandExecutor {
    private static final String CONFIRM = "--confirm-preproduction";
    private final TianjiTownPlugin plugin;
    private final ResidenceSmokeTest residenceSmokeTest;

    PhaseZeroCommand(TianjiTownPlugin plugin, ResidenceSmokeTest residenceSmokeTest) {
        this.plugin = plugin;
        this.residenceSmokeTest = residenceSmokeTest;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("tianjitown.admin.phase0")) {
            sender.sendMessage("§c没有权限。");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("phase0")
                && args[1].equalsIgnoreCase("status")) {
            GateStatus status = plugin.gateStatus();
            sender.sendMessage("§6TianjiTown 第0阶段门禁: §f" + status.state());
            status.details().forEach(detail -> sender.sendMessage("§7- " + detail));
            return true;
        }
        if (args.length == 8 && args[0].equalsIgnoreCase("phase0")
                && args[1].equalsIgnoreCase("residence-smoke")) {
            return runResidenceSmoke(sender, args);
        }
        sender.sendMessage("§e/townadmin phase0 status");
        sender.sendMessage("§e/townadmin phase0 residence-smoke <world> <chunkX> <chunkZ> <memberUuid> --confirm-empty-chunk --confirm-preproduction");
        return true;
    }

    private boolean runResidenceSmoke(CommandSender sender, String[] args) {
        if (!plugin.getConfig().getBoolean("phase0.allow-residence-smoke", false)) {
            sender.sendMessage("§c配置未开启 phase0.allow-residence-smoke。");
            return true;
        }
        if (!CONFIRM.equals(args[7])) {
            sender.sendMessage("§c缺少预发确认参数；未执行任何写操作。");
            return true;
        }
        String allowedWorld = plugin.getConfig().getString("phase0.residence-smoke-world", "");
        if (!allowedWorld.equals(args[2])) {
            sender.sendMessage("§c只能在配置指定的预发世界运行；未执行任何写操作。");
            return true;
        }
        World world = plugin.getServer().getWorld(args[2]);
        if (world == null) {
            sender.sendMessage("§c世界未加载: " + args[2]);
            return true;
        }
        try {
            int chunkX = Integer.parseInt(args[3]);
            int chunkZ = Integer.parseInt(args[4]);
            UUID memberId = UUID.fromString(args[5]);
            if (!args[6].equals("--confirm-empty-chunk")) {
                sender.sendMessage("§c缺少 --confirm-empty-chunk；未执行任何写操作。");
                return true;
            }
            ResidenceSmokeTest.Result result = residenceSmokeTest.run(world, chunkX, chunkZ, memberId);
            sender.sendMessage(result.success() ? "§aResidence 冒烟测试通过。" : "§cResidence 冒烟测试失败: " + result.error());
            sender.sendMessage("§7步骤: " + String.join(" -> ", result.steps()));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            sender.sendMessage("§c参数无效: " + exception.getMessage());
        }
        return true;
    }
}
