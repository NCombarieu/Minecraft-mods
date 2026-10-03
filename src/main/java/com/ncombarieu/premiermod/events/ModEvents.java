package com.ncombarieu.premiermod.events;

import com.ncombarieu.premiermod.PremierMod;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class ModEvents {
	// 1 chance sur 50 d'obtenir un diamant en cassant de la terre
	private static final int CHANCE_DIAMANT = 50;

	private ModEvents() {
	}

	public static void register() {
		// Message de bienvenue à chaque connexion d'un joueur
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			player.sendSystemMessage(Component.literal("Bienvenue sur le serveur, " + player.getName().getString() + " !")
					.withStyle(ChatFormatting.GOLD));
			PremierMod.LOGGER.info("{} a rejoint le serveur", player.getName().getString());
		});

		// Terre chanceuse : casser de la terre peut faire apparaître un diamant
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level.isClientSide() || !state.is(Blocks.DIRT)) {
				return;
			}
			if (level.getRandom().nextInt(CHANCE_DIAMANT) == 0) {
				Block.popResource(level, pos, new ItemStack(Items.DIAMOND));
				level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1.0F, 1.0F);
				player.sendSystemMessage(Component.literal("Terre chanceuse ! Un diamant !").withStyle(ChatFormatting.AQUA));
			}
		});

		// Mouton arc-en-ciel : clic droit main vide sur un mouton = couleur au hasard
		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (!(entity instanceof Sheep sheep) || !player.getItemInHand(hand).isEmpty()) {
				return InteractionResult.PASS;
			}
			if (!level.isClientSide()) {
				DyeColor[] couleurs = DyeColor.values();
				sheep.setColor(couleurs[level.getRandom().nextInt(couleurs.length)]);
				level.playSound(null, sheep, SoundEvents.NOTE_BLOCK_BELL, SoundSource.NEUTRAL, 1.0F, 1.5F);
			}
			return InteractionResult.SUCCESS;
		});
	}
}
