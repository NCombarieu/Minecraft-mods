package com.ncombarieu.chronomancie.temps;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** L'état d'un joueur à un tick donné. */
public record Instantane(
		ResourceKey<Level> dimension,
		Vec3 position,
		float yRot,
		float xRot,
		float sante,
		boolean enFeu,
		Pose pose,
		ItemStack mainDroite,
		boolean coup) {
}
