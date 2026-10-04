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
		if (ame.histoire != null) {
			f.append("Ton histoire : ").append(ame.histoire).append("\n");
		}
		Ames.Village village = Ames.village(ame);
		if (ame.etranger) {
			f.append("Tu n'es pas d'ici : tu es arrivé d'ailleurs, seul, et personne ne t'a encore présenté aux gens du coin.\n");
		} else if (village != null && village.nom != null && village.culture != null && !village.culture.isEmpty()) {
			f.append("Ton village, ").append(village.nom).append(" : ").append(village.culture).append("\n");
		}
		if (!ame.liens.isEmpty()) {
			f.append("Tes liens : ").append(String.join(" ", ame.liens)).append("\n");
		}
		if (ame.role != null) {
			f.append("Ta place au village (celle que tu t'es faite) : ").append(ame.role).append(".\n");
		}
		if (ame.maitre != null) {
			f.append("Tu es au service de ").append(ame.maitre).append(" : ce qu'il te demande, tu le fais tout de suite, par l'action qui convient, sans discuter ni marchander (râler reste permis, selon ton caractère). ")
					.append(ame.suit ? "Tu le suis partout ; l'action \"rester\" te fait l'attendre sur place." : "Il t'a dit d'attendre ici ; l'action \"suivre\" te remet dans ses pas.").append("\n");
		}
		if (village != null && !ame.etranger && !village.chronique.isEmpty()) {
			f.append("Ce que tout le village sait :\n");
			village.chronique.forEach(fait -> f.append("- ").append(fait).append("\n"));
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
				f.append(nom.equals(ame.maitre) ? " (ton maître" : ame.relations.containsKey(nom) ? " (voyageur" : " (voyageur que tu ne connais pas encore");
				if (!joueur.getMainHandItem().isEmpty()) {
					f.append(", tient : ").append(objet(joueur.getMainHandItem()));
				}
				f.append(")");
			} else if (proche instanceof Villager autre) {
				Ame voisine = Ames.de(autre);
				f.append(" (").append(metier(autre)).append(voisine.role != null ? " ; " + voisine.role : "").append(ame.nom.equals(voisine.maitre) ? " ; à ton service" : voisine.maitre != null ? " ; au service de " + voisine.maitre : "")
						.append(autre.isSleeping() ? ", dort" : "").append(")");
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

		Map<String, Integer> betes = new java.util.TreeMap<>();
		for (net.minecraft.world.entity.animal.Animal bete : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, villageois.getBoundingBox().inflate(VUE), Entity::isAlive)) {
			betes.merge(bete.getType().getDescription().getString(), 1, Integer::sum);
		}
		if (!betes.isEmpty()) {
			List<String> especes = new ArrayList<>();
			betes.forEach((espece, nombre) -> especes.add(espece + (nombre > 1 ? " x" + nombre : "")));
			f.append("Bêtes aux alentours : ").append(String.join(", ", especes)).append(".\n");
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

	private static String typeVillage(final String type) {
		return switch (type == null ? "" : type) {
			case "desert" -> "désert";
			case "savanna" -> "savane";
			case "taiga" -> "taïga";
			case "snow" -> "plaine enneigée";
			case "swamp" -> "marais";
			case "jungle" -> "jungle";
			default -> "plaine";
		};
	}

	private static String portrait(final Ame ame) {
		return ame.nom + " (" + (ame.femme ? "femme" : "homme") + ", " + ame.metier + " ; " + String.join(", ", ame.traits) + ")";
	}

	/** Les habitants du même coin, du plus proche au plus lointain, hors ébauches. */
	static List<Ame> voisins(final Ame ame) {
		List<Ame> voisins = new ArrayList<>();
		for (Ame autre : Ames.toutes()) {
			if (autre != ame && !autre.ebauche && Math.abs(autre.x - ame.x) < Ames.RAYON_VILLAGE && Math.abs(autre.z - ame.z) < Ames.RAYON_VILLAGE) {
				voisins.add(autre);
			}
		}
		voisins.sort(Comparator.comparingLong(a -> (long) (a.x - ame.x) * (a.x - ame.x) + (long) (a.z - ame.z) * (a.z - ame.z)));
		return voisins;
	}

	/** Ce qu'on donne à Claude pour fonder un village : son milieu, qui y vit déjà, et de quoi le démarquer. */
	static String fondation(final Ames.Village village) {
		StringBuilder f = new StringBuilder("Village de ").append(typeVillage(village.type)).append(".\n");
		List<String> habitants = new ArrayList<>();
		for (Ame ame : Ames.toutes()) {
			if (!ame.ebauche && Math.abs(ame.x - village.x) < Ames.RAYON_VILLAGE && Math.abs(ame.z - village.z) < Ames.RAYON_VILLAGE && habitants.size() < 25) {
				habitants.add(ame.nom + " (" + ame.metier + ")");
			}
		}
		if (!habitants.isEmpty()) {
			f.append("Des gens y vivent déjà, la culture doit leur aller : ").append(String.join(", ", habitants)).append(".\n");
		}
		List<String> autres = new ArrayList<>();
		for (Ames.Village autre : Ames.villages()) {
			if (autre != village && autre.nom != null && autre.culture != null && !autre.culture.isEmpty()) {
				autres.add(autre.nom + " : " + autre.culture);
			}
		}
		if (!autres.isEmpty()) {
			f.append("Villages qui existent déjà, dont celui-ci doit se démarquer nettement :\n- ").append(String.join("\n- ", autres.subList(Math.max(0, autres.size() - 6), autres.size()))).append("\n");
		}
		f.append("Tirés au sort pour t'inspirer, à prendre ou à laisser : ").append(Ames.germes(3)).append(".");
		return f.toString();
	}

	/** Ce qu'on donne à Claude pour inventer un habitant, le réinventer, ou le modifier selon la demande d'un joueur. */
	static String naissance(final Villager villageois, final Ame ame, final Ames.Village village) {
		ame.metier = metier(villageois);
		StringBuilder f = new StringBuilder();
		if (ame.etranger) {
			f.append("Cet habitant n'appartient à aucun village : c'est un étranger venu d'ailleurs, qui vient d'arriver seul. Son prénom, son parler et son histoire viennent d'un autre pays que ceux des gens du coin, à toi d'imaginer lequel.\n");
		} else if (village != null && village.nom != null && village.culture != null && !village.culture.isEmpty()) {
			f.append("Village : ").append(village.nom).append(" (").append(typeVillage(village.type)).append("). ").append(village.culture).append("\n");
		} else {
			f.append("Village de ").append(typeVillage(village != null ? village.type : null)).append(".\n");
		}
		List<Ame> voisins = voisins(ame);
		if (!voisins.isEmpty()) {
			f.append(ame.etranger ? "Gens du coin, qu'il ne connaît pas : " : "Habitants déjà là : ");
			f.append(String.join(" ; ", voisins.subList(0, Math.min(12, voisins.size())).stream().map(Perception::portrait).toList())).append(".\n");
		}
		List<String> pris = new ArrayList<>();
		for (Ame autre : Ames.toutes()) {
			if (autre != ame && !voisins.subList(0, Math.min(12, voisins.size())).contains(autre) && pris.size() < 80) {
				pris.add(autre.nom);
			}
		}
		if (!pris.isEmpty()) {
			f.append("Autres prénoms déjà pris : ").append(String.join(", ", pris)).append(".\n");
		}
		boolean aVecu = !ame.liens.isEmpty() || !ame.marquants.isEmpty() || !ame.relations.isEmpty();
		boolean veutLien = !ame.lie && !ame.etranger && !voisins.isEmpty();
		if (ame.consigne != null) {
			f.append("\nCet habitant existe déjà. Sa fiche actuelle :\n");
			f.append("{\"nom\":\"").append(ame.nom).append("\", sexe : ").append(ame.femme ? "femme" : "homme").append(", métier : ").append(ame.metier).append(ame.enfant ? ", encore enfant" : "")
					.append(", traits : ").append(String.join(", ", ame.traits)).append(", manie : ").append(ame.manie).append(", parler : ").append(ame.parler)
					.append(", désir : ").append(ame.desir).append(", peur : ").append(ame.peur).append(ame.histoire != null ? ", histoire : " + ame.histoire : "").append("}\n");
			vecu(f, ame);
			f.append("Un joueur demande de le changer ainsi : « ").append(ame.consigne).append(" »\n");
			f.append("Récris sa fiche complète en suivant cette demande à la lettre, même si elle est outrancière ou comique : c'est un jeu. Change tout ce qu'elle implique (traits, manie, parler, désir, peur, histoire) et garde le reste. "
					+ "Garde son prénom, sauf si la demande en donne ou en réclame un autre. Si la demande change son sexe, ajoute \"femme\":true ou false. ");
		} else {
			f.append("\nL'habitant à inventer : ").append(ame.femme ? "une femme" : "un homme").append(", ").append(ame.enfant ? "encore enfant" : "environ " + Ames.age() + " ans")
					.append(", métier : ").append(ame.metier).append(".\n");
			if (ame.baptise) {
				f.append("Il s'appelle « ").append(ame.nom).append(" » : garde ce prénom tel quel.\n");
			}
			if (aVecu) {
				f.append("Il vit ici depuis un moment").append(ame.baptise ? "" : " sous le prénom « " + ame.nom + " »").append(" : donne-lui une identité toute neuve")
						.append(ame.baptise ? "" : " (nouveau prénom compris)").append(", mais compatible avec ce qu'il a vécu.\n");
				vecu(f, ame);
			}
			f.append("Tirés au sort pour t'inspirer, à prendre ou à laisser : ").append(ame.baptise ? "" : "initiale du prénom " + Ames.initiale() + " ; ").append(Ames.germes(3)).append(".\n");
		}
		String voix = Voix.choix(ame);
		if (!voix.isEmpty() && (ame.voixId == null || ame.consigne == null)) {
			f.append("Voix possibles pour le faire parler : ").append(voix).append(".\n");
		}
		f.append(veutLien ? "Donne-lui un \"lien\" avec l'un des habitants déjà là." : "\"lien\" : null.");
		return f.toString();
	}

	private static void vecu(final StringBuilder f, final Ame ame) {
		if (!ame.liens.isEmpty()) {
			f.append("Ses liens (ils restent vrais) : ").append(String.join(" ", ame.liens)).append("\n");
		}
		if (!ame.marquants.isEmpty()) {
			f.append("Ses souvenirs marquants : ").append(String.join(" ; ", ame.marquants)).append("\n");
		}
	}

	private static void lieu(final Villager villageois, final MemoryModuleType<GlobalPos> type, final String nom, final List<String> lieux) {
		Optional<GlobalPos> pos = villageois.getBrain().getMemory(type);
		pos.ifPresent(p -> lieux.add(nom + " en " + coord(p.pos())));
	}
}
