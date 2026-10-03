package com.ncombarieu.premiermod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PremierMod implements ModInitializer {
	public static final String MOD_ID = "premiermod";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Message de bienvenue à chaque connexion d'un joueur
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			player.sendSystemMessage(Component.literal("Bienvenue sur le serveur, " + player.getName().getString() + " !")
					.withStyle(ChatFormatting.GOLD));
			LOGGER.info("{} a rejoint le serveur", player.getName().getString());
		});

		// /heal : soigne et nourrit le joueur (réservé aux ops)
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("heal")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							player.setHealth(player.getMaxHealth());
							player.getFoodData().setFoodLevel(20);
							player.getFoodData().setSaturation(20.0F);
							context.getSource().sendSuccess(() -> Component.literal("Tu as été soigné !")
									.withStyle(ChatFormatting.GREEN), false);
							return 1;
						})));

		LOGGER.info("Premier Mod chargé !");
	}
}
