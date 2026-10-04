package com.ncombarieu.hameau;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;

/** Le rythme du village : qui réfléchit, quand, et ce que deviennent les décisions. */
public final class Vie {
	private static final Random HASARD = new Random();
	static boolean enPause;
	private static int enVol;
	private static boolean plafondSignale;

	private Vie() {
	}

	static Villager trouver(final MinecraftServer server, final UUID uuid) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(uuid) instanceof Villager villageois) {
				return villageois;
			}
		}
		return null;
	}

	/** Les villageois qui vivent en ce moment : proches d'un joueur, ou tous ceux chargés en vie hors ligne. */
	static List<Villager> actifs(final MinecraftServer server) {
		HameauConfig config = HameauConfig.get();
		Set<Villager> actifs = new LinkedHashSet<>();
		for (ServerLevel level : server.getAllLevels()) {
			if (config.horsLigne()) {
				actifs.addAll(level.getEntities(EntityTypeTest.forClass(Villager.class), Entity::isAlive));
			} else {
				for (ServerPlayer joueur : level.players()) {
					if (!joueur.isSpectator()) {
						actifs.addAll(level.getEntitiesOfClass(Villager.class, joueur.getBoundingBox().inflate(config.rayonActif), Entity::isAlive));
					}
				}
			}
		}
		List<Villager> liste = new ArrayList<>(actifs);
		return liste.size() > config.maxVillageoisActifs ? liste.subList(0, config.maxVillageoisActifs) : liste;
	}

	static void tick(final MinecraftServer server) {
		long maintenant = server.getTickCount();
		if (maintenant % 20 != 0) {
			return;
		}
		if (maintenant % 6000 == 0) {
			Ames.sauvegarder();
		}
		HameauConfig config = HameauConfig.get();
		List<Villager> actifs = actifs(server);
		if (maintenant % 200 == 0) {
			maintenirCharges(server, actifs);
		}
		if (!enPause && Cerveau.pret()) {
			aborder(server, maintenant);
			imprevu(actifs, maintenant);
		}
		for (Villager villageois : actifs) {
			Ame ame = Ames.de(villageois);
			ame.x = villageois.getBlockX();
			ame.y = villageois.getBlockY();
			ame.z = villageois.getBlockZ();
			if (!ame.etranger && ame.village == null) {
				Ames.rattacher(villageois, ame);
			}
			if (!enPause && Cerveau.pret() && enVol < config.appelsSimultanes && Ames.budget.autorise()) {
				fonder(server, Ames.village(ame));
			}
			if (ame.ebauche) {
				// Pas de réflexion avant d'avoir une personnalité ; le village d'abord, pour que l'habitant lui ressemble.
				Ames.Village village = Ames.village(ame);
				boolean villagePret = ame.etranger || village == null || village.culture != null;
				if (!enPause && Cerveau.pret() && !ame.enCours && villagePret && maintenant >= ame.prochaineNaissance && enVol < config.appelsSimultanes && Ames.budget.autorise()) {
					naitre(server, villageois, ame, null);
				}
				continue;
			}
			if (ame.prochainePensee == 0) {
				ame.prochainePensee = maintenant + (10 + HASARD.nextInt(Math.max(1, config.intervallePensee / 2))) * 20L;
			}
			if (enPause || !Cerveau.pret() || ame.enCours || maintenant < ame.prochainePensee) {
				continue;
			}
			if (villageois.isSleeping() && ame.nouveaux.isEmpty()) {
				ame.prochainePensee = maintenant + config.intervallePensee * 20L;
				continue;
			}
			if (enVol >= config.appelsSimultanes) {
				break;
			}
			if (!Ames.budget.autorise()) {
				if (!plafondSignale) {
					plafondSignale = true;
					Hameau.LOGGER.warn("Hameau : plafond de dépense atteint, les villageois se taisent. {}", Ames.budget.resume());
				}
				return;
			}
			plafondSignale = false;
			reflechir(server, villageois, ame, maintenant);
		}
	}

	private static final Map<UUID, Long> DERNIER_ABORD = new java.util.HashMap<>();
	private static long prochainImprevu;

	/** Un joueur qui s'approche est remarqué : le villageois le plus proche peut l'aborder de lui-même. */
	private static void aborder(final MinecraftServer server, final long maintenant) {
		HameauConfig config = HameauConfig.get();
		for (ServerPlayer joueur : server.getPlayerList().getPlayers()) {
			if (joueur.isSpectator() || maintenant - DERNIER_ABORD.getOrDefault(joueur.getUUID(), -10000L) < 30 * 20) {
				continue;
			}
			String nomJoueur = joueur.getName().getString();
			List<Villager> proches = joueur.level().getEntitiesOfClass(Villager.class, joueur.getBoundingBox().inflate(config.distanceAbord), e -> e.isAlive() && !e.isSleeping());
			proches.sort(java.util.Comparator.comparingDouble(joueur::distanceToSqr));
			for (Villager villageois : proches) {
				Ame ame = Ames.de(villageois);
				if (ame.abords == null) {
					ame.abords = new java.util.HashMap<>();
				}
				// Chaque villageois ne remarque l'arrivée d'un même joueur qu'une fois toutes les six minutes.
				if (ame.enCours || maintenant - ame.abords.getOrDefault(nomJoueur, -100000L) < 360 * 20 || maintenant - ame.dernierAppel < 40 * 20) {
					continue;
				}
				ame.abords.put(nomJoueur, maintenant);
				DERNIER_ABORD.put(joueur.getUUID(), maintenant);
				ame.noter(nomJoueur + " s'approche de toi" + (ame.relations.containsKey(nomJoueur) ? "." : " : c'est la première fois que tu vois ce voyageur."));
				ame.presser(maintenant, 1);
				break;
			}
		}
	}

	private static final String[] IMPREVUS = {
		"Tu viens de trouver une émeraude par terre.|EMERALD",
		"Tu as fait cette nuit un rêve étrange où {autre} tenait un grand rôle, et tu n'arrives pas à te l'ôter de la tête.",
		"Tu t'aperçois que ton porte-bonheur a disparu. Tu es presque sûr de l'avoir vu hier chez {autre}.",
		"Une idée te vient : et si le village organisait une fête ? Il faudrait en parler aux autres.",
		"Tu as une envie terrible de quelque chose de sucré, et tu n'as rien de tel.",
		"On t'a rapporté que {autre} aurait dit du mal de toi. Tu ne sais pas si c'est vrai.",
		"Tu as cueilli une fleur en chemin et tu te demandes à qui l'offrir.|POPPY",
		"Tu es d'excellente humeur sans savoir pourquoi : tu as envie de faire plaisir à quelqu'un.",
		"Tu repenses à un pari que tu voudrais proposer à {autre}.",
		"Tu as mal dormi et tout t'agace un peu aujourd'hui.",
		"Tu as retrouvé au fond de ta poche un biscuit que tu avais oublié.|COOKIE",
		"Tu te dis que tu ne connais pas assez {autre} et qu'il serait temps d'y remédier.",
		"Un souvenir d'enfance te revient et te donne envie de le raconter à quelqu'un.",
		"Tu trouves que le village manque de quelque chose, et tu as ton idée sur ce qu'il faudrait y construire.",
		"Tu as entendu un bruit bizarre du côté des champs cette nuit. Tu voudrais savoir si d'autres l'ont entendu.",
		"Tu as composé une petite chanson et tu brûles de la faire entendre.",
		"Tu t'ennuies : il te faut de la compagnie ou une bêtise à faire.",
		"Tu as un service à demander, et tu cherches qui pourrait te le rendre.",
		"Tu te sens d'humeur à pardonner une vieille querelle.",
		"Tu as une nouvelle croustillante à partager et tu cherches à qui la dire."
	};

	/** De temps en temps, la vie apporte un petit imprévu à un villageois éveillé : la graine d'une histoire. */
	private static void imprevu(final List<Villager> actifs, final long maintenant) {
		int intervalle = HameauConfig.get().intervalleIncident;
		if (intervalle <= 0 || actifs.isEmpty()) {
			return;
		}
		if (prochainImprevu == 0 || prochainImprevu > maintenant + intervalle * 40L) {
			prochainImprevu = maintenant + (long) (intervalle * (0.3 + HASARD.nextDouble() * 0.5)) * 20L;
		}
		if (maintenant < prochainImprevu) {
			return;
		}
		prochainImprevu = maintenant + (long) (intervalle * (0.6 + HASARD.nextDouble() * 0.8)) * 20L;
		Villager villageois = actifs.get(HASARD.nextInt(actifs.size()));
		Ame ame = Ames.de(villageois);
		if (villageois.isSleeping() || ame.enCours) {
			return;
		}
		String[] parties = IMPREVUS[HASARD.nextInt(IMPREVUS.length)].split("\\|");
		String texte = parties[0];
		if (texte.contains("{autre}")) {
			List<Villager> autres = new ArrayList<>(actifs);
			autres.remove(villageois);
			if (autres.isEmpty()) {
				return;
			}
			texte = texte.replace("{autre}", Ames.de(autres.get(HASARD.nextInt(autres.size()))).nom);
		}
		if (parties.length > 1) {
			net.minecraft.world.item.Item objet = switch (parties[1]) {
				case "EMERALD" -> net.minecraft.world.item.Items.EMERALD;
				case "POPPY" -> net.minecraft.world.item.Items.POPPY;
				default -> net.minecraft.world.item.Items.COOKIE;
			};
			villageois.getInventory().addItem(new net.minecraft.world.item.ItemStack(objet));
		}
		ame.noter(texte);
		ame.presser(maintenant, 2);
	}

	/** Demande à Claude le nom et la culture d'un village qui n'en a pas encore. */
	private static void fonder(final MinecraftServer server, final Ames.Village village) {
		if (village == null || village.culture != null || village.enCours) {
			return;
		}
		village.enCours = true;
		enVol++;
		Cerveau.fonder(Perception.fondation(village)).whenComplete((fondation, erreur) -> server.execute(() -> {
			enVol--;
			village.enCours = false;
			if (erreur == null) {
				Ames.budget.enregistrer(fondation.tokensEntree(), fondation.tokensSortie());
			}
			if (erreur != null || fondation.nom() == null || fondation.culture() == null) {
				Hameau.LOGGER.warn("Hameau : village non fondé : {}", erreur != null ? erreur.toString() : "réponse incomplète");
				if (++village.echecs >= 2) {
					// Tant pis : ses habitants naîtront sans culture commune.
					village.culture = "";
				}
				return;
			}
			village.nom = fondation.nom();
			village.culture = fondation.culture();
			Ames.sauvegarder();
			Hameau.LOGGER.info("Hameau : village fondé — {} : {}", village.nom, village.culture);
		}));
	}

	/**
	 * Claude invente la personnalité du villageois (naissance, /hameau renaitre) ou la modifie selon ame.consigne (/hameau personnalite).
	 * @param retour à qui annoncer le résultat, ou null
	 */
	static void naitre(final MinecraftServer server, final Villager villageois, final Ame ame, final net.minecraft.commands.CommandSourceStack retour) {
		String demande = Perception.naissance(villageois, ame, Ames.village(ame));
		boolean surDemande = ame.consigne != null;
		ame.enCours = true;
		enVol++;
		UUID uuid = villageois.getUUID();
		Cerveau.incarner(demande).whenComplete((persona, erreur) -> server.execute(() -> {
			enVol--;
			ame.enCours = false;
			long apres = server.getTickCount();
			if (erreur == null) {
				Ames.budget.enregistrer(persona.tokensEntree(), persona.tokensSortie());
			}
			if (erreur != null || !persona.complete()) {
				Hameau.LOGGER.warn("Hameau : personnalité de {} non inventée : {}", ame.nom, erreur != null ? erreur.toString() : "réponse incomplète");
				ame.prochaineNaissance = apres + 30 * 20;
				if (++ame.echecsNaissance >= 3) {
					// On s'en tient au brouillon tiré au sort.
					ame.ebauche = false;
					ame.consigne = null;
				}
				if (retour != null) {
					retour.sendFailure(Component.literal("Claude n'a pas pu réécrire " + ame.nom + ". Nouvel essai dans 30 secondes."));
				}
				return;
			}
			Villager present = trouver(server, uuid);
			String ancien = ame.nom;
			if ((surDemande || !ame.baptise) && Ames.prenomLibre(persona.nom(), ame)) {
				Ames.renommer(ame, persona.nom(), present);
			}
			if (persona.femme() != null && persona.femme() != ame.femme && surDemande) {
				ame.femme = persona.femme();
				if (present != null) {
					Corps.retirer(present);
				}
			}
			ame.traits = new ArrayList<>(persona.traits());
			ame.manie = persona.manie();
			ame.parler = persona.parler();
			ame.desir = persona.desir();
			ame.peur = persona.peur();
			if (persona.histoire() != null) {
				ame.histoire = persona.histoire();
			}
			Ame autre = persona.lienAvec() != null ? Ames.parNom(persona.lienAvec()) : null;
			if (!ame.lie && !ame.etranger && autre != null && autre != ame && persona.lienPourToi() != null) {
				ame.lie = true;
				ame.liens.add(persona.lienPourToi());
				ame.ajusterRelation(autre.nom, persona.opinion());
				if (persona.lienPourLui() != null) {
					autre.liens.add(persona.lienPourLui());
				}
				autre.ajusterRelation(ame.nom, persona.opinionDeLui());
			}
			// Un étranger n'a de lien avec personne ; les autres, faute de lien inventé, en reçoivent un tiré au sort (Ames.de).
			ame.lie |= ame.etranger;
			if (surDemande) {
				ame.noter("Tu te sens changé, comme si tu devenais enfin toi-même.");
			}
			ame.ebauche = false;
			ame.consigne = null;
			ame.echecsNaissance = 0;
			ame.prochainePensee = apres + (3 + HASARD.nextInt(10)) * 20L;
			Ames.sauvegarder();
			Hameau.LOGGER.info("Hameau : {}{} — {} | manie : {} | parle : {} | désire : {} | craint : {} | {}", ame.nom, ancien.equals(ame.nom) ? "" : " (jusqu'ici " + ancien + ")",
					String.join(", ", ame.traits), ame.manie, ame.parler, ame.desir, ame.peur, ame.histoire);
			if (retour != null) {
				retour.sendSuccess(() -> Component.literal(ame.nom + (ancien.equals(ame.nom) ? "" : " (jusqu'ici " + ancien + ")") + " est désormais : " + String.join(", ", ame.traits)
						+ ". /hameau ame " + ame.nom + " pour sa fiche.").withStyle(ChatFormatting.YELLOW), false);
			}
		}));
	}

	static void reflechir(final MinecraftServer server, final Villager villageois, final Ame ame, final long maintenant) {
		String fiche = Perception.fiche(villageois, ame, (ServerLevel) villageois.level());
		int digeres = ame.nouveaux.size();
		String quand = Perception.moment((ServerLevel) villageois.level());
		ame.enCours = true;
		ame.dernierAppel = maintenant;
		enVol++;
		UUID uuid = villageois.getUUID();
		Cerveau.demander(fiche).whenComplete((decision, erreur) -> server.execute(() -> {
			enVol--;
			ame.enCours = false;
			long apres = server.getTickCount();
			int intervalle = HameauConfig.get().intervallePensee;
			ame.prochainePensee = apres + (long) (intervalle * (0.75 + HASARD.nextDouble() * 0.5)) * 20L;
			if (erreur != null) {
				Hameau.LOGGER.warn("Hameau : {} n'a pas pu réfléchir : {}", ame.nom, erreur.toString());
				return;
			}
			Ames.budget.enregistrer(decision.tokensEntree(), decision.tokensSortie());
			ame.digerer(digeres, "(" + quand + ") ");
			Villager present = trouver(server, uuid);
			if (present == null || !present.isAlive()) {
				return;
			}
			appliquer(present, ame, decision, apres);
		}));
	}

	private static void appliquer(final Villager villageois, final Ame ame, final Cerveau.Decision decision, final long maintenant) {
		if (decision.pensee() != null) {
			ame.dernierePensee = decision.pensee();
		}
		if (decision.humeur() != null) {
			ame.humeur = decision.humeur();
		}
		if (decision.projet() != null) {
			ame.projet = decision.projet();
		}
		for (Map.Entry<String, Integer> relation : decision.relations().entrySet()) {
			ame.ajusterRelation(relation.getKey(), relation.getValue());
		}
		if (decision.souvenir() != null) {
			ame.retenir("(" + Perception.moment((ServerLevel) villageois.level()) + ") " + decision.souvenir());
		}
		Bulles.effacer(villageois.getUUID());
		Bulles.emouvoir(villageois, ame, decision.emotion(), decision.parole() != null);
		if ("peur".equalsIgnoreCase(decision.emotion()) || "tristesse".equalsIgnoreCase(decision.emotion())) {
			Corps.accroupir(villageois, 50);
		} else if (decision.parole() != null) {
			// Parler s'accompagne d'un geste du bras.
			Corps.balancer(villageois);
		}
		if (decision.parole() != null) {
			dire(villageois, ame, decision.parole(), decision.geste(), decision.a(), decision.prive(), maintenant);
		} else if (decision.geste() != null) {
			mimer(villageois, ame, decision.geste(), maintenant);
		}
		Actions.lancer(villageois, ame, decision, maintenant);
		Hameau.LOGGER.info("[{}] {} | {} | action={} cible={} objet={}{} | {}", ame.nom, decision.pensee(), decision.geste(), decision.action(), decision.cible(), decision.objet(),
				decision.suite().isEmpty() ? "" : " puis " + decision.suite().stream().map(e -> e.action() + " " + e.cible()).toList(),
				decision.parole() == null ? "(se tait)" : (decision.prive() ? "(chuchote) " : "") + "« " + decision.parole() + " »");
	}

	private static Entity present(final Villager villageois, final String nom) {
		if (nom == null) {
			return null;
		}
		String cherche = Ames.simplifier(nom);
		for (net.minecraft.world.entity.LivingEntity proche : villageois.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
				villageois.getBoundingBox().inflate(24), e -> e != villageois && e.isAlive() && (e instanceof Villager || e instanceof ServerPlayer))) {
			if (Ames.simplifier(Perception.nom(proche)).equals(cherche)) {
				return proche;
			}
		}
		return null;
	}

	/** Un geste sans parole : visible en bulle discrète et en italique dans le chat des joueurs proches. */
	private static void mimer(final Villager villageois, final Ame ame, final String geste, final long maintenant) {
		Bulles.montrer(villageois, "* " + geste + " *", true, maintenant);
		Component message = Component.literal("* " + ame.nom + " " + geste + " *").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
		for (ServerPlayer joueur : ((ServerLevel) villageois.level()).players()) {
			if (joueur.distanceTo(villageois) <= 14) {
				joueur.sendSystemMessage(message);
			}
		}
	}

	/** La parole est entendue par les joueurs et les villageois proches ; le destinataire villageois peut répliquer. */
	static void dire(final Villager villageois, final Ame ame, final String texte, final String geste, final String destinataire, final boolean prive, final long maintenant) {
		ServerLevel level = (ServerLevel) villageois.level();
		Entity vise = present(villageois, destinataire);
		if (vise != null) {
			villageois.getLookControl().setLookAt(vise);
			villageois.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET, new net.minecraft.world.entity.ai.behavior.EntityTracker(vise, true));
		}
		boolean chuchote = prive && vise != null;
		net.minecraft.network.chat.MutableComponent message = Component.literal(ame.nom).withStyle(ChatFormatting.YELLOW);
		if (geste != null) {
			message.append(Component.literal(" (" + geste + ")").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		}
		message.append(Component.literal(chuchote ? " te chuchote : " : destinataire != null ? " à " + destinataire + " : " : " : ").withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(texte).withStyle(chuchote ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.WHITE));
		if (chuchote) {
			Bulles.montrer(villageois, "* chuchote à " + destinataire + " *", true, maintenant);
			if (vise instanceof ServerPlayer joueur) {
				joueur.sendSystemMessage(message);
			}
		} else {
			Bulles.montrer(villageois, texte, false, maintenant);
			for (ServerPlayer joueur : level.players()) {
				if (joueur.distanceTo(villageois) <= 24) {
					joueur.sendSystemMessage(message);
				}
			}
		}
		ame.noter("Tu as " + (chuchote ? "chuchoté" : "dit") + (destinataire != null ? " à " + destinataire : "") + " : « " + texte + " »");
		for (Villager autre : level.getEntitiesOfClass(Villager.class, villageois.getBoundingBox().inflate(12), e -> e != villageois && e.isAlive())) {
			Ame oreille = Ames.de(autre);
			boolean cible = autre == vise;
			if (chuchote && !cible) {
				oreille.noter("Tu as vu " + ame.nom + " chuchoter quelque chose à " + destinataire + ".");
				continue;
			}
			oreille.noter(ame.nom + (cible ? (chuchote ? " t'a chuchoté" : " t'a dit") : destinataire != null ? " a dit à " + destinataire : " a dit") + " : « " + texte + " »");
			if (cible && oreille.peutRepliquer(maintenant)) {
				Actions.ecouter(autre, villageois, maintenant);
				oreille.presser(maintenant, 3);
			}
		}
	}

	static void annoncer(final Villager villageois, final String texte, final ServerPlayer joueur) {
		joueur.sendSystemMessage(Component.literal(texte).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
	}

	/** Les villageois proches d'une scène en gardent le souvenir ; un fait grave les fait réagir sans attendre. */
	static void temoins(final Entity auteur, final Entity victime, final String recit, final boolean grave) {
		ServerLevel level = (ServerLevel) auteur.level();
		long maintenant = level.getServer().getTickCount();
		for (Villager temoin : level.getEntitiesOfClass(Villager.class, auteur.getBoundingBox().inflate(14), e -> e != auteur && e != victime && e.isAlive())) {
			Ame ame = Ames.de(temoin);
			ame.noter(recit);
			if (grave && ame.peutRepliquer(maintenant)) {
				ame.presser(maintenant, 4 + HASARD.nextInt(6));
			}
		}
	}

	/** Vie hors ligne : garde chargés les chunks où vivent les villageois ; sinon libère ceux qu'on avait retenus. */
	static void maintenirCharges(final MinecraftServer server, final List<Villager> actifs) {
		ServerLevel monde = server.overworld();
		Set<Long> voulus = new HashSet<>();
		if (HameauConfig.get().horsLigne()) {
			for (Villager villageois : actifs) {
				if (villageois.level() == monde) {
					voulus.add(ChunkPos.pack(villageois.getBlockX() >> 4, villageois.getBlockZ() >> 4));
				}
			}
			// Au démarrage, personne n'est encore chargé : on repart des dernières positions connues.
			if (actifs.isEmpty()) {
				for (Ame ame : Ames.toutes()) {
					voulus.add(ChunkPos.pack(ame.x >> 4, ame.z >> 4));
				}
			}
		}
		for (Long chunk : new ArrayList<>(Ames.chunksForces)) {
			if (!voulus.contains(chunk)) {
				monde.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
				Ames.chunksForces.remove(chunk);
			}
		}
		for (Long chunk : voulus) {
			if (Ames.chunksForces.add(chunk)) {
				monde.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true);
			}
		}
	}
}
