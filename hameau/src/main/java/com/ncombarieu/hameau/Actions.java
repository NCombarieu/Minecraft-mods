package com.ncombarieu.hameau;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Traduit une décision en gestes dans le monde, suivis de tick en tick jusqu'à leur terme. */
public final class Actions {
	private static final float PAS = 0.55F;
	private static final float COURSE = 0.75F;
	private static final Map<UUID, Geste> GESTES = new HashMap<>();

	private Actions() {
	}

	private static final class Geste {
		String type;
		Entity cible;
		BlockPos pos;
		String objet;
		long fin;
		int coups;
		long prochainCoup;
		/** Travail manuel en cours sur geste.pos : ticks écoulés et ticks nécessaires (0 = pas commencé). */
		int avancement;
		int duree;
		int blocsFaits;
		/** Pour « batir » : l'âme à qui appartient le chantier (soi-même, ou celui qu'on aide). */
		Ame proprietaire;
		/** Pour « miner » et « recolter » : ce qu'on cherche, combien, les blocs à dégager avant d'avancer, la case où avancer ensuite. */
		java.util.function.Predicate<BlockState> ressource;
		String nomRessource;
		int objectif;
		java.util.ArrayDeque<BlockPos> aDegager = new java.util.ArrayDeque<>();
		BlockPos pas;
		long delaiPas;
		BlockPos filon;
		boolean surFilon;
		int galerie;
	}

	/** Les étapes suivantes du plan de chaque villageois, et ceux qui s'interrompent pour écouter quelqu'un. */
	private static final Map<UUID, java.util.ArrayDeque<Cerveau.Etape>> SUITES = new HashMap<>();
	private static final Map<UUID, Object[]> ECOUTES = new HashMap<>();

	private static void abandonner(final Villager villageois) {
		GESTES.remove(villageois.getUUID());
		SUITES.remove(villageois.getUUID());
		Chantiers.oublier(villageois.getUUID());
		Corps.relacher(villageois);
	}

	/** Ce que le villageois est en train de faire, pour sa fiche : il peut ainsi choisir de continuer ou d'arrêter. */
	static String activite(final UUID uuid) {
		Geste geste = GESTES.get(uuid);
		if (geste == null) {
			return null;
		}
		StringBuilder texte = new StringBuilder(geste.type);
		if (geste.type.equals("batir") && geste.proprietaire != null && geste.proprietaire.chantier != null) {
			texte.append(" « ").append(geste.proprietaire.chantier.nom).append(" » (reste ").append(geste.proprietaire.chantier.restants.size()).append(" blocs)");
		} else if (geste.cible != null) {
			texte.append(" ").append(Perception.nom(geste.cible));
		} else if (geste.pos != null) {
			texte.append(" ").append(Perception.coord(geste.pos));
		}
		java.util.ArrayDeque<Cerveau.Etape> suite = SUITES.get(uuid);
		if (suite != null && !suite.isEmpty()) {
			texte.append(" ; ensuite : ");
			suite.forEach(e -> texte.append(e.action()).append(e.cible() != null ? " " + e.cible() : "").append(", "));
			texte.setLength(texte.length() - 2);
		}
		return texte.toString();
	}

	/** Le villageois s'arrête et regarde celui qui lui parle, le temps que sa réponse vienne, sans perdre ce qu'il faisait. */
	static void ecouter(final Villager villageois, final Entity qui, final long maintenant) {
		Geste actuel = GESTES.get(villageois.getUUID());
		if (actuel != null && (actuel.type.equals("frapper") || actuel.type.equals("fuir"))) {
			return;
		}
		ECOUTES.put(villageois.getUUID(), new Object[] {qui, maintenant + 7 * 20});
	}

	static void oublier(final UUID uuid) {
		SUITES.remove(uuid);
		ECOUTES.remove(uuid);
		Chantiers.oublier(uuid);
		GESTES.remove(uuid);
	}

	static void toutOublier() {
		SUITES.clear();
		ECOUTES.clear();
		GESTES.clear();
	}

	/** Applique une décision : « rien » laisse l'activité en cours se poursuivre, toute autre action la remplace. */
	static void lancer(final Villager villageois, final Ame ame, final Cerveau.Decision decision, final long maintenant) {
		ECOUTES.remove(villageois.getUUID());
		String type = normaliser(decision.action());
		String cible = decision.cible();
		String objet = decision.objet();
		java.util.List<Cerveau.Etape> suite = new java.util.ArrayList<>(decision.suite());
		// « rien, puis… » : la première étape de la suite devient l'action.
		while (type.equals("rien") && !suite.isEmpty()) {
			Cerveau.Etape premiere = suite.removeFirst();
			type = normaliser(premiere.action());
			cible = premiere.cible();
			objet = premiere.objet();
		}
		if (type.equals("rien")) {
			// Un chantier laissé en plan (redémarrage du serveur, interruption) reprend de lui-même.
			if (!GESTES.containsKey(villageois.getUUID()) && ame.chantier != null) {
				demarrer(villageois, ame, "batir", null, null, maintenant);
			}
			return;
		}
		if (ame.maitre != null && (type.equals("rester") || (type.equals("suivre") && ame.maitre.equalsIgnoreCase(cible)))) {
			ame.suit = type.equals("suivre");
			abandonner(villageois);
			return;
		}
		if (type.equals("rester")) {
			// Sans maître, rester sur place n'est rien de plus que continuer.
			return;
		}
		abandonner(villageois);
		if (type.equals("arreter")) {
			if (ame.chantier != null) {
				ame.noter("Tu as abandonné le chantier « " + ame.chantier.nom + " » : il restera inachevé.");
				ame.chantier = null;
			}
			return;
		}
		if (demarrer(villageois, ame, type, cible, objet, maintenant) && !suite.isEmpty()) {
			SUITES.put(villageois.getUUID(), new java.util.ArrayDeque<>(suite));
		}
	}

	private static String normaliser(final String action) {
		return switch (action == null ? "rien" : action) {
			case "offrir" -> "donner";
			case "attaquer", "taper", "chasser", "tuer", "abattre_bete" -> "frapper";
			case "creuser_mine", "extraire", "piocher_minerai", "prospecter" -> "miner";
			case "recolter", "récolter", "moissonner", "cueillir" -> "recolter";
			case "ranger", "déposer", "stocker" -> "deposer";
			case "collecter" -> "ramasser";
			case "se_nourrir" -> "manger";
			case "attendre_ici", "reste", "rester_ici" -> "rester";
			case "voler" -> "prendre";
			case "accompagner" -> "suivre";
			case "abattre", "bucheronner", "bûcheronner", "couper_arbre" -> "couper";
			case "creuser", "piocher" -> "casser";
			case "becher", "bêcher", "biner" -> "labourer";
			case "construire", "bâtir", "aider" -> "batir";
			case "crafter", "cuisiner", "forger", "confectionner", "creer", "créer" -> "fabriquer";
			case "écrire", "pancarte", "afficher" -> "ecrire";
			case "actionner", "ouvrir", "fermer", "sonner", "activer" -> "utiliser";
			case "feter", "fêter", "celebrer", "célébrer", "sauter" -> "danser";
			case "arrêter", "stop", "abandonner" -> "arreter";
			case "travailler", "dormir", "attendre", "parler", "observer", "continuer" -> "rien";
			default -> action;
		};
	}

	/** Lance un geste. Si c'est impossible, le villageois l'apprend à sa prochaine réflexion. @return vrai si le geste a démarré. */
	private static boolean demarrer(final Villager villageois, final Ame ame, final String type, final String cible, final String objet, final long maintenant) {
		HameauConfig.Autonomie autonomie = HameauConfig.get().autonomie;
		boolean permis = switch (type) {
			case "aller", "suivre", "fuir", "donner" -> true;
			case "frapper" -> autonomie.frapper;
			case "casser", "couper", "labourer" -> autonomie.casser;
			case "poser" -> autonomie.poser;
			case "prendre" -> autonomie.prendreDansCoffres;
			case "batir" -> autonomie.batir;
			case "fabriquer", "danser", "manger", "ramasser", "recolter", "deposer" -> true;
			case "miner" -> autonomie.casser;
			case "ecrire", "utiliser" -> autonomie.poser;
			default -> false;
		};
		if (!permis) {
			ame.noter("Tu as voulu « " + type + " » mais tu n'en es pas capable.");
			return false;
		}
		ServerLevel level = (ServerLevel) villageois.level();
		Geste geste = new Geste();
		geste.type = type;
		geste.objet = objet;
		geste.fin = maintenant + 30 * 20;
		switch (type) {
			case "fabriquer" -> {
				geste.objet = objet != null ? objet : cible;
				geste.fin = maintenant + 30;
				GESTES.put(villageois.getUUID(), geste);
				return true;
			}
			case "danser" -> {
				geste.fin = maintenant + 6 * 20;
				GESTES.put(villageois.getUUID(), geste);
				return true;
			}
			case "manger" -> {
				manger(villageois, ame);
				return false;
			}
			case "ramasser", "recolter" -> {
				geste.fin = maintenant + 90 * 20;
				GESTES.put(villageois.getUUID(), geste);
				return true;
			}
			case "miner" -> {
				String quoi = (cible == null ? "" : cible) + " " + (objet == null || objet.equals(cible) ? "" : objet);
				java.util.regex.Matcher nombre = java.util.regex.Pattern.compile("\\b(\\d{1,2})\\b").matcher(TROIS_NOMBRES.matcher(quoi).replaceAll(""));
				geste.objectif = nombre.find() ? Math.clamp(Integer.parseInt(nombre.group(1)), 1, 16) : 6;
				geste.nomRessource = ressource(quoi, geste);
				geste.fin = maintenant + 8 * 60 * 20;
				if (villageois.isSleeping()) {
					villageois.stopSleeping();
				}
				GESTES.put(villageois.getUUID(), geste);
				return true;
			}
			case "ecrire" -> {
				geste.pos = coordonnees(cible);
				if (geste.pos == null) {
					// Sans emplacement donné : juste devant lui.
					geste.pos = villageois.blockPosition().relative(villageois.getDirection());
				}
			}
			case "utiliser" -> geste.pos = coordonnees(cible);
			case "batir" -> {
				Entity aide = personne(villageois, level, cible);
				Ame aidee = aide instanceof Villager autre ? Ames.de(autre) : null;
				if (aidee != null && (aidee.chantier != null || aidee.planEnCours)) {
					geste.proprietaire = aidee;
					ame.noter("Tu vas prêter main-forte à " + aidee.nom + " sur son chantier.");
				} else if (ame.planEnCours || ame.chantier != null) {
					// Un chantier ouvert se reprend, même s'il le redécrit : pour bâtir autre chose ou ailleurs, il doit d'abord l'abandonner (« arreter »).
					geste.proprietaire = ame;
				} else {
					BlockPos centre = coordonnees(cible != null ? cible : objet);
					if (centre == null) {
						centre = villageois.blockPosition().relative(villageois.getDirection(), 6);
					}
					if (!centre.closerThan(villageois.blockPosition(), 64)) {
						ame.noter("Tu as voulu bâtir en " + Perception.coord(centre) + " mais c'est trop loin d'ici.");
						return false;
					}
					String description = objet == null ? "" : TROIS_NOMBRES.matcher(objet).replaceAll("").trim();
					Chantiers.commander(villageois, ame, centre, description.isEmpty() ? "une petite maison à son goût" : description, maintenant);
					geste.proprietaire = ame;
				}
				geste.fin = maintenant + 20 * 60 * 20;
				if (villageois.isSleeping()) {
					villageois.stopSleeping();
				}
				GESTES.put(villageois.getUUID(), geste);
				return true;
			}
			case "fuir" -> {
				geste.cible = personne(villageois, level, cible);
				if (geste.cible == null && lieu(villageois, cible) != null) {
					// « fuir vers la maison » : c'est s'y rendre.
					geste.type = "aller";
					geste.pos = lieu(villageois, cible);
				}
			}
			case "suivre", "donner" -> geste.cible = personne(villageois, level, cible);
			case "frapper" -> {
				geste.cible = personne(villageois, level, cible);
				if (geste.cible == null) {
					geste.cible = bete(villageois, level, cible);
				}
			}
			case "casser", "couper", "labourer", "poser", "prendre", "deposer" -> geste.pos = coordonnees(cible);
			default -> {
				geste.pos = lieu(villageois, cible);
				if (geste.pos == null) {
					geste.cible = personne(villageois, level, cible);
				}
			}
		}
		if (geste.cible == null && geste.pos == null) {
			ame.noter("Tu as voulu " + type + " « " + cible + " » mais tu ne l'as pas trouvé.");
			return false;
		}
		if (geste.pos != null && !geste.pos.closerThan(villageois.blockPosition(), 48)) {
			ame.noter("Tu as voulu " + type + " en " + cible + " mais c'est trop loin.");
			return false;
		}
		if (type.equals("suivre")) {
			geste.fin = maintenant + 45 * 20;
		} else if (type.equals("fuir")) {
			geste.fin = maintenant + 12 * 20;
		}
		if (villageois.isSleeping()) {
			villageois.stopSleeping();
		}
		GESTES.put(villageois.getUUID(), geste);
		return true;
	}

	private static Entity personne(final Villager villageois, final ServerLevel level, final String nom) {
		if (nom == null) {
			return null;
		}
		for (LivingEntity proche : level.getEntitiesOfClass(LivingEntity.class, villageois.getBoundingBox().inflate(32),
				e -> e != villageois && e.isAlive() && (e instanceof Villager || e instanceof ServerPlayer))) {
			if (Ames.simplifier(Perception.nom(proche)).equals(Ames.simplifier(nom))) {
				return proche;
			}
		}
		return null;
	}

	/** Le pied de l'arbre le plus proche, en « x y z », ou null. */
	static String arbre(final Villager villageois) {
		ServerLevel level = (ServerLevel) villageois.level();
		BlockPos centre = villageois.blockPosition();
		BlockPos tronc = null;
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-16, -3, -16), centre.offset(16, 6, 16))) {
			if (level.getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS) && !level.getBlockState(pos.below()).is(net.minecraft.tags.BlockTags.LOGS)
					&& level.getBlockState(pos.above()).is(net.minecraft.tags.BlockTags.LOGS) && (tronc == null || pos.distSqr(centre) < tronc.distSqr(centre))) {
				tronc = pos.immutable();
			}
		}
		return tronc == null ? null : Perception.coord(tronc);
	}

	/** Le coffre (ou tonneau) le plus proche, en « x y z », ou null. */
	static String coffre(final Villager villageois) {
		ServerLevel level = (ServerLevel) villageois.level();
		BlockPos centre = villageois.blockPosition();
		BlockPos proche = null;
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-12, -4, -12), centre.offset(12, 4, 12))) {
			if (level.getBlockState(pos).hasBlockEntity() && level.getBlockEntity(pos) instanceof Container && (proche == null || pos.distSqr(centre) < proche.distSqr(centre))) {
				proche = pos.immutable();
			}
		}
		return proche == null ? null : Perception.coord(proche);
	}

	/** L'espèce de la bête la plus proche, ou null. */
	static String gibier(final Villager villageois) {
		net.minecraft.world.entity.animal.Animal proche = null;
		for (net.minecraft.world.entity.animal.Animal bete : villageois.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, villageois.getBoundingBox().inflate(24), Entity::isAlive)) {
			if (proche == null || villageois.distanceToSqr(bete) < villageois.distanceToSqr(proche)) {
				proche = bete;
			}
		}
		return proche == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(proche.getType()).getPath();
	}

	private static final Map<String, String> ESPECES = Map.ofEntries(Map.entry("vache", "cow"), Map.entry("mouton", "sheep"), Map.entry("cochon", "pig"), Map.entry("porc", "pig"),
			Map.entry("poule", "chicken"), Map.entry("poulet", "chicken"), Map.entry("lapin", "rabbit"), Map.entry("cheval", "horse"), Map.entry("loup", "wolf"), Map.entry("renard", "fox"),
			Map.entry("chevre", "goat"), Map.entry("squelette", "skeleton"), Map.entry("araignee", "spider"), Map.entry("sorciere", "witch"), Map.entry("pillard", "pillager"), Map.entry("noye", "drowned"));

	/** La bête ou le monstre le plus proche de cette espèce (« vache », « Cow », « zombie »…). */
	private static Entity bete(final Villager villageois, final ServerLevel level, final String espece) {
		if (espece == null) {
			return null;
		}
		String cherche = Ames.simplifier(espece).replaceAll("^(un|une|le|la|les|des|ce|cette) ", "").replaceAll("s$", "");
		String id = ESPECES.getOrDefault(cherche, cherche).replace(' ', '_');
		Entity proche = null;
		for (LivingEntity candidat : level.getEntitiesOfClass(LivingEntity.class, villageois.getBoundingBox().inflate(24),
				e -> e.isAlive() && !(e instanceof Villager) && !(e instanceof ServerPlayer) && !(e instanceof net.minecraft.world.entity.decoration.Mannequin))) {
			String chemin = BuiltInRegistries.ENTITY_TYPE.getKey(candidat.getType()).getPath();
			if ((chemin.equals(id) || Ames.simplifier(candidat.getType().getDescription().getString()).equals(cherche)) && (proche == null || villageois.distanceToSqr(candidat) < villageois.distanceToSqr(proche))) {
				proche = candidat;
			}
		}
		return proche;
	}

	private static final Map<String, String> MINERAIS = Map.ofEntries(Map.entry("charbon", "coal_ore"), Map.entry("fer", "iron_ore"), Map.entry("cuivre", "copper_ore"), Map.entry("or", "gold_ore"),
			Map.entry("diamant", "diamond_ore"), Map.entry("emeraude", "emerald_ore"), Map.entry("redstone", "redstone_ore"), Map.entry("lapis", "lapis_ore"), Map.entry("sable", "sand"),
			Map.entry("gravier", "gravel"), Map.entry("argile", "clay"), Map.entry("coal", "coal_ore"), Map.entry("iron", "iron_ore"), Map.entry("copper", "copper_ore"), Map.entry("gold", "gold_ore"),
			Map.entry("diamond", "diamond_ore"), Map.entry("emerald", "emerald_ore"));

	/** Comprend ce que le villageois veut extraire, règle le filtre du geste et renvoie le nom à lui redire. */
	private static String ressource(final String demande, final Geste geste) {
		for (String mot : Ames.simplifier(demande).split("[^a-z_]+")) {
			String sans = mot.replaceAll("s$", "");
			if (sans.matches("pierre|roche|caillou|stone|cobblestone")) {
				geste.ressource = etat -> etat.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD);
				return "pierre";
			}
			String cle = MINERAIS.getOrDefault(sans, sans.endsWith("_ore") || BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.Identifier.withDefaultNamespace(sans)) ? sans : null);
			if (cle != null && sans.length() > 1) {
				geste.ressource = etat -> BuiltInRegistries.BLOCK.getKey(etat.getBlock()).getPath().endsWith(cle);
				return sans;
			}
		}
		geste.ressource = etat -> BuiltInRegistries.BLOCK.getKey(etat.getBlock()).getPath().endsWith("_ore");
		return "minerai";
	}

	private static final java.util.regex.Pattern TROIS_NOMBRES = java.util.regex.Pattern.compile("(-?\\d+)[ ,;]+(-?\\d+)[ ,;]+(-?\\d+)");

	/** Lit « x y z », même noyé dans du texte (« Chest en -541 71 66 »). */
	private static BlockPos coordonnees(final String texte) {
		if (texte == null) {
			return null;
		}
		java.util.regex.Matcher m = TROIS_NOMBRES.matcher(texte);
		return m.find() ? new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))) : null;
	}

	private static BlockPos lieu(final Villager villageois, final String texte) {
		if (texte == null) {
			return null;
		}
		var memoire = switch (texte.trim().toLowerCase()) {
			case "maison" -> MemoryModuleType.HOME;
			case "travail" -> MemoryModuleType.JOB_SITE;
			case "place" -> MemoryModuleType.MEETING_POINT;
			default -> null;
		};
		if (memoire != null) {
			return villageois.getBrain().getMemory(memoire).map(p -> p.pos()).orElse(null);
		}
		return coordonnees(texte);
	}

	static void tick(final MinecraftServer server) {
		long maintenant = server.getTickCount();
		if (maintenant % 4 != 0 || (GESTES.isEmpty() && ECOUTES.isEmpty())) {
			return;
		}
		ECOUTES.entrySet().removeIf(e -> {
			Villager villageois = Vie.trouver(server, e.getKey());
			Entity qui = (Entity) e.getValue()[0];
			if (villageois == null || !qui.isAlive() || maintenant > (long) e.getValue()[1]) {
				return true;
			}
			villageois.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			villageois.getNavigation().stop();
			villageois.getLookControl().setLookAt(qui);
			return false;
		});
		for (UUID uuid : new java.util.ArrayList<>(GESTES.keySet())) {
			if (ECOUTES.containsKey(uuid)) {
				continue;
			}
			Geste geste = GESTES.get(uuid);
			Villager villageois = Vie.trouver(server, uuid);
			Ame ame = Ames.connue(uuid);
			if (villageois == null || ame == null || !villageois.isAlive()) {
				GESTES.remove(uuid);
				SUITES.remove(uuid);
				continue;
			}
			boolean fini;
			try {
				fini = poursuivre(villageois, ame, geste, maintenant);
			} catch (RuntimeException e) {
				Hameau.LOGGER.error("Hameau : geste interrompu pour {}", ame.nom, e);
				fini = true;
				SUITES.remove(uuid);
			}
			if (!fini || GESTES.get(uuid) != geste) {
				continue;
			}
			GESTES.remove(uuid);
			Corps.relacher(villageois);
			// Étape suivante du plan, s'il y en a une ; un échec interrompt le plan.
			java.util.ArrayDeque<Cerveau.Etape> suite = SUITES.get(uuid);
			if (suite != null && !suite.isEmpty()) {
				Cerveau.Etape etape = suite.poll();
				String type = normaliser(etape.action());
				if (type.equals("rien") || type.equals("arreter") || !demarrer(villageois, ame, type, etape.cible(), etape.objet(), maintenant)) {
					SUITES.remove(uuid);
				}
			} else {
				SUITES.remove(uuid);
			}
		}
	}

	private static void marcher(final Villager villageois, final Vec3 vers, final float vitesse, final int assezPres) {
		villageois.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(vers, vitesse, assezPres));
	}

	/** @return vrai quand le geste est terminé (réussi, raté ou périmé). */
	private static boolean poursuivre(final Villager villageois, final Ame ame, final Geste geste, final long maintenant) {
		ServerLevel level = (ServerLevel) villageois.level();
		boolean perime = maintenant > geste.fin;
		if (geste.cible != null && (!geste.cible.isAlive() || geste.cible.level() != level)) {
			return true;
		}
		String nomCible = geste.cible != null ? Perception.nom(geste.cible) : null;
		switch (geste.type) {
			case "batir" -> {
				return perime || Chantiers.avancer(villageois, ame, geste.proprietaire, level, maintenant);
			}
			case "fabriquer" -> {
				villageois.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
				Corps.balancer(villageois);
				if (perime) {
					fabriquer(villageois, ame, geste.objet);
				}
				return perime;
			}
			case "danser" -> {
				villageois.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
				villageois.getJumpControl().jump();
				Corps.balancer(villageois);
				if (maintenant % 16 == 0) {
					Corps.accroupir(villageois, 4);
					level.sendParticles(net.minecraft.core.particles.ParticleTypes.NOTE, villageois.getX(), villageois.getY() + 2.2, villageois.getZ(), 2, 0.3, 0.1, 0.3, 0.5);
				}
				if (perime) {
					ame.noter("Tu as dansé un moment.");
					Vie.temoins(villageois, null, "Tu as vu " + ame.nom + " se mettre à danser.", false);
				}
				return perime;
			}
			case "miner" -> {
				return miner(villageois, ame, geste, level, maintenant, perime);
			}
			case "recolter" -> {
				return recolter(villageois, ame, geste, level, perime);
			}
			case "ramasser" -> {
				return ramasser(villageois, ame, geste, level, perime);
			}
			case "aller" -> {
				Vec3 but = geste.cible != null ? geste.cible.position() : Vec3.atBottomCenterOf(geste.pos);
				if (villageois.position().closerThan(but, 2.5)) {
					ame.noter("Tu es arrivé là où tu voulais aller" + (nomCible != null ? ", près de " + nomCible : "") + ".");
					return true;
				}
				marcher(villageois, but, PAS, 1);
				return perime;
			}
			case "suivre" -> {
				if (villageois.distanceTo(geste.cible) > 3) {
					marcher(villageois, geste.cible.position(), PAS, 2);
				}
				return perime;
			}
			case "fuir" -> {
				Vec3 ecart = villageois.position().subtract(geste.cible.position());
				Vec3 direction = ecart.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : ecart.normalize();
				BlockPos refuge = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(villageois.position().add(direction.scale(10))));
				marcher(villageois, Vec3.atBottomCenterOf(refuge), COURSE, 1);
				return perime;
			}
			case "donner" -> {
				if (villageois.distanceTo(geste.cible) > 3) {
					marcher(villageois, geste.cible.position(), PAS, 2);
					if (perime) {
						ame.noter("Tu n'as pas réussi à rejoindre " + nomCible + " pour lui donner quelque chose.");
					}
					return perime;
				}
				ItemStack pile = retirer(villageois.getInventory(), geste.objet, false);
				if (pile.isEmpty()) {
					ame.noter("Tu voulais donner « " + geste.objet + " » à " + nomCible + " mais tu n'en as pas.");
					return true;
				}
				String quoi = Perception.objet(pile);
				Corps.tenir(villageois, pile);
				Corps.balancer(villageois);
				if (geste.cible instanceof ServerPlayer joueur) {
					if (!joueur.getInventory().add(pile)) {
						joueur.spawnAtLocation(level, pile);
					}
					Vie.annoncer(villageois, ame.nom + " te donne " + quoi + ".", joueur);
				} else if (geste.cible instanceof Villager autre) {
					ItemStack reste = autre.getInventory().addItem(pile);
					if (!reste.isEmpty()) {
						autre.spawnAtLocation(level, reste);
					}
					Ame recevant = Ames.de(autre);
					recevant.noter(ame.nom + " t'a donné " + quoi + ".");
					recevant.ajusterRelation(ame.nom, 6);
				}
				ame.noter("Tu as donné " + quoi + " à " + nomCible + ".");
				Vie.temoins(villageois, geste.cible, "Tu as vu " + ame.nom + " donner " + quoi + " à " + nomCible + ".", false);
				return true;
			}
			case "frapper" -> {
				if (villageois.distanceTo(geste.cible) > 2.6) {
					marcher(villageois, geste.cible.position(), COURSE, 1);
					if (perime) {
						ame.noter("Tu n'as pas réussi à rattraper " + nomCible + ".");
					}
					return perime;
				}
				if (maintenant >= geste.prochainCoup) {
					HameauConfig.Autonomie autonomie = HameauConfig.get().autonomie;
					villageois.getLookControl().setLookAt(geste.cible);
					Corps.balancer(villageois);
					boolean gibier = !(geste.cible instanceof Villager) && !(geste.cible instanceof ServerPlayer);
					if (gibier) {
						Corps.tenir(villageois, new ItemStack(net.minecraft.world.item.Items.IRON_AXE));
					}
					if (!geste.cible.hurtServer(level, level.damageSources().mobAttack(villageois), gibier ? Math.max(4F, autonomie.degatsParCoup) : autonomie.degatsParCoup)) {
						ame.noter("Tu as frappé " + nomCible + " mais tes coups ne lui font rien du tout : inutile d'insister.");
						return true;
					}
					geste.coups++;
					geste.prochainCoup = maintenant + 20;
					if (gibier && !geste.cible.isAlive()) {
						// Ce que la bête laisse va dans ses poches.
						java.util.List<String> butin = new java.util.ArrayList<>();
						for (net.minecraft.world.entity.item.ItemEntity tombe : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, geste.cible.getBoundingBox().inflate(2.5), Entity::isAlive)) {
							butin.add(Perception.objet(tombe.getItem()));
							empocher(villageois, level, tombe);
						}
						ame.noter("Tu as tué : " + nomCible + (butin.isEmpty() ? "." : " et ramassé " + String.join(", ", butin) + "."));
						Vie.temoins(villageois, null, "Tu as vu " + ame.nom + " tuer : " + nomCible + ".", false);
						return true;
					}
					if (geste.coups >= (gibier ? 15 : autonomie.coupsMax)) {
						if (!gibier) {
							Ames.journal(ame, level, ame.nom + " a frappé " + nomCible + ".");
						}
						ame.noter("Tu as frappé " + nomCible + " (" + geste.coups + " coups).");
						return true;
					}
				}
				if (perime && geste.coups > 0) {
					ame.noter("Tu as frappé " + nomCible + ".");
				}
				return perime;
			}
			default -> {
				Vec3 but = Vec3.atCenterOf(geste.pos);
				double dx = villageois.getX() - but.x;
				double dz = villageois.getZ() - but.z;
				// Pour un arbre, seule compte la distance au pied : on abat les bûches du dessus sans grimper.
				boolean aPortee = geste.type.equals("couper") ? dx * dx + dz * dz < 9 && Math.abs(villageois.getY() - but.y) < 9 : villageois.position().closerThan(but, 4.5);
				if (!aPortee) {
					if (geste.duree > 0) {
						level.destroyBlockProgress(villageois.getId(), geste.pos, -1);
						geste.duree = 0;
						geste.avancement = 0;
					}
					marcher(villageois, but, PAS, 2);
					if (perime) {
						ame.noter("Tu n'as pas réussi à atteindre " + Perception.coord(geste.pos) + ".");
					}
					return perime;
				}
				villageois.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
				villageois.getNavigation().stop();
				villageois.getLookControl().setLookAt(but);
				return switch (geste.type) {
					case "ecrire" -> {
						ecrire(villageois, ame, geste, level);
						yield true;
					}
					case "utiliser" -> {
						utiliser(villageois, ame, geste, level);
						yield true;
					}
					case "casser", "couper" -> ouvrager(villageois, ame, geste, level, maintenant);
					case "labourer" -> labourer(villageois, ame, geste, level, maintenant);
					default -> {
						Corps.balancer(villageois);
						agirSurBloc(villageois, ame, geste, level);
						yield true;
					}
				};
			}
		}
	}

	private static final String[] INFABRICABLES = {"command", "bedrock", "barrier", "spawn_egg", "spawner", "tnt", "end_crystal", "debug", "structure", "jigsaw", "netherite", "diamond",
		"emerald", "elytra", "totem", "beacon", "nether_star", "shulker", "enchanted", "dragon", "lava", "wither", "trial_key", "mace", "light"};

	/** Fabrique un objet de ses mains : n'importe quoi de raisonnable, d'après son nom ou son identifiant. */
	private static void fabriquer(final Villager villageois, final Ame ame, final String demande) {
		String voulu = demande == null ? "" : Ames.simplifier(demande).replace("minecraft:", "");
		int quantite = 1;
		java.util.regex.Matcher nombre = java.util.regex.Pattern.compile("^(\\d+)\\s*x?\\s+(.+)$").matcher(voulu);
		if (nombre.matches()) {
			quantite = Math.clamp(Integer.parseInt(nombre.group(1)), 1, 8);
			voulu = nombre.group(2);
		}
		String identifiant = voulu.replace(' ', '_');
		net.minecraft.world.item.Item objet = null;
		net.minecraft.resources.Identifier cle = net.minecraft.resources.Identifier.tryParse("minecraft:" + identifiant);
		if (cle != null) {
			objet = BuiltInRegistries.ITEM.getOptional(cle).orElse(null);
		}
		if (objet == null) {
			for (net.minecraft.world.item.Item candidat : BuiltInRegistries.ITEM) {
				if (Ames.simplifier(new ItemStack(candidat).getHoverName().getString()).equals(voulu)) {
					objet = candidat;
					break;
				}
			}
		}
		String chemin = objet == null ? "" : BuiltInRegistries.ITEM.getKey(objet).getPath();
		boolean interdit = objet == null || objet == net.minecraft.world.item.Items.AIR;
		for (String mot : INFABRICABLES) {
			interdit |= chemin.contains(mot);
		}
		if (interdit) {
			ame.noter("Tu ne sais pas fabriquer « " + demande + " ». (Donne l'identifiant Minecraft en anglais, ex. bread, cake, oak_sign, torch.)");
			return;
		}
		ItemStack pile = new ItemStack(objet, Math.min(quantite, objet.getDefaultMaxStackSize()));
		String quoi = Perception.objet(pile);
		Corps.tenir(villageois, pile);
		Corps.balancer(villageois);
		ItemStack reste = villageois.getInventory().addItem(pile);
		if (!reste.isEmpty()) {
			villageois.spawnAtLocation((ServerLevel) villageois.level(), reste);
		}
		villageois.level().playSound(null, villageois.blockPosition(), net.minecraft.sounds.SoundEvents.VILLAGER_WORK_TOOLSMITH, net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F, 1.1F);
		ame.noter("Tu as fabriqué : " + quoi + ".");
		Vie.temoins(villageois, null, "Tu as vu " + ame.nom + " fabriquer : " + quoi + ".", false);
	}

	/** Plante une pancarte et y écrit quelques mots : message, enseigne, avertissement, moquerie… */
	private static void ecrire(final Villager villageois, final Ame ame, final Geste geste, final ServerLevel level) {
		BlockPos pos = geste.pos;
		for (int essai = 0; essai < 3 && !level.getBlockState(pos).canBeReplaced(); essai++) {
			pos = pos.above();
		}
		String texte = geste.objet == null ? "" : TROIS_NOMBRES.matcher(geste.objet).replaceAll("").trim();
		if (texte.isEmpty() || !level.getBlockState(pos).canBeReplaced() || !level.getBlockState(pos.below()).isSolid()) {
			ame.noter("Tu n'as pas pu planter de pancarte en " + Perception.coord(geste.pos) + ".");
			return;
		}
		// Quatre lignes d'une quinzaine de caractères.
		java.util.List<String> lignes = new java.util.ArrayList<>();
		StringBuilder ligne = new StringBuilder();
		for (String mot : texte.split("\\s+")) {
			if (ligne.length() + mot.length() + 1 > 15 && !ligne.isEmpty()) {
				lignes.add(ligne.toString());
				ligne = new StringBuilder();
			}
			ligne.append(ligne.isEmpty() ? "" : " ").append(mot);
		}
		lignes.add(ligne.toString());
		while (lignes.size() < 4) {
			lignes.add("");
		}
		int versLui = Math.floorMod(Math.round((villageois.getYRot() + 180F) * 16F / 360F), 16);
		level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16, versLui));
		StringBuilder messages = new StringBuilder();
		for (int i = 0; i < 4; i++) {
			messages.append(i == 0 ? "" : ",").append('"').append(lignes.get(i).replace("\\", "").replace("\"", "'")).append('"');
		}
		level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
				"data merge block " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " {front_text:{messages:[" + messages + "]}}");
		Corps.balancer(villageois);
		level.playSound(null, pos, net.minecraft.sounds.SoundEvents.WOOD_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 1F, 1F);
		String ou = Perception.coord(pos);
		ame.noter("Tu as planté une pancarte en " + ou + " : « " + texte + " »");
		Vie.temoins(villageois, null, ame.nom + " a planté une pancarte en " + ou + " où l'on lit : « " + texte + " »", false);
		Ames.journal(ame, level, ame.nom + " a planté une pancarte en " + ou + " : « " + texte + " »");
	}

	/** Actionne ce qui peut l'être : porte, trappe, portillon, cloche, levier. */
	private static void utiliser(final Villager villageois, final Ame ame, final Geste geste, final ServerLevel level) {
		BlockPos pos = geste.pos;
		BlockState etat = level.getBlockState(pos);
		String nomBloc = etat.getBlock().getName().getString();
		Corps.balancer(villageois);
		if (etat.getBlock() instanceof net.minecraft.world.level.block.BellBlock cloche) {
			cloche.attemptToRing(villageois, level, pos, villageois.getDirection().getOpposite());
			ame.noter("Tu as sonné la cloche.");
			Vie.temoins(villageois, null, ame.nom + " vient de sonner la cloche du village.", true);
		} else if (etat.getBlock() instanceof net.minecraft.world.level.block.DoorBlock porte) {
			porte.setOpen(villageois, level, etat, pos, !porte.isOpen(etat));
			ame.noter("Tu as " + (porte.isOpen(etat) ? "fermé" : "ouvert") + " la porte en " + Perception.coord(pos) + ".");
		} else if (etat.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
			level.setBlockAndUpdate(pos, etat.cycle(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN));
			ame.noter("Tu as actionné : " + nomBloc + " en " + Perception.coord(pos) + ".");
		} else if (etat.getBlock() instanceof net.minecraft.world.level.block.LeverBlock) {
			level.setBlockAndUpdate(pos, etat.cycle(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED));
			level.updateNeighborsAt(pos, etat.getBlock());
			ame.noter("Tu as basculé le levier en " + Perception.coord(pos) + ".");
		} else {
			ame.noter("Il n'y a rien à actionner en " + Perception.coord(pos) + " (" + nomBloc + ").");
		}
	}

	private static net.minecraft.world.item.Item outilPour(final BlockState etat) {
		if (etat.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)) {
			return net.minecraft.world.item.Items.IRON_AXE;
		}
		if (etat.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) {
			return net.minecraft.world.item.Items.IRON_PICKAXE;
		}
		if (etat.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) {
			return net.minecraft.world.item.Items.IRON_SHOVEL;
		}
		if (etat.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_HOE)) {
			return net.minecraft.world.item.Items.IRON_HOE;
		}
		return net.minecraft.world.item.Items.AIR;
	}

	/** Casse un bloc à la main ou à l'outil, coup après coup, fissures visibles ; « couper » enchaîne sur tout le tronc. */
	private static boolean ouvrager(final Villager villageois, final Ame ame, final Geste geste, final ServerLevel level, final long maintenant) {
		BlockPos pos = geste.pos;
		BlockState etat = level.getBlockState(pos);
		String nomBloc = etat.getBlock().getName().getString();
		boolean arbre = geste.type.equals("couper");
		if (geste.duree == 0) {
			float durete = etat.getDestroySpeed(level, pos);
			if (etat.isAir() || durete < 0 || etat.hasBlockEntity() || (arbre && !etat.is(net.minecraft.tags.BlockTags.LOGS))) {
				ame.noter(arbre ? "Il n'y a pas de tronc à couper en " + Perception.coord(pos) + "." : "Tu n'as pas pu casser ce qu'il y a en " + Perception.coord(pos) + ".");
				return true;
			}
			geste.duree = Math.clamp((long) (durete * 24), 16, 80);
			geste.avancement = 0;
			Corps.tenir(villageois, new ItemStack(outilPour(etat)));
		}
		geste.fin = Math.max(geste.fin, maintenant + 60);
		geste.avancement += 4;
		Corps.balancer(villageois);
		level.playSound(null, pos, etat.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 0.8F);
		level.destroyBlockProgress(villageois.getId(), pos, Math.min(9, geste.avancement * 10 / geste.duree));
		if (geste.avancement < geste.duree) {
			return false;
		}
		level.destroyBlockProgress(villageois.getId(), pos, -1);
		// Ce que le bloc donne va dans ses poches ; le surplus tombe au sol.
		for (ItemStack butin : net.minecraft.world.level.block.Block.getDrops(etat, level, pos, null, villageois, new ItemStack(outilPour(etat)))) {
			ItemStack reste = villageois.getInventory().addItem(butin);
			if (!reste.isEmpty()) {
				villageois.spawnAtLocation(level, reste);
			}
		}
		level.destroyBlock(pos, false, villageois, 512);
		geste.blocsFaits++;
		geste.duree = 0;
		if (arbre && geste.blocsFaits < 10 && level.getBlockState(pos.above()).is(net.minecraft.tags.BlockTags.LOGS)) {
			geste.pos = pos.above();
			return false;
		}
		String ou = Perception.coord(pos);
		if (arbre) {
			ame.noter("Tu as abattu un arbre : " + geste.blocsFaits + " bûches de plus dans tes poches.");
			Vie.temoins(villageois, null, "Tu as vu " + ame.nom + " abattre un arbre à la hache.", false);
		} else {
			ame.noter("Tu as cassé : " + nomBloc + " en " + ou + " et ramassé ce qu'il a donné.");
			Vie.temoins(villageois, null, "Tu as vu " + ame.nom + " casser : " + nomBloc + " en " + ou + ".", true);
		}
		return true;
	}

	/** Retourne la terre à la houe : trois coups, puis le bloc devient terre labourée. */
	private static boolean labourer(final Villager villageois, final Ame ame, final Geste geste, final ServerLevel level, final long maintenant) {
		BlockPos pos = geste.pos;
		BlockState etat = level.getBlockState(pos);
		if (geste.duree == 0) {
			if (!etat.is(net.minecraft.tags.BlockTags.DIRT) || !level.getBlockState(pos.above()).isAir()) {
				ame.noter("Impossible de labourer en " + Perception.coord(pos) + " : il faut de la terre à l'air libre.");
				return true;
			}
			geste.duree = 24;
			Corps.tenir(villageois, new ItemStack(net.minecraft.world.item.Items.IRON_HOE));
		}
		geste.fin = Math.max(geste.fin, maintenant + 60);
		geste.avancement += 4;
		Corps.balancer(villageois);
		if (geste.avancement % 8 == 0) {
			level.playSound(null, pos, net.minecraft.sounds.SoundEvents.HOE_TILL.value(), net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1F);
		}
		if (geste.avancement < geste.duree) {
			return false;
		}
		level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState());
		ame.noter("Tu as labouré la terre en " + Perception.coord(pos) + ".");
		Vie.temoins(villageois, null, "Tu as vu " + ame.nom + " labourer la terre à la houe.", false);
		return true;
	}

	private static void agirSurBloc(final Villager villageois, final Ame ame, final Geste geste, final ServerLevel level) {
		BlockPos pos = geste.pos;
		BlockState etat = level.getBlockState(pos);
		String nomBloc = etat.getBlock().getName().getString();
		String ou = Perception.coord(pos);
		switch (geste.type) {
			case "poser" -> {
				ItemStack pile = retirer(villageois.getInventory(), geste.objet, true);
				if (pile.isEmpty() || !(pile.getItem() instanceof BlockItem bloc)) {
					if (!pile.isEmpty()) {
						villageois.getInventory().addItem(pile);
					}
					ame.noter("Tu voulais poser « " + geste.objet + " » mais tu n'as pas ce bloc.");
					return;
				}
				if (!etat.canBeReplaced()) {
					villageois.getInventory().addItem(pile);
					ame.noter("Impossible de poser un bloc en " + ou + " : la place est prise.");
					return;
				}
				BlockState pose = bloc.getBlock().defaultBlockState();
				if (bloc.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
					// Une porte se pose en deux moitiés, face au villageois.
					pose = pose.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, villageois.getDirection());
					level.setBlockAndUpdate(pos.above(), pose.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
				}
				level.setBlockAndUpdate(pos, pose);
				level.playSound(null, pos, pose.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1F, 1F);
				ame.noter("Tu as posé : " + bloc.getBlock().getName().getString() + " en " + ou + ".");
				Vie.temoins(villageois, null, "Tu as vu " + ame.nom + " poser un bloc en " + ou + ".", false);
			}
			case "deposer" -> {
				if (!(level.getBlockEntity(pos) instanceof Container coffre)) {
					ame.noter("Il n'y a pas de coffre en " + ou + ".");
					return;
				}
				String voulu = geste.objet == null ? "" : TROIS_NOMBRES.matcher(geste.objet).replaceAll("").trim();
				java.util.List<String> ranges = new java.util.ArrayList<>();
				for (int tour = 0; tour < 8; tour++) {
					ItemStack pile = retirer(villageois.getInventory(), voulu, false);
					if (pile.isEmpty()) {
						break;
					}
					String quoi = Perception.objet(pile);
					int avant = pile.getCount();
					ItemStack reste = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, coffre, pile, null);
					if (!reste.isEmpty()) {
						villageois.getInventory().addItem(reste);
						if (reste.getCount() == avant) {
							break;
						}
					}
					ranges.add(quoi);
				}
				coffre.setChanged();
				ame.noter(ranges.isEmpty() ? "Tu n'avais rien à ranger dans le coffre en " + ou + " (ou il est plein)." : "Tu as rangé " + String.join(", ", ranges) + " dans : " + nomBloc + " en " + ou + ".");
				if (!ranges.isEmpty()) {
					Vie.temoins(villageois, null, "Tu as vu " + ame.nom + " ranger " + String.join(", ", ranges) + " dans un coffre en " + ou + ".", false);
				}
			}
			default -> {
				if (!(level.getBlockEntity(pos) instanceof Container coffre)) {
					ame.noter("Il n'y a pas de coffre en " + ou + ".");
					return;
				}
				for (int i = 0; i < coffre.getContainerSize(); i++) {
					ItemStack pile = coffre.getItem(i);
					if (!pile.isEmpty() && villageois.getInventory().canAddItem(pile)) {
						ItemStack prise = coffre.removeItem(i, Math.min(pile.getCount(), 8));
						String quoi = Perception.objet(prise);
						villageois.getInventory().addItem(prise);
						coffre.setChanged();
						ame.noter("Tu as pris " + quoi + " dans : " + nomBloc + " en " + ou + ".");
						Vie.temoins(villageois, null, "Tu as vu " + ame.nom + " prendre " + quoi + " dans un coffre en " + ou + ".", true);
						Ames.journal(ame, level, ame.nom + " a pris " + quoi + " dans un coffre en " + ou + ".");
						return;
					}
				}
				ame.noter("Le coffre en " + ou + " ne contient rien que tu puisses emporter.");
			}
		}
	}

	private static void empocher(final Villager villageois, final ServerLevel level, final net.minecraft.world.entity.item.ItemEntity tombe) {
		ItemStack reste = villageois.getInventory().addItem(tombe.getItem().copy());
		if (reste.isEmpty()) {
			tombe.discard();
		} else {
			tombe.setItem(reste);
		}
	}

	private static void empocher(final Villager villageois, final ServerLevel level, final BlockState etat, final BlockPos pos, final net.minecraft.world.item.Item outil) {
		for (ItemStack butin : net.minecraft.world.level.block.Block.getDrops(etat, level, pos, null, villageois, new ItemStack(outil))) {
			ItemStack reste = villageois.getInventory().addItem(butin);
			if (!reste.isEmpty()) {
				villageois.spawnAtLocation(level, reste);
			}
		}
	}

	/** Celui qui sert quelqu'un reste dans ses pas tant qu'il n'a rien d'autre à faire. */
	static void escorter(final Villager villageois, final Ame ame) {
		if (ame.maitre == null || !ame.suit || GESTES.containsKey(villageois.getUUID()) || ECOUTES.containsKey(villageois.getUUID()) || villageois.isSleeping()) {
			return;
		}
		Entity maitre = personne(villageois, (ServerLevel) villageois.level(), ame.maitre);
		if (maitre != null && villageois.distanceTo(maitre) > 4) {
			marcher(villageois, maitre.position(), villageois.distanceTo(maitre) > 10 ? COURSE : PAS, 2);
		}
	}

	private static void manger(final Villager villageois, final Ame ame) {
		SimpleContainer poches = villageois.getInventory();
		for (int i = 0; i < poches.getContainerSize(); i++) {
			ItemStack pile = poches.getItem(i);
			if (!pile.isEmpty() && pile.has(net.minecraft.core.component.DataComponents.FOOD)) {
				String quoi = pile.getHoverName().getString();
				Corps.balancer(villageois);
				poches.removeItem(i, 1);
				villageois.heal(4);
				villageois.level().playSound(null, villageois.blockPosition(), net.minecraft.sounds.SoundEvents.GENERIC_EAT.value(), net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F, 1F);
				ame.noter("Tu as mangé : " + quoi + ".");
				return;
			}
		}
		ame.noter("Tu voulais manger mais tu n'as rien de comestible en poche.");
	}

	/** Ramasse un à un les objets tombés à terre aux alentours. */
	private static boolean ramasser(final Villager villageois, final Ame ame, final Geste geste, final ServerLevel level, final boolean perime) {
		net.minecraft.world.entity.item.ItemEntity proche = null;
		for (net.minecraft.world.entity.item.ItemEntity tombe : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, villageois.getBoundingBox().inflate(10, 4, 10),
				e -> e.isAlive() && villageois.getInventory().canAddItem(e.getItem()))) {
			if (proche == null || villageois.distanceToSqr(tombe) < villageois.distanceToSqr(proche)) {
				proche = tombe;
			}
		}
		if (proche == null || perime || geste.blocsFaits >= 12) {
			ame.noter(geste.blocsFaits == 0 ? "Il n'y avait rien à ramasser par terre (ou tes poches sont pleines)." : "Tu as ramassé ce qui traînait : " + geste.objet + ".");
			return true;
		}
		if (villageois.distanceTo(proche) > 2) {
			marcher(villageois, proche.position(), PAS, 1);
			return false;
		}
		String quoi = Perception.objet(proche.getItem());
		geste.objet = geste.blocsFaits == 0 ? quoi : geste.objet + ", " + quoi;
		geste.blocsFaits++;
		Corps.accroupir(villageois, 6);
		empocher(villageois, level, proche);
		return false;
	}

	/** Moissonne les cultures mûres des alentours et ressème derrière lui. */
	private static boolean recolter(final Villager villageois, final Ame ame, final Geste geste, final ServerLevel level, final boolean perime) {
		if (geste.pos == null || !(level.getBlockState(geste.pos).getBlock() instanceof net.minecraft.world.level.block.CropBlock culture) || !culture.isMaxAge(level.getBlockState(geste.pos))) {
			geste.pos = null;
			BlockPos centre = villageois.blockPosition();
			for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-9, -2, -9), centre.offset(9, 2, 9))) {
				BlockState etat = level.getBlockState(pos);
				if (etat.getBlock() instanceof net.minecraft.world.level.block.CropBlock champ && champ.isMaxAge(etat) && (geste.pos == null || pos.distSqr(centre) < geste.pos.distSqr(centre))) {
					geste.pos = pos.immutable();
				}
			}
		}
		if (geste.pos == null || perime || geste.blocsFaits >= 24) {
			ame.noter(geste.blocsFaits == 0 ? "Il n'y a aucune culture mûre à récolter par ici." : "Tu as moissonné " + geste.blocsFaits + " plants et ressemé derrière toi ; la récolte est dans tes poches.");
			if (geste.blocsFaits > 0) {
				Vie.temoins(villageois, null, "Tu as vu " + ame.nom + " faire la moisson.", false);
			}
			return true;
		}
		if (!villageois.position().closerThan(Vec3.atCenterOf(geste.pos), 2.5)) {
			marcher(villageois, Vec3.atCenterOf(geste.pos), PAS, 1);
			return false;
		}
		BlockState etat = level.getBlockState(geste.pos);
		villageois.getLookControl().setLookAt(Vec3.atCenterOf(geste.pos));
		Corps.tenir(villageois, new ItemStack(net.minecraft.world.item.Items.IRON_HOE));
		Corps.balancer(villageois);
		empocher(villageois, level, etat, geste.pos, net.minecraft.world.item.Items.IRON_HOE);
		level.playSound(null, geste.pos, etat.getSoundType().getBreakSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1F);
		level.setBlockAndUpdate(geste.pos, etat.getBlock().defaultBlockState());
		geste.blocsFaits++;
		geste.pos = null;
		return false;
	}

	private static BlockPos filon(final ServerLevel level, final BlockPos centre, final java.util.function.Predicate<BlockState> ressource, final int rayon, final int dessous) {
		BlockPos proche = null;
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-rayon, -dessous, -rayon), centre.offset(rayon, 8, rayon))) {
			if (pos.getY() > level.getMinY() + 4 && ressource.test(level.getBlockState(pos)) && (proche == null || pos.distSqr(centre) < proche.distSqr(centre))) {
				proche = pos.immutable();
			}
		}
		return proche;
	}

	private static boolean lave(final ServerLevel level, final BlockPos pos) {
		for (net.minecraft.core.Direction cote : net.minecraft.core.Direction.values()) {
			if (level.getFluidState(pos.relative(cote)).is(net.minecraft.tags.FluidTags.LAVA)) {
				return true;
			}
		}
		return level.getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA);
	}

	private static boolean finMine(final Villager villageois, final Ame ame, final Geste geste, final ServerLevel level, final String raison) {
		if (geste.duree > 0 && geste.pos != null) {
			level.destroyBlockProgress(villageois.getId(), geste.pos, -1);
		}
		String bilan = geste.blocsFaits > 0 ? "Tu as extrait " + geste.blocsFaits + " blocs de " + geste.nomRessource + " : c'est dans tes poches." : "Tu n'as rien extrait.";
		ame.noter("Mine : " + raison + " " + bilan + (geste.galerie > 3 ? " Ta galerie s'arrête en " + Perception.coord(villageois.blockPosition()) + "." : ""));
		if (geste.blocsFaits > 0) {
			Vie.temoins(villageois, null, "Tu as vu " + ame.nom + " revenir de la mine avec " + geste.blocsFaits + " blocs de " + geste.nomRessource + ".", false);
			Ames.journal(ame, level, ame.nom + " est revenu de la mine avec " + geste.blocsFaits + " blocs de " + geste.nomRessource + ".");
		}
		return true;
	}

	/**
	 * Va au filon le plus proche en creusant sa galerie : un escalier quand il faut descendre ou monter, une torche de loin en loin,
	 * un pont de pierre au-dessus du vide, et demi-tour devant la lave.
	 */
	private static boolean miner(final Villager villageois, final Ame ame, final Geste geste, final ServerLevel level, final long maintenant, final boolean perime) {
		if (perime) {
			return finMine(villageois, ame, geste, level, "le temps a passé, tu arrêtes là.");
		}
		// 1. Le bloc en cours de casse.
		if (geste.pos != null) {
			BlockState etat = level.getBlockState(geste.pos);
			if (etat.isAir() || (!geste.surFilon && etat.canBeReplaced() && etat.getFluidState().isEmpty())) {
				if (!etat.isAir()) {
					level.destroyBlock(geste.pos, false, villageois, 512);
				}
				geste.pos = null;
				geste.duree = 0;
				return false;
			}
			if (etat.getDestroySpeed(level, geste.pos) < 0 || etat.hasBlockEntity() || !etat.getFluidState().isEmpty() || lave(level, geste.pos)) {
				return finMine(villageois, ame, geste, level, etat.getFluidState().isEmpty() && !lave(level, geste.pos) ? "quelque chose d'incassable barre le passage." : "de l'eau ou de la lave barre le passage, tu renonces par prudence.");
			}
			if (geste.duree == 0) {
				geste.duree = Math.clamp((long) (etat.getDestroySpeed(level, geste.pos) * 14), 8, 48);
				geste.avancement = 0;
				Corps.tenir(villageois, new ItemStack(outilPour(etat) == net.minecraft.world.item.Items.AIR ? net.minecraft.world.item.Items.IRON_PICKAXE : outilPour(etat)));
			}
			villageois.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			villageois.getNavigation().stop();
			villageois.getLookControl().setLookAt(Vec3.atCenterOf(geste.pos));
			geste.avancement += 4;
			Corps.balancer(villageois);
			level.playSound(null, geste.pos, etat.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 0.8F);
			level.destroyBlockProgress(villageois.getId(), geste.pos, Math.min(9, geste.avancement * 10 / geste.duree));
			if (geste.avancement < geste.duree) {
				return false;
			}
			level.destroyBlockProgress(villageois.getId(), geste.pos, -1);
			if (geste.surFilon) {
				empocher(villageois, level, etat, geste.pos, net.minecraft.world.item.Items.IRON_PICKAXE);
				geste.blocsFaits++;
			}
			level.destroyBlock(geste.pos, false, villageois, 512);
			geste.pos = null;
			geste.duree = 0;
			geste.surFilon = false;
			return false;
		}
		// 2. Les blocs à dégager pour le pas suivant.
		if (!geste.aDegager.isEmpty()) {
			geste.pos = geste.aDegager.poll();
			return false;
		}
		// 3. Avancer dans la case dégagée.
		if (geste.pas != null) {
			if (villageois.blockPosition().closerThan(geste.pas, 1.2)) {
				geste.pas = null;
			} else if (maintenant > geste.delaiPas) {
				villageois.teleportTo(geste.pas.getX() + 0.5, geste.pas.getY(), geste.pas.getZ() + 0.5);
				villageois.getNavigation().stop();
				geste.pas = null;
			} else {
				// Dans une galerie le chemin est tout tracé : on le fait avancer droit devant, sans attendre que son cerveau de villageois s'y décide.
				villageois.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
				villageois.getMoveControl().setWantedPosition(geste.pas.getX() + 0.5, geste.pas.getY(), geste.pas.getZ() + 0.5, 0.6);
				return false;
			}
		}
		if (geste.blocsFaits >= geste.objectif) {
			return finMine(villageois, ame, geste, level, "tu as ce que tu voulais.");
		}
		if (geste.galerie >= 90) {
			return finMine(villageois, ame, geste, level, "la galerie est déjà bien longue, tu t'arrêtes.");
		}
		// 4. Le filon : celui qu'on visait s'il est toujours là, sinon le plus proche.
		BlockPos ici = villageois.blockPosition();
		if (geste.filon == null || !geste.ressource.test(level.getBlockState(geste.filon))) {
			geste.filon = filon(level, ici, geste.ressource, 10, 12);
			if (geste.filon == null) {
				geste.filon = filon(level, ici, geste.ressource, 24, 40);
			}
			if (geste.filon == null) {
				return finMine(villageois, ame, geste, level, "pas de " + geste.nomRessource + " à moins de 24 blocs d'ici, même en profondeur.");
			}
		}
		BlockPos filon = geste.filon;
		int dx = filon.getX() - ici.getX();
		int dy = filon.getY() - ici.getY();
		int dz = filon.getZ() - ici.getZ();
		if (Math.abs(dx) + Math.abs(dz) <= 3 && Math.abs(dx) <= 2 && Math.abs(dz) <= 2 && dy >= -1 && dy <= 2) {
			geste.pos = filon;
			geste.surFilon = true;
			return false;
		}
		// 5. Un pas de galerie vers le filon : en escalier s'il est plus bas ou plus haut.
		net.minecraft.core.Direction cap = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST) : (dz >= 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);
		int pente = dy > 1 ? 1 : dy < -1 ? -1 : 0;
		BlockPos suivant = ici.relative(cap).above(pente);
		if (lave(level, suivant) || lave(level, suivant.above())) {
			return finMine(villageois, ame, geste, level, "de la lave juste devant, tu renonces par prudence.");
		}
		BlockPos appui = suivant.below();
		if (!level.getBlockState(appui).isSolid()) {
			if (!level.getFluidState(appui).isEmpty()) {
				return finMine(villageois, ame, geste, level, "de l'eau barre le passage.");
			}
			level.setBlockAndUpdate(appui, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());
		}
		geste.aDegager.add(suivant.above());
		geste.aDegager.add(suivant);
		if (pente != 0) {
			geste.aDegager.add(pente < 0 ? suivant.above(2) : ici.above(2));
		}
		geste.pas = suivant;
		geste.delaiPas = maintenant + 50;
		geste.galerie++;
		if (geste.galerie % 7 == 0 && level.getBlockState(ici).isAir() && level.getBlockState(ici.below()).isSolid() && level.getMaxLocalRawBrightness(ici) < 8) {
			level.setBlockAndUpdate(ici, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
		}
		return false;
	}

	/** Retire un objet de l'inventaire d'après le nom donné par Claude (nom affiché ou identifiant, approximatif). */
	private static ItemStack retirer(final SimpleContainer inventaire, final String objet, final boolean unSeul) {
		String voulu = objet == null ? "" : objet.toLowerCase().replaceAll(" x\\d+$", "").trim();
		for (int i = 0; i < inventaire.getContainerSize(); i++) {
			ItemStack pile = inventaire.getItem(i);
			if (pile.isEmpty()) {
				continue;
			}
			String affiche = pile.getHoverName().getString().toLowerCase();
			String identifiant = BuiltInRegistries.ITEM.getKey(pile.getItem()).getPath().replace('_', ' ');
			if (voulu.isEmpty() || affiche.contains(voulu) || voulu.contains(affiche) || identifiant.contains(voulu) || voulu.contains(identifiant)) {
				return inventaire.removeItem(i, unSeul ? 1 : pile.getCount());
			}
		}
		return ItemStack.EMPTY;
	}
}
