package com.ncombarieu.chronomancie.item;

import java.util.List;
import java.util.function.ToIntFunction;

import com.ncombarieu.chronomancie.ChronoConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Les objets du mod. Ce sont des objets vanilla habillés (nom, modèle, description, données cachées) :
 * les joueurs n'ont rien à installer.
 */
public enum Artefact {
	FRAGMENT("fragment", "Fragment temporel", Items.AMETHYST_SHARD, "minecraft:amethyst_shard", ChatFormatting.LIGHT_PURPLE,
			List.of("Un éclat de temps cristallisé,", "arraché à une faille refermée."),
			null, null, null),
	SABLIER("sablier", "Sablier du Retour", Items.CLOCK, "minecraft:clock", ChatFormatting.GOLD,
			List.of("Clic droit : remonte ton propre fil", "du temps de quelques secondes.", "Tu retrouves ta position et ta santé d'alors."),
			c -> c.sablier.charges, c -> c.sablier.recharge, "retour"),
	MIROIR("miroir", "Miroir d'Écho", Items.CLOCK, "minecraft:echo_shard", ChatFormatting.AQUA,
			List.of("Clic droit : ton Écho surgit du passé", "et rejoue tes derniers pas.", "Les monstres s'acharnent sur lui."),
			c -> c.miroir.charges, c -> c.miroir.recharge, "echo"),
	STASE("stase", "Montre de Stase", Items.CLOCK, "minecraft:recovery_compass", ChatFormatting.BLUE,
			List.of("Clic droit : fige le temps autour de toi.", "Projectiles, monstres, TNT… tout s'arrête.", "Les coups portés s'accumulent jusqu'au dégel."),
			c -> c.stase.charges, c -> c.stase.recharge, "stase"),
	CHRONOSCOPE("chronoscope", "Chronoscope", Items.CLOCK, "minecraft:spyglass", ChatFormatting.GREEN,
			List.of("Clic droit : le monde se souvient", "et répare les dégâts des explosions.", "Accroupi : aperçu sans rien restaurer."),
			c -> c.chronoscope.charges, c -> c.chronoscope.recharge, "chronoscope");

	public final String id;
	public final String nom;
	public final Item base;
	public final String modele;
	public final ChatFormatting couleur;
	public final List<String> description;
	private final ToIntFunction<ChronoConfig> charges;
	private final ToIntFunction<ChronoConfig> recharge;
	/** Groupe de temps de recharge propre à l'artefact (sinon tous les objets « horloge » partageraient le même). */
	public final String groupeRecharge;

	Artefact(final String id, final String nom, final Item base, final String modele, final ChatFormatting couleur, final List<String> description,
			final ToIntFunction<ChronoConfig> charges, final ToIntFunction<ChronoConfig> recharge, final String groupeRecharge) {
		this.id = id;
		this.nom = nom;
		this.base = base;
		this.modele = modele;
		this.couleur = couleur;
		this.description = description;
		this.charges = charges;
		this.recharge = recharge;
		this.groupeRecharge = groupeRecharge;
	}

	public boolean estOutil() {
		return this.charges != null;
	}

	public int chargesMax() {
		return this.charges == null ? 0 : Math.max(1, this.charges.applyAsInt(ChronoConfig.get()));
	}

	public int rechargeTicks() {
		return this.recharge == null ? 0 : Math.max(0, this.recharge.applyAsInt(ChronoConfig.get())) * 20;
	}

	public static Artefact parId(final String id) {
		for (Artefact artefact : values()) {
			if (artefact.id.equals(id)) {
				return artefact;
			}
		}
		return null;
	}
}
