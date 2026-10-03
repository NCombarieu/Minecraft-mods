package com.ncombarieu.premiermod.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public final class ModCommands {
	private ModCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// /heal : soigne et nourrit le joueur (réservé aux ops)
			dispatcher.register(Commands.literal("heal")
					.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					.executes(ModCommands::heal));

			// /fusee : envoie le joueur dans les airs
			dispatcher.register(Commands.literal("fusee")
					.executes(ModCommands::fusee));

			// /de : lance un dé à 6 faces, ou /de <faces> pour choisir (entre 2 et 100)
			dispatcher.register(Commands.literal("de")
					.executes(context -> lancerDe(context, 6))
					.then(Commands.argument("faces", IntegerArgumentType.integer(2, 100))
							.executes(context -> lancerDe(context, IntegerArgumentType.getInteger(context, "faces")))));
		});
	}

	private static int heal(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(20.0F);
		context.getSource().sendSuccess(() -> Component.literal("Tu as été soigné !").withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int fusee(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		player.setDeltaMovement(player.getDeltaMovement().add(0, 3.0, 0));
		// Le mouvement d'un joueur est géré par son client : il faut lui envoyer la nouvelle vitesse
		player.connection.send(new ClientboundSetEntityMotionPacket(player));
		player.level().playSound(null, player.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0F, 1.0F);
		context.getSource().sendSuccess(() -> Component.literal("3… 2… 1… Décollage !").withStyle(ChatFormatting.AQUA), false);
		return 1;
	}

	private static int lancerDe(CommandContext<CommandSourceStack> context, int faces) {
		int resultat = context.getSource().getServer().overworld().getRandom().nextInt(faces) + 1;
		Component message = Component.literal(context.getSource().getTextName() + " lance un dé à " + faces + " faces : ")
				.withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(String.valueOf(resultat)).withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD));
		// Diffusé à tous les joueurs connectés
		context.getSource().getServer().getPlayerList().broadcastSystemMessage(message, false);
		return resultat;
	}
}
