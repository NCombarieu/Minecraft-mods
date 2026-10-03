package com.ncombarieu.chronomancie.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.ncombarieu.chronomancie.Chronomancie;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.UseCooldown;

/** Fabrique et reconnaît les artefacts. */
public final class Artefacts {
	/** Clé dans les données cachées (custom_data) de l'objet : {chronomancie:"sablier"}. */
	public static final String CLE = Chronomancie.MOD_ID;

	private Artefacts() {
	}

	public static ItemStack creer(final Artefact artefact) {
		return creer(artefact, 1);
	}

	public static ItemStack creer(final Artefact artefact, final int quantite) {
		ItemStack stack = new ItemStack(artefact.base, quantite);
		CompoundTag tag = new CompoundTag();
		tag.putString(CLE, artefact.id);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal(artefact.nom).withStyle(artefact.couleur));
		stack.set(DataComponents.ITEM_MODEL, Identifier.parse(artefact.modele));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.RARITY, artefact == Artefact.FRAGMENT ? Rarity.UNCOMMON : Rarity.EPIC);

		List<Component> lore = new ArrayList<>();
		Style style = Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false);
		for (String ligne : artefact.description) {
			lore.add(Component.literal(ligne).withStyle(style));
		}
		if (artefact.estOutil()) {
			stack.set(DataComponents.MAX_STACK_SIZE, 1);
			stack.set(DataComponents.MAX_DAMAGE, artefact.chargesMax());
			stack.set(DataComponents.DAMAGE, 0);
			stack.set(DataComponents.USE_COOLDOWN, new UseCooldown(artefact.rechargeTicks() / 20.0F,
					Optional.of(Identifier.fromNamespaceAndPath(Chronomancie.MOD_ID, artefact.groupeRecharge))));
			lore.add(Component.empty());
			lore.add(Component.literal("Accroupi + fragment en main gauche : recharger").withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_PURPLE).withItalic(true)));
		}
		lore.add(Component.literal("✦ Chronomancie").withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA).withItalic(false)));
		stack.set(DataComponents.LORE, new ItemLore(lore));
		return stack;
	}

	/** Quel artefact est cet objet ? null si c'est un objet ordinaire. */
	public static Artefact identifier(final ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		return data.copyTag().getString(CLE).map(Artefact::parId).orElse(null);
	}

	/** Met l'objet dans l'inventaire, ou le pose aux pieds du joueur s'il est plein. */
	public static void offrir(final ServerPlayer joueur, final ItemStack stack) {
		if (!joueur.getInventory().add(stack)) {
			joueur.drop(stack, false, Prediction.SERVER_ONLY);
		}
	}

	public static boolean est(final ItemStack stack, final Artefact artefact) {
		return identifier(stack) == artefact;
	}

	/** Aligne un artefact sur les réglages actuels (charges et temps de recharge modifiables dans la config). */
	public static void actualiser(final ItemStack stack, final Artefact artefact) {
		if (!artefact.estOutil()) {
			return;
		}
		int max = artefact.chargesMax();
		if (stack.getMaxDamage() != max) {
			int restantes = chargesRestantes(stack);
			stack.set(DataComponents.MAX_DAMAGE, max);
			stack.setDamageValue(Math.max(0, max - Math.min(max, restantes)));
		}
		UseCooldown voulu = new UseCooldown(artefact.rechargeTicks() / 20.0F,
				Optional.of(Identifier.fromNamespaceAndPath(Chronomancie.MOD_ID, artefact.groupeRecharge)));
		if (!voulu.equals(stack.get(DataComponents.USE_COOLDOWN))) {
			stack.set(DataComponents.USE_COOLDOWN, voulu);
		}
	}

	public static int chargesRestantes(final ItemStack stack) {
		return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
	}

	public static void consommerCharge(final ItemStack stack) {
		stack.setDamageValue(Math.min(stack.getMaxDamage(), stack.getDamageValue() + 1));
	}

	/** Recharge d'un quart (au moins une charge). Renvoie le nombre de charges regagnées. */
	public static int recharger(final ItemStack stack) {
		int gain = Math.max(1, stack.getMaxDamage() / 4);
		int avant = stack.getDamageValue();
		stack.setDamageValue(Math.max(0, avant - gain));
		return avant - stack.getDamageValue();
	}
}
