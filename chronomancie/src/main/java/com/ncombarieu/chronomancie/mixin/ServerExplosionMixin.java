package com.ncombarieu.chronomancie.mixin;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.ncombarieu.chronomancie.monde.CicatricesExplosions;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;

/** Mémorise les blocs qu'une explosion s'apprête à détruire. */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Shadow
	@Final
	private Vec3 center;

	@Shadow
	@Final
	private @Nullable Entity source;

	@Inject(method = "interactWithBlocks", at = @At("HEAD"))
	private void chronomancie$memoriser(final List<BlockPos> targetBlocks, final CallbackInfo ci) {
		Component cause = this.source == null ? Component.literal("une explosion") : this.source.getType().getDescription();
		CicatricesExplosions.noter(this.level, this.center, cause, targetBlocks);
	}
}
