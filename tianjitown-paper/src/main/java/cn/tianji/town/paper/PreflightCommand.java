package cn.tianji.town.paper;

import cn.tianji.town.integrations.residence.ResidenceSmokeTest;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

final class PreflightCommand implements CommandExecutor {
    private final TianjiTownPlugin plugin;
    private final ResidenceSmokeTest residenceSmokeTest;

    PreflightCommand(TianjiTownPlugin plugin, ResidenceSmokeTest residenceSmokeTest) {
        this.plugin = plugin;
        this.residenceSmokeTest = residenceSmokeTest;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!TownAdminPermissions.has(sender::hasPermission,
                TownAdminPermissions.PREFLIGHT)) {
            sender.sendMessage("§c没有权限。");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("phase0")
                && args[1].equalsIgnoreCase("status")) {
            GateStatus status = plugin.gateStatus();
            sender.sendMessage("§6TianjiTown 安装门禁: §f" + status.state());
            status.details().forEach(detail -> sender.sendMessage("§7- " + detail));
            return true;
        }
        sender.sendMessage("§e/townadmin phase0 status");
        sender.sendMessage("§e/townadmin phase0 residence-smoke <world> <chunkX> <chunkZ> <memberUuid>");
        return true;
    }

    boolean validateResidenceSmoke(CommandSender sender, String[] args) {
        return smokeRequest(sender, args) != null;
    }

    void runResidenceSmoke(CommandSender sender, String[] args) {
        SmokeRequest request = smokeRequest(sender, args);
        if (request == null) {
            return;
        }
        ResidenceSmokeTest.Result result = residenceSmokeTest.run(request.world(), request.chunkX(),
                request.chunkZ(), request.memberId());
        sender.sendMessage(result.success() ? "§aResidence 冒烟测试通过。"
                : "§cResidence 冒烟测试失败: " + result.error());
        sender.sendMessage("§7步骤: " + String.join(" -> ", result.steps()));
    }

    private SmokeRequest smokeRequest(CommandSender sender, String[] args) {
        if (!TownAdminPermissions.has(sender::hasPermission,
                TownAdminPermissions.PREFLIGHT)) {
            sender.sendMessage("§c没有权限。");
            return null;
        }
        if (args.length != 6 || !args[0].equalsIgnoreCase("phase0")
                || !args[1].equalsIgnoreCase("residence-smoke")) {
            sender.sendMessage("§c用法: /townadmin phase0 residence-smoke <world> <chunkX> <chunkZ> <memberUuid>");
            return null;
        }
        if (plugin.gateStatus().state() != GateStatus.State.READY) {
            sender.sendMessage("§c运行时门禁未就绪；未执行 Residence 写操作。使用 /townadmin status 查看详情。");
            return null;
        }
        if (!plugin.getConfig().getBoolean("phase0.allow-residence-smoke", false)) {
            sender.sendMessage("§c配置未开启 phase0.allow-residence-smoke。");
            return null;
        }
        String allowedWorld = plugin.getConfig().getString("phase0.residence-smoke-world", "");
        if (!allowedWorld.equals(args[2])) {
            sender.sendMessage("§c只能在配置指定的预发世界运行；未执行任何写操作。");
            return null;
        }
        World world = plugin.getServer().getWorld(args[2]);
        if (world == null) {
            sender.sendMessage("§c世界未加载: " + args[2]);
            return null;
        }
        try {
            int chunkX = Integer.parseInt(args[3]);
            int chunkZ = Integer.parseInt(args[4]);
            UUID memberId = UUID.fromString(args[5]);
            return new SmokeRequest(world, chunkX, chunkZ, memberId);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            sender.sendMessage("§c参数无效: " + exception.getMessage());
            return null;
        }
    }

    private record SmokeRequest(World world, int chunkX, int chunkZ, UUID memberId) {
    }
}
