package com.ncombarieu.hameau;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;

/** Ce qu'un villageois sait de sa situation, mis en texte pour Claude. */
public final class Perception {
	static final int VUE = 16;

	private Perception() {
	}

	static String cleMetier(final Villager villageois) {
		return villageois.getVillagerData().profession().unwrapKey().map(cle -> cle.identifier().getPath()).orElse("none");
	}

	static String metier(final Villager villageois) {
		if (villageois.isBaby()) {
			return "enfant";
		}
		return switch (cleMetier(villageois)) {
			case "farmer" -> "paysan";
			case "fisherman" -> "pêcheur";
			case "librarian" -> "bibliothécaire";
			case "armorer" -> "armurier";
			case "toolsmith" -> "forgeron d'outils";
			case "weaponsmith" -> "forgeron d'armes";
			case "cleric" -> "prêtre";
			case "butcher" -> "boucher";
			case "shepherd" -> "berger";
			case "fletcher" -> "archer-fléchier";
			case "mason" -> "maçon";
			case "leatherworker" -> "tanneur";
			case "cartographer" -> "cartographe";
			case "nitwit" -> "simplet du village";
			default -> "sans métier";
		};
	}

	/** « collectionne les cailloux » reste tel quel ; « est doux » aussi : les listes sont écrites à la 3e personne, lisibles après « tu ». */
	private static String conjuguer(final String texte) {
		if (texte.startsWith("est ")) {
			return "es " + texte.substring(4);
		}
		if (texte.startsWith("se ")) {
			return "te " + texte.substring(3);
		}
		if (texte.startsWith("s'")) {
			return "t'" + texte.substring(2);
		}
		return texte;
	}

	static String nom(final Entity entite) {
		if (entite instanceof Villager villageois) {
			return Ames.de(villageois).nom;
		}
		return entite.getName().getString();
	}

	static String coord(final BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	static String objet(final ItemStack pile) {
		return pile.getHoverName().getString() + (pile.getCount() > 1 ? " x" + pile.getCount() : "");
	}

	static String moment(final ServerLevel level) {
		long horloge = level.getOverworldClockTime();
		long heure = horloge % 24000;
		String phase = heure < 6000 ? "matin" : heure < 12000 ? "après-midi" : heure < 13500 ? "soir" : "nuit";
		return "Jour " + (horloge / 24000 + 1) + ", " + phase;
	}

	static String fiche(final Villager villageois, final Ame ame, final ServerLevel level) {
		ame.metier = metier(villageois);
		StringBuilder f = new StringBuilder();
		f.append("Tu es ").append(ame.nom).append(ame.femme ? " (femme" : " (homme").append(ame.enfant ? ", encore enfant" : "").append("), métier : ").append(ame.metier)
				.append(". Caractère : ").append(String.join(", ", ame.traits)).append(". Ta manie : tu ").append(conjuguer(ame.manie))
				.append(". Ta façon de parler : tu ").append(conjuguer(ame.parler)).append(".\n");
		f.append("Tu désires : ").append(ame.desir).append(". Tu crains : ").append(ame.peur).append(".\n");
		if (!ame.liens.isEmpty()) {
			f.append("Tes liens : ").append(String.join(" ", ame.liens)).append("\n");
		}
		String activite = Actions.activite(villageois.getUUID());
		if (activite != null) {
			f.append("En ce moment tu es en train de : ").append(activite).append(". (\"rien\" pour continuer, \"arreter\" pour abandonner.)\n");
		} else if (ame.chantier != null) {
			f.append("Tu as un chantier interrompu : « ").append(ame.chantier.nom).append(" » en ").append(ame.chantier.x).append(" ").append(ame.chantier.y).append(" ").append(ame.chantier.z)
					.append(", reste ").append(ame.chantier.restants.size()).append(" blocs à poser. (\"batir\" pour le reprendre.)\n");
		}
		if (ame.projet != null) {
			f.append("Ton projet du moment : ").append(ame.projet).append("\n");
		}
		f.append("Humeur : ").append(ame.humeur).append(". Santé : ").append((int) villageois.getHealth()).append("/").append((int) villageois.getMaxHealth());
		if (villageois.isSleeping()) {
			f.append(". Tu dormais");
		}
		List<String> inventaire = new ArrayList<>();
		for (ItemStack pile : villageois.getInventory().getItems()) {
			if (!pile.isEmpty()) {
				inventaire.add(objet(pile));
			}
		}
		f.append(". Inventaire : ").append(inventaire.isEmpty() ? "rien" : String.join(", ", inventaire)).append(".\n");
		f.append(moment(level)).append(level.isRaining() ? ", il pleut" : "").append(". Tu es en ").append(coord(villageois.blockPosition())).append(".\n");

		f.append("Autour de toi :\n");
		List<LivingEntity> proches = level.getEntitiesOfClass(LivingEntity.class, villageois.getBoundingBox().inflate(VUE),
				e -> e != villageois && e.isAlive() && (e instanceof Villager || e instanceof ServerPlayer || e instanceof Enemy));
		proches.sort(Comparator.comparingDouble(villageois::distanceToSqr));
		int monstres = 0;
		int listes = 0;
		for (LivingEntity proche : proches) {
			if (proche instanceof Enemy) {
				monstres++;
				if (monstres > 2) {
					continue;
				}
			} else if (++listes > 8) {
				continue;
			}
			String nom = nom(proche);
			f.append("- ").append(nom);
			if (proche instanceof ServerPlayer joueur) {
				f.append(ame.relations.containsKey(nom) ? " (voyageur" : " (voyageur que tu ne connais pas encore");
				if (!joueur.getMainHandItem().isEmpty()) {
					f.append(", tient : ").append(objet(joueur.getMainHandItem()));
				}
				f.append(")");
			} else if (proche instanceof Villager autre) {
				f.append(" (").append(metier(autre)).append(autre.isSleeping() ? ", dort" : "").append(")");
			} else {
				f.append(" (monstre)");
			}
			f.append(", à ").append((int) villageois.distanceTo(proche)).append(" blocs, en ").append(coord(proche.blockPosition()));
			if (ame.relations.containsKey(nom)) {
				f.append(", ton opinion : ").append(ame.relation(nom));
			}
			f.append("\n");
		}
		if (proches.isEmpty()) {
			f.append("- personne\n");
		}

		List<String> lieux = new ArrayList<>();
		lieu(villageois, MemoryModuleType.HOME, "maison", lieux);
		lieu(villageois, MemoryModuleType.JOB_SITE, "travail", lieux);
		lieu(villageois, MemoryModuleType.MEETING_POINT, "place", lieux);
		int coffres = 0;
		BlockPos centre = villageois.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-8, -3, -8), centre.offset(8, 3, 8))) {
			if (coffres < 3 && level.getBlockState(pos).hasBlockEntity() && level.getBlockEntity(pos) instanceof Container) {
				lieux.add(level.getBlockState(pos).getBlock().getName().getString() + " en " + coord(pos));
				coffres++;
			}
		}
		BlockPos tronc = null;
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-10, -2, -10), centre.offset(10, 5, 10))) {
			if (level.getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS) && !level.getBlockState(pos.below()).is(net.minecraft.tags.BlockTags.LOGS)
					&& level.getBlockState(pos.above()).is(net.minecraft.tags.BlockTags.LOGS) && (tronc == null || pos.distSqr(centre) < tronc.distSqr(centre))) {
				tronc = pos.immutable();
			}
		}
		if (tronc != null) {
			lieux.add("arbre (pied du tronc) en " + coord(tronc));
		}
		BlockPos sol = centre.below();
		lieux.add("sol sous tes pieds : " + level.getBlockState(sol).getBlock().getName().getString() + " en " + coord(sol));
		if (!lieux.isEmpty()) {
			f.append("Lieux : ").append(String.join(" ; ", lieux)).append(".\n");
		}

		List<String> autres = new ArrayList<>();
		for (Map.Entry<String, Integer> relation : ame.relations.entrySet()) {
			if (Math.abs(relation.getValue()) >= 10 && f.indexOf("- " + relation.getKey()) < 0) {
				autres.add(relation.getKey() + " " + relation.getValue());
			}
		}
		if (!autres.isEmpty()) {
			f.append("Tes opinions sur des absents (-100 à 100) : ").append(String.join(", ", autres)).append(".\n");
		}
		if (!ame.marquants.isEmpty()) {
			f.append("Souvenirs marquants :\n");
			ame.marquants.forEach(s -> f.append("- ").append(s).append("\n"));
		}
		if (!ame.recents.isEmpty()) {
			f.append("Souvenirs récents :\n");
			ame.recents.forEach(s -> f.append("- ").append(s).append("\n"));
		}
		if (!ame.dernierePensee.isEmpty()) {
			f.append("Ta dernière pensée : ").append(ame.dernierePensee).append("\n");
		}
		f.append("Ce qui vient de se passer :\n");
		if (ame.nouveaux.isEmpty()) {
			f.append("- rien de particulier\n");
		} else {
			ame.nouveaux.forEach(s -> f.append("- ").append(s).append("\n"));
		}
		return f.toString();
	}

	private static void lieu(final Villager villageois, final MemoryModuleType<GlobalPos> type, final String nom, final List<String> lieux) {
		Optional<GlobalPos> pos = villageois.getBrain().getMemory(type);
		pos.ifPresent(p -> lieux.add(nom + " en " + coord(p.pos())));
	}
}
