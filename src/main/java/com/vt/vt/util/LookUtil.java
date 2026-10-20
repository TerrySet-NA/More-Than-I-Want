package com.vt.vt.util;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class LookUtil {

    public enum LookType {
        DIR_6,  // 標準 6 向 (原版上下左右前後)
        DIR_26  // 26 向 (包含面、邊緣、角落)
    }

    /**
     * 獲取玩家視角的網格化方向向量
     */
    public static Vec3 getGridDirection(Player player, LookType type) {
        return getGridDirection(player.getLookAngle(), type);
    }

    /**
     * 計算任意視線方向的網格化向量
     */
    public static Vec3 getGridDirection(Vec3 look, LookType type) {
        if (look == null) {
            return new Vec3(0, 1, 0);
        }

        if (type == LookType.DIR_26) {
            double maxLook = Math.max(Math.abs(look.x), Math.max(Math.abs(look.y), Math.abs(look.z)));
            if (maxLook < 1.0E-5) {
                return new Vec3(0, 1, 0);
            }

            double nx = Math.round(look.x / maxLook);
            double ny = Math.round(look.y / maxLook);
            double nz = Math.round(look.z / maxLook);

            return new Vec3(nx, ny, nz);
        } else {
            Direction dir = toDirection(look);
            return toVec3(dir);
        }
    }

    public static Vec3 toNormal(Vec3 gridDir) {
        return gridDir.normalize();
    }

    public static Direction toDirection(Vec3 dir) {
        return Direction.getNearest(dir.x, dir.y, dir.z);
    }

    public static Vec3 toVec3(Direction dir) {
        return new Vec3(dir.getStepX(), dir.getStepY(), dir.getStepZ());
    }
}