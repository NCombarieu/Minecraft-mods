package com.ncombarieu.chronomancie.progression;

import com.ncombarieu.chronomancie.Chronomancie;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Les progrès (onglet « Chronomancie » de l'écran des progrès). */
public enum Succes {
	FAILLE_VUE("faille_vue"),
	FAILLE_REFERMEE("faille_refermee"),
	RETOUR("retour"),
	ECHO("echo"),
	STASE("stase"),
	RESTAURATION("restauration"),
	MAITRE("maitre");

	private final Identifier id;

	Succes(final String chemin) {
		this.id = Identifier.fromNamespaceAndPath(Chronomancie.MOD_ID, chemin);
	}

	public void accorder(final ServerPlayer joueur) {
		AdvancementHolder holder = joueur.level().getServer().getAdvancements().get(this.id);
		if (holder != null) {
			joueur.getAdvancements().award(holder, "accorde");
		}
	}
}
