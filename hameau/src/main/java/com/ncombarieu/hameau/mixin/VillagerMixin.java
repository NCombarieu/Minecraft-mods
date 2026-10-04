package com.ncombarieu.hameau.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ncombarieu.hameau.Ames;

import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ServerLevelAccessor;

/** Un villageois apparu par /summon ou par un œuf n'est pas du village : il est marqué comme étranger. */
@Mixin(Villager.class)
public abstract class VillagerMixin {
	@Inject(method = "finalizeSpawn", at = @At("HEAD"))
	private void hameau$marquerEtranger(final ServerLevelAccessor level, final DifficultyInstance difficulty, final EntitySpawnReason reason, final SpawnGroupData groupData,
			final CallbackInfoReturnable<SpawnGroupData> cir) {
		if (reason == EntitySpawnReason.COMMAND || reason == EntitySpawnReason.SPAWN_ITEM_USE || reason == EntitySpawnReason.DISPENSER) {
			((Villager) (Object) this).addTag(Ames.TAG_ETRANGER);
		}
	}
}
