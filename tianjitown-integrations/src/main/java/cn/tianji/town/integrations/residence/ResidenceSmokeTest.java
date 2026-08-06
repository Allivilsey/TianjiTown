package cn.tianji.town.integrations.residence;

import com.bekvon.bukkit.residence.api.ResidenceApi;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import org.bukkit.Location;
import org.bukkit.World;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 只允许在显式指定的预发世界运行。测试先做碰撞判断，测试领地始终在 finally 中清理。
 */
public final class ResidenceSmokeTest {
    public Result run(World world, int chunkX, int chunkZ, UUID memberId) {
        List<String> steps = new ArrayList<>();
        ResidenceManager manager;
        try {
            manager = (ResidenceManager) ResidenceApi.getResidenceManager();
        } catch (RuntimeException exception) {
            return Result.failure("无法取得 ResidenceManager: " + exception.getMessage(), steps);
        }

        int minX = Math.multiplyExact(chunkX, 16);
        int minZ = Math.multiplyExact(chunkZ, 16);
        Location low = new Location(world, minX, world.getMinHeight(), minZ);
        Location high = new Location(world, minX + 15, world.getMaxHeight() - 1, minZ + 15);
        CuboidArea area = new CuboidArea(low, high);
        ClaimedResidence collision = manager.collidesWithResidence(area);
        if (collision != null) {
            return Result.failure("目标区块与现有 Residence 冲突: " + collision.getName(), steps);
        }
        steps.add("collision-empty");

        String name = ("tt_phase0_" + Long.toString(Instant.now().toEpochMilli(), 36))
                .toLowerCase(Locale.ROOT);
        try {
            if (!manager.addResidence(name, "TianjiTownSystem", low, high)) {
                return Result.failure("Residence API 拒绝创建测试领地", steps);
            }
            steps.add("created");
            ClaimedResidence created = manager.getByName(name);
            if (created == null || manager.getByLoc(low) == null || manager.getByLoc(high) == null) {
                return Result.failure("创建后按名称/边界位置读取失败", steps);
            }
            steps.add("read-bounds");
            if (!created.isServerLand()) {
                return Result.failure("测试领地所有者不是受控服务端账户: " + created.getOwner(), steps);
            }
            steps.add("server-owner");

            boolean flagSet = created.getPermissions().setPlayerFlag(memberId, "build",
                    FlagPermissions.FlagState.TRUE);
            Boolean stored = created.getPermissions().getPlayerFlags(memberId).get("build");
            if (!flagSet || !Boolean.TRUE.equals(stored)) {
                return Result.failure("成员 build 权限写入或读取失败", steps);
            }
            steps.add("member-permission");

            manager.removeResidence(name);
            if (manager.getByName(name) != null) {
                return Result.failure("删除后测试领地仍存在", steps);
            }
            steps.add("deleted");

            if (!manager.addResidence(name, "TianjiTownSystem", low, high)
                    || manager.getByName(name) == null) {
                return Result.failure("删除后重建失败", steps);
            }
            steps.add("rebuilt");
            ClaimedResidence rebuiltCollision = manager.collidesWithResidence(area);
            if (rebuiltCollision == null || !name.equalsIgnoreCase(rebuiltCollision.getName())) {
                return Result.failure("重建后的区块碰撞判断失败", steps);
            }
            steps.add("collision-detected");
            return Result.success(steps);
        } catch (RuntimeException exception) {
            return Result.failure(exception.getClass().getSimpleName() + ": " + exception.getMessage(), steps);
        } finally {
            if (manager.getByName(name) != null) {
                manager.removeResidence(name);
                steps.add("cleanup");
            }
        }
    }

    public record Result(boolean success, String error, List<String> steps) {
        static Result success(List<String> steps) {
            return new Result(true, null, Collections.unmodifiableList(steps));
        }

        static Result failure(String error, List<String> steps) {
            return new Result(false, error, Collections.unmodifiableList(steps));
        }
    }
}
