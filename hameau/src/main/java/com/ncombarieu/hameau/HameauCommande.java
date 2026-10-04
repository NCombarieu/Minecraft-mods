package com.ncombarieu.hameau;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/**
 * /hameau etat                  — dépense et activité (tout le monde)
 * /hameau qui                   — les villageois des environs, par prénom (tout le monde)
 * /hameau ame [prénom]          — fiche intime d'un villageois, le plus proche par défaut (ops)
 * /hameau penser [prénom]       — le fait réfléchir tout de suite (ops)
 * /hameau dire <prénom> <texte> — lui adresse la parole, d'où qu'on soit (ops, console)
 * /hameau faire <prénom> <action> [cible] — lui impose une action sans passer par Claude (ops) : couper 3 64 9 ; aller place — ou batir 10 64 10 une cabane de pêcheur
 * /hameau oubli <joueur>      — tous les villageois oublient leurs griefs et leurs souvenirs de ce joueur (ops)
 * /hameau modele <haiku|sonnet|opus> [plans <haiku|sonnet|opus>] — change de modèle à chaud, sans redémarrer (ops)
 * /hameau pause | reprendre     — suspend ou relance les appels à Claude (ops)
 * /hameau recharger             — relit config/hameau.json et la clé (ops)
 * Les prénoms se complètent avec Tab et s'écrivent avec ou sans accents.
 */
public final class HameauCommande {
	private static final SimpleCommandExceptionType PERSONNE = new SimpleCommandExceptionType(Component.literal("Aucun villageois ici."));
	private static final DynamicCommandExceptionType INCONNU = new DynamicCommandExceptionType(
			nom -> Component.literal("Aucun villageois nommé « " + nom + " » dans les parages. Essaie /hameau qui."));
	private static final SimpleCommandExceptionType USAGE_DIRE = new SimpleCommandExceptionType(Component.literal("Usage : /hameau dire <prénom> <texte>"));

	private HameauCommande() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("hameau")
				.then(Commands.literal("etat").executes(HameauCommande::etat))
				.then(Commands.literal("qui").executes(HameauCommande::qui))
				.then(Commands.literal("ame")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> ame(c, proche(c)))
						.then(Commands.argument("prenom", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms)
								.executes(c -> ame(c, nomme(c, StringArgumentType.getString(c, "prenom"))))))
				.then(Commands.literal("penser")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> penser(c, proche(c)))
						.then(Commands.argument("prenom", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms)
								.executes(c -> penser(c, nomme(c, StringArgumentType.getString(c, "prenom"))))))
				.then(Commands.literal("dire")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("prenom et texte", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms)
								.executes(HameauCommande::dire)))
				.then(Commands.literal("faire")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("prenom action cible", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms)
								.executes(HameauCommande::faire)))
				.then(Commands.literal("oubli")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("joueur", StringArgumentType.word()).executes(HameauCommande::oubli)))
				.then(Commands.literal("modele")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("modele", StringArgumentType.word())
								.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(List.of("haiku", "sonnet", "opus"), b))
								.executes(c -> modele(c, StringArgumentType.getString(c, "modele"), null))
								.then(Commands.literal("plans").then(Commands.argument("plans", StringArgumentType.word())
										.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(List.of("haiku", "sonnet", "opus"), b))
										.executes(c -> modele(c, StringArgumentType.getString(c, "modele"), StringArgumentType.getString(c, "plans")))))))
				.then(Commands.literal("pause")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> pause(c, true)))
				.then(Commands.literal("reprendre")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> pause(c, false)))
				.then(Commands.literal("recharger")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(HameauCommande::recharger)));
	}

	/** Les villageois chargés du monde de la source, du plus proche au plus lointain. */
	private static List<Villager> alentour(final CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		List<Villager> villageois = new ArrayList<>(level.getEntities(EntityTypeTest.forClass(Villager.class), Entity::isAlive));
		villageois.sort(Comparator.comparingDouble(v -> v.distanceToSqr(source.getPosition())));
		return villageois;
	}

	/** Propose les prénoms tant que le premier mot n'est pas terminé. */
	private static CompletableFuture<Suggestions> prenoms(final CommandContext<CommandSourceStack> c, final SuggestionsBuilder builder) {
		String saisi = builder.getRemaining();
		if (saisi.contains(" ")) {
			return builder.buildFuture();
		}
		String debut = Ames.simplifier(saisi);
		int proposes = 0;
		for (Villager villageois : alentour(c.getSource())) {
			String nom = Ames.de(villageois).nom;
			if (Ames.simplifier(nom).startsWith(debut) && proposes++ < 30) {
				builder.suggest(nom);
			}
		}
		return builder.buildFuture();
	}

	private static Villager nomme(final CommandContext<CommandSourceStack> c, final String prenom) throws CommandSyntaxException {
		String cherche = Ames.simplifier(prenom);
		for (Villager villageois : alentour(c.getSource())) {
			if (Ames.simplifier(Ames.de(villageois).nom).equals(cherche)) {
				return villageois;
			}
		}
		throw INCONNU.create(prenom.trim());
	}

	private static Villager proche(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		CommandSourceStack source = c.getSource();
		List<Villager> proches = source.getLevel().getEntitiesOfClass(Villager.class, AABB.ofSize(source.getPosition(), 32, 16, 32), Entity::isAlive);
		if (proches.isEmpty()) {
			throw PERSONNE.create();
		}
		proches.sort(Comparator.comparingDouble(v -> v.distanceToSqr(source.getPosition())));
		return proches.getFirst();
	}

	private static void ligne(final CommandContext<CommandSourceStack> c, final String texte, final ChatFormatting couleur) {
		c.getSource().sendSuccess(() -> Component.literal(texte).withStyle(couleur), false);
	}

	private static int etat(final CommandContext<CommandSourceStack> c) {
		HameauConfig config = HameauConfig.get();
		String statut = !Cerveau.pret() ? "pas de clé API" : Vie.enPause ? "en pause" : Ames.budget.autorise() ? "actif" : "plafond de dépense atteint";
		ligne(c, "Hameau : " + statut + " — " + Ames.toutes().size() + " âmes connues, " + Vie.actifs(c.getSource().getServer()).size() + " éveillées.", ChatFormatting.YELLOW);
		ligne(c, Ames.budget.resume(), ChatFormatting.GRAY);
		ligne(c, "Modèle " + config.modele + " (plans : " + config.batir.modele + "), une réflexion toutes les " + config.intervallePensee + " s environ, vie hors ligne : " + (config.horsLigne() ? "oui" : "non") + ".", ChatFormatting.GRAY);
		return 1;
	}

	private static int qui(final CommandContext<CommandSourceStack> c) {
		List<Villager> villageois = alentour(c.getSource());
		if (villageois.isEmpty()) {
			ligne(c, "Aucun villageois dans les parages.", ChatFormatting.GRAY);
			return 0;
		}
		int montres = 0;
		for (Villager v : villageois) {
			if (montres++ >= 15) {
				break;
			}
			Ame ame = Ames.de(v);
			ligne(c, ame.nom + ", " + Perception.metier(v) + " — " + ame.humeur + " — à " + (int) Math.sqrt(v.distanceToSqr(c.getSource().getPosition())) + " blocs",
					montres == 1 ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
		}
		return montres;
	}

	private static int ame(final CommandContext<CommandSourceStack> c, final Villager villageois) {
		Ame ame = Ames.de(villageois);
		ligne(c, ame.nom + ", " + Perception.metier(villageois) + " — " + String.join(", ", ame.traits), ChatFormatting.YELLOW);
		ligne(c, "Manie : " + ame.manie + ". Parle : " + ame.parler + ".", ChatFormatting.GRAY);
		ligne(c, "Désire : " + ame.desir + ". Craint : " + ame.peur + ". Humeur : " + ame.humeur + ".", ChatFormatting.GRAY);
		for (String lien : ame.liens) {
			ligne(c, "♦ " + lien, ChatFormatting.GOLD);
		}
		if (ame.projet != null) {
			ligne(c, "Projet : " + ame.projet, ChatFormatting.GRAY);
		}
		if (!ame.dernierePensee.isEmpty()) {
			ligne(c, "Pense : " + ame.dernierePensee, ChatFormatting.GRAY);
		}
		StringBuilder relations = new StringBuilder();
		for (Map.Entry<String, Integer> relation : ame.relations.entrySet()) {
			relations.append(relations.isEmpty() ? "" : ", ").append(relation.getKey()).append(" ").append(relation.getValue() > 0 ? "+" : "").append(relation.getValue());
		}
		ligne(c, "Opinions : " + (relations.isEmpty() ? "aucune encore" : relations), ChatFormatting.GRAY);
		for (String souvenir : ame.marquants) {
			ligne(c, "★ " + souvenir, ChatFormatting.DARK_AQUA);
		}
		List<String> recents = ame.recents.subList(Math.max(0, ame.recents.size() - 5), ame.recents.size());
		for (String souvenir : recents) {
			ligne(c, "· " + souvenir, ChatFormatting.DARK_GRAY);
		}
		return 1;
	}

	private static int penser(final CommandContext<CommandSourceStack> c, final Villager villageois) {
		Ame ame = Ames.de(villageois);
		if (!Cerveau.pret() || ame.enCours || !Ames.budget.autorise()) {
			ligne(c, ame.nom + " ne peut pas réfléchir maintenant (clé absente, plafond atteint ou réflexion en cours).", ChatFormatting.RED);
			return 0;
		}
		Vie.reflechir(c.getSource().getServer(), villageois, ame, c.getSource().getServer().getTickCount());
		ligne(c, ame.nom + " réfléchit…", ChatFormatting.YELLOW);
		return 1;
	}

	private static int dire(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		String[] parties = StringArgumentType.getString(c, "prenom et texte").trim().split("\\s+", 2);
		if (parties.length < 2) {
			throw USAGE_DIRE.create();
		}
		Villager villageois = nomme(c, parties[0]);
		Ame ame = Ames.de(villageois);
		long maintenant = c.getSource().getServer().getTickCount();
		ame.noter(c.getSource().getTextName() + " t'a dit : « " + parties[1] + " »");
		ame.presser(maintenant, 1);
		if (c.getSource().getEntity() != null) {
			Actions.ecouter(villageois, c.getSource().getEntity(), maintenant);
		}
		Bulles.montrer(villageois, "…", true, maintenant);
		ligne(c, "Tu dis à " + ame.nom + " : « " + parties[1] + " »", ChatFormatting.GRAY);
		return 1;
	}

	private static int faire(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		String[] parties = StringArgumentType.getString(c, "prenom action cible").trim().split("\\s+", 2);
		if (parties.length < 2) {
			throw new SimpleCommandExceptionType(Component.literal("Usage : /hameau faire <prénom> <action> [cible] [; action cible …]")).create();
		}
		Villager villageois = nomme(c, parties[0]);
		Ame ame = Ames.de(villageois);
		// Plusieurs actions séparées par « ; » s'enchaînent, comme un plan décidé par le villageois.
		List<Cerveau.Etape> etapes = new ArrayList<>();
		for (String morceau : parties[1].split(";")) {
			String[] mots = morceau.trim().split("\\s+", 2);
			if (!mots[0].isEmpty()) {
				String cible = mots.length > 1 ? mots[1] : null;
				etapes.add(new Cerveau.Etape(mots[0].toLowerCase(), cible, cible));
			}
		}
		Cerveau.Etape premiere = etapes.removeFirst();
		Cerveau.Decision decision = new Cerveau.Decision(null, null, null, null, false, premiere.action(), premiere.cible(), premiere.objet(), etapes, null, null, null, Map.of(), null, 0, 0);
		Actions.lancer(villageois, ame, decision, c.getSource().getServer().getTickCount());
		ligne(c, ame.nom + " : " + parties[1], ChatFormatting.YELLOW);
		return 1;
	}

	private static int oubli(final CommandContext<CommandSourceStack> c) {
		String joueur = StringArgumentType.getString(c, "joueur");
		int touches = 0;
		for (Ame ame : Ames.toutes()) {
			boolean avait = ame.relations.keySet().removeIf(nom -> nom.equalsIgnoreCase(joueur));
			avait |= ame.marquants.removeIf(s -> s.toLowerCase().contains(joueur.toLowerCase()));
			avait |= ame.recents.removeIf(s -> s.toLowerCase().contains(joueur.toLowerCase()));
			avait |= ame.nouveaux.removeIf(s -> s.toLowerCase().contains(joueur.toLowerCase()));
			if (ame.abords != null) {
				ame.abords.clear();
			}
			touches += avait ? 1 : 0;
		}
		int total = touches;
		ligne(c, total + " villageois ont tout oublié de " + joueur + " : il repart comme un inconnu.", ChatFormatting.YELLOW);
		return total;
	}

	private static int modele(final CommandContext<CommandSourceStack> c, final String reflexions, final String plans) {
		HameauConfig config = HameauConfig.get();
		if (!config.choisirModele(reflexions) || (plans != null && !config.choisirModelePlans(plans))) {
			ligne(c, "Modèles possibles : haiku, sonnet, opus.", ChatFormatting.RED);
			return 0;
		}
		HameauConfig.sauvegarder();
		ligne(c, "Réflexions : " + config.modele + " — plans de construction : " + config.batir.modele + ". Effet immédiat, réglage conservé.", ChatFormatting.YELLOW);
		return 1;
	}

	private static int pause(final CommandContext<CommandSourceStack> c, final boolean pause) {
		Vie.enPause = pause;
		ligne(c, pause ? "Hameau en pause : plus aucun appel à Claude." : "Hameau relancé.", ChatFormatting.YELLOW);
		return 1;
	}

	private static int recharger(final CommandContext<CommandSourceStack> c) {
		HameauConfig.charger();
		Cerveau.demarrer();
		ligne(c, "Réglages et clé relus." + (Cerveau.pret() ? "" : " Aucune clé API trouvée."), ChatFormatting.YELLOW);
		return 1;
	}
}
