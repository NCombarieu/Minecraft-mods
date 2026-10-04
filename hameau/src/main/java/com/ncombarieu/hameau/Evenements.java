package com.ncombarieu.hameau;

import java.util.Comparator;
import java.util.List;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;

/** Ce que le monde apprend aux villageois : paroles des joueurs, coups, morts, cadeaux. */
public final class Evenements {
	private static final java.util.Map<java.util.UUID, Long> DERNIERE_CASSE = new java.util.HashMap<>();

	private Evenements() {
	}

	private static boolean pres(final Villager villageois, final MemoryModuleType<net.minecraft.core.GlobalPos> lieu, final net.minecraft.core.BlockPos pos) {
		return villageois.getBrain().getMemory(lieu).map(p -> p.pos().closerThan(pos, 5)).orElse(false);
	}

	static void register() {
		// Le chat signé est filtré côté client (réglage « chat sécurisé uniquement », horloge déréglée, joueur masqué, compte restreint) :
		// certains ne voient alors pas les messages des autres. Redistribué en message système, il s'affiche chez tout le monde.
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, joueur, type) -> {
			if (!HameauConfig.get().chatFiable) {
				return true;
			}
			joueur.level().getServer().getPlayerList().broadcastSystemMessage(type.decorate(message.decoratedContent()), false);
			entendre(joueur, message.signedContent(), false);
			return false;
		});
		ServerMessageEvents.CHAT_MESSAGE.register((message, joueur, type) -> {
			String texte = message.signedContent();
			joueur.level().getServer().execute(() -> entendre(joueur, texte, false));
		});

		// Un coup porté au corps atteint en réalité le villageois.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entite, source, degats) -> {
			Villager cache = Corps.villageoisDe(entite);
			if (cache == null) {
				return true;
			}
			if (cache.level() instanceof ServerLevel level && cache.isAlive()) {
				cache.hurtServer(level, source, degats);
			}
			return false;
		});

		ServerLivingEntityEvents.AFTER_DAMAGE.register((victime, source, degatsBruts, degats, bloque) -> {
			Entity auteur = source.getEntity();
			if (!(auteur instanceof LivingEntity) || auteur == victime || !(victime.level() instanceof ServerLevel level)) {
				return;
			}
			boolean auteurNomme = auteur instanceof Villager || auteur instanceof ServerPlayer;
			if (!(victime instanceof Villager) && !(victime instanceof ServerPlayer && auteur instanceof Villager)) {
				return;
			}
			String nomAuteur = Perception.nom(auteur);
			String nomVictime = Perception.nom(victime);
			long maintenant = level.getServer().getTickCount();
			if (victime instanceof Villager villageois) {
				Corps.accuser(villageois, source);
				Ame ame = Ames.de(villageois);
				ame.noter(nomAuteur + " t'a frappé.");
				if (auteurNomme) {
					ame.ajusterRelation(nomAuteur, -20);
				}
				ame.presser(maintenant, 2);
			}
			if (auteurNomme) {
				Vie.temoins(auteur, victime, "Tu as vu " + nomAuteur + " frapper " + nomVictime + ".", true);
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((mort, source) -> {
			if (!(mort instanceof Villager) && !(mort instanceof ServerPlayer)) {
				return;
			}
			String nom = Perception.nom(mort);
			Entity tueur = source.getEntity();
			String recit = nom + " est mort" + (tueur != null ? ", tué par " + Perception.nom(tueur) : "") + " sous tes yeux.";
			if (mort instanceof Villager villageois) {
				Ames.oublier(villageois.getUUID());
				Actions.oublier(villageois.getUUID());
				Bulles.effacer(villageois.getUUID());
			}
			Vie.temoins(mort, tueur, recit, true);
		});

		// Un joueur qui casse un bloc près d'eux : les villageois le remarquent, surtout près de chez eux.
		PlayerBlockBreakEvents.AFTER.register((level, joueur, pos, etat, blocEntite) -> {
			if (!(level instanceof ServerLevel monde) || joueur.isSpectator()) {
				return;
			}
			long maintenant = monde.getServer().getTickCount();
			if (maintenant - DERNIERE_CASSE.getOrDefault(joueur.getUUID(), -1000L) < 100) {
				return;
			}
			DERNIERE_CASSE.put(joueur.getUUID(), maintenant);
			String nomJoueur = joueur.getName().getString();
			String bloc = etat.getBlock().getName().getString();
			for (Villager villageois : monde.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(pos).inflate(10), Entity::isAlive)) {
				Ame ame = Ames.de(villageois);
				String ou = null;
				if (pres(villageois, MemoryModuleType.HOME, pos)) {
					ou = "ta maison";
				} else if (pres(villageois, MemoryModuleType.JOB_SITE, pos)) {
					ou = "ton lieu de travail";
				}
				if (ou != null) {
					ame.noter(nomJoueur + " vient de casser : " + bloc + ", tout près de " + ou + " (en " + Perception.coord(pos) + ").");
					if (ame.peutRepliquer(maintenant)) {
						ame.presser(maintenant, 2);
					}
				} else {
					ame.noter("Tu as vu " + nomJoueur + " casser : " + bloc + " en " + Perception.coord(pos) + ".");
				}
			}
		});

		// Accroupi + clic droit avec un objet en main : on l'offre au villageois au lieu d'ouvrir le commerce.
		UseEntityCallback.EVENT.register((joueur, level, main, entite, impact) -> {
			Villager cache = Corps.villageoisDe(entite);
			if (cache != null && !(joueur.isShiftKeyDown() && !joueur.getItemInHand(main).isEmpty())) {
				// Clic sur le corps : c'est au villageois qu'on s'adresse (commerce).
				return level.isClientSide() || joueur.isSpectator() ? InteractionResult.PASS : cache.interact(joueur, main, impact != null ? impact.getLocation() : cache.position());
			}
			Entity vise = cache != null ? cache : entite;
			if (!(vise instanceof Villager villageois) || main != InteractionHand.MAIN_HAND || !joueur.isShiftKeyDown() || joueur.isSpectator()) {
				return InteractionResult.PASS;
			}
			ItemStack tenu = joueur.getItemInHand(main);
			if (tenu.isEmpty() || level.isClientSide()) {
				return InteractionResult.PASS;
			}
			ItemStack offert = tenu.copyWithCount(1);
			if (!villageois.getInventory().canAddItem(offert)) {
				joueur.sendSystemMessage(Component.literal("Ses poches sont pleines.").withStyle(ChatFormatting.GRAY));
				return InteractionResult.SUCCESS;
			}
			villageois.getInventory().addItem(offert);
			tenu.shrink(1);
			Ame ame = Ames.de(villageois);
			String nomJoueur = joueur.getName().getString();
			String quoi = Perception.objet(offert);
			ame.noter(nomJoueur + " t'a offert " + quoi + ".");
			ame.ajusterRelation(nomJoueur, 8);
			ame.presser(level.getServer().getTickCount(), 2);
			joueur.sendSystemMessage(Component.literal("Tu offres " + quoi + " à " + ame.nom + ".").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
			Vie.temoins(villageois, joueur, "Tu as vu " + nomJoueur + " offrir " + quoi + " à " + ame.nom + ".", false);
			return InteractionResult.SUCCESS;
		});
	}

	/** Un joueur parle : le villageois nommé (ou le plus proche) est interpellé, les autres entendent. */
	/** @param vocal dit au micro : le prénom peut avoir été mal transcrit, alors le villageois qu'on regarde est celui à qui l'on parle */
	static void entendre(final ServerPlayer joueur, final String texte, final boolean vocal) {
		if (texte.startsWith("/") || joueur.isSpectator()) {
			return;
		}
		ServerLevel level = joueur.level();
		String maitre = joueur.getName().getString();
		List<Villager> proches = level.getEntitiesOfClass(Villager.class, joueur.getBoundingBox().inflate(24),
				v -> v.isAlive() && (v.distanceTo(joueur) <= 10 || maitre.equals(Ames.de(v).maitre)));
		if (proches.isEmpty()) {
			return;
		}
		proches.sort(Comparator.comparingDouble(joueur::distanceToSqr));
		String minuscule = Ames.simplifier(texte);
		Villager vise = null;
		for (Villager villageois : proches) {
			if (minuscule.contains(Ames.simplifier(Ames.de(villageois).nom))) {
				vise = villageois;
				break;
			}
		}
		if (vise == null && vocal) {
			double meilleur = 0.9;
			for (Villager villageois : proches) {
				net.minecraft.world.phys.Vec3 vers = villageois.getEyePosition().subtract(joueur.getEyePosition()).normalize();
				double alignement = vers.dot(joueur.getLookAngle());
				if (alignement > meilleur) {
					meilleur = alignement;
					vise = villageois;
				}
			}
		}
		if (vise == null && joueur.distanceTo(proches.getFirst()) <= 6) {
			vise = proches.getFirst();
		}
		if (vise == null) {
			// Personne tout près : un ordre lancé à la cantonade s'adresse à celui qui le sert.
			for (Villager villageois : proches) {
				if (maitre.equals(Ames.de(villageois).maitre)) {
					vise = villageois;
					break;
				}
			}
		}
		String nomJoueur = joueur.getName().getString();
		String nomVise = vise != null ? Ames.de(vise).nom : null;
		long maintenant = level.getServer().getTickCount();
		for (Villager villageois : proches) {
			Ame ame = Ames.de(villageois);
			if (villageois == vise) {
				ame.noter(nomJoueur + " t'a dit : « " + texte + " »");
				ame.presser(maintenant, 1);
				Actions.ecouter(villageois, joueur, maintenant);
				Corps.accroupir(villageois, 8);
				Bulles.montrer(villageois, "…", true, maintenant);
			} else {
				ame.noter(nomJoueur + (nomVise != null ? " a dit à " + nomVise : " a dit") + " : « " + texte + " »");
			}
		}
	}
}
