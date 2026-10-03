package com.ncombarieu.chronomancie.item;

import com.ncombarieu.chronomancie.fx.Effets;
import com.ncombarieu.chronomancie.pouvoir.Echo;
import com.ncombarieu.chronomancie.pouvoir.Restauration;
import com.ncombarieu.chronomancie.pouvoir.Retour;
import com.ncombarieu.chronomancie.pouvoir.Stase;

import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;

/** Clic droit avec un artefact. */
public final class UtilisationArtefacts {
	private UtilisationArtefacts() {
	}

	public static void register() {
		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack stack = player.getItemInHand(hand);
			Artefact artefact = Artefacts.identifier(stack);
			if (artefact == null || !artefact.estOutil()) {
				return InteractionResult.PASS;
			}
			if (!(player instanceof ServerPlayer joueur)) {
				return InteractionResult.SUCCESS;
			}
			return utiliser(joueur, hand, stack, artefact);
		});
	}

	private static InteractionResult utiliser(final ServerPlayer joueur, final InteractionHand hand, final ItemStack stack, final Artefact artefact) {
		Artefacts.actualiser(stack, artefact);
		// Accroupi avec un fragment dans l'autre main : recharge
		ItemStack autreMain = joueur.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		if (joueur.isShiftKeyDown() && Artefacts.est(autreMain, Artefact.FRAGMENT)) {
			return recharger(joueur, stack, autreMain, artefact);
		}
		if (joueur.getCooldowns().isOnCooldown(stack)) {
			return InteractionResult.FAIL;
		}
		if (Artefacts.chargesRestantes(stack) <= 0) {
			joueur.sendOverlayMessage(Component.literal(artefact.nom + " est épuisé. Accroupis-toi avec un fragment temporel dans l'autre main pour le recharger.")
					.withStyle(ChatFormatting.RED));
			Effets.son(joueur.level(), joueur.position(), SoundEvents.AMETHYST_BLOCK_HIT, 1.0F, 0.5F);
			return InteractionResult.FAIL;
		}

		boolean reussi = switch (artefact) {
			case SABLIER -> Retour.demarrer(joueur);
			case MIROIR -> Echo.demarrer(joueur);
			case STASE -> Stase.demarrer(joueur);
			case CHRONOSCOPE -> Restauration.utiliser(joueur, joueur.isShiftKeyDown());
			case FRAGMENT -> false;
		};
		if (!reussi) {
			return InteractionResult.FAIL;
		}
		if (!joueur.isCreative()) {
			Artefacts.consommerCharge(stack);
		}
		joueur.getCooldowns().addCooldown(stack, artefact.rechargeTicks());
		joueur.swing(hand, SwingAnimation.DEFAULT, true);
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult recharger(final ServerPlayer joueur, final ItemStack stack, final ItemStack fragment, final Artefact artefact) {
		if (stack.getDamageValue() == 0) {
			joueur.sendOverlayMessage(Component.literal(artefact.nom + " est déjà plein.").withStyle(ChatFormatting.GRAY));
			return InteractionResult.FAIL;
		}
		int gain = Artefacts.recharger(stack);
		if (!joueur.isCreative()) {
			fragment.shrink(1);
		}
		joueur.sendOverlayMessage(Component.literal("+" + gain + " charges  (" + Artefacts.chargesRestantes(stack) + "/" + stack.getMaxDamage() + ")")
				.withStyle(ChatFormatting.LIGHT_PURPLE));
		Effets.son(joueur.level(), joueur.position(), SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.0F, 1.4F);
		Effets.particules(joueur.level(), ParticleTypes.REVERSE_PORTAL, joueur.position().add(0, 1, 0), 30, 0.3, 0.05);
		return InteractionResult.SUCCESS;
	}
}
