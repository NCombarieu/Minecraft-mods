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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/**
 * /hameau help [commande]       — la liste des commandes, ou le détail de l'une d'elles (tout le monde)
 * /hameau etat                  — dépense et activité (tout le monde)
 * /hameau univers [texte|rien]  — le décor propre au serveur, ajouté aux consignes de Claude (ops)
 * /hameau voix [prénom] [voix]  — état de la voix parlée, voix d'un villageois, ou changement de voix (ops)
 * /hameau journal [tout|livre]  — ce qui s'est passé au village depuis la dernière lecture (tout le monde)
 * /hameau chantier <valider|ici|annuler> <prénom> — avis sur l'emplacement d'un chantier balisé (tout le monde)
 * /hameau ordres [prénom]       — menu d'ordres d'un villageois à son service (tout le monde)
 * /hameau village               — nom et culture du village où l'on se trouve (tout le monde)
 * /hameau personnalite <prénom> <demande> — réécrit son caractère selon la demande (ops)
 * /hameau renaitre <prénom|tous> — lui fait inventer une identité toute neuve par Claude (ops)
 * /hameau recruter <prénom> [joueur] / liberer <prénom> — le met au service de quelqu'un, ou le congédie (ops)
 * /hameau presenter <prénom>    — fait d'un étranger (/summon, œuf) un habitant du village (ops)
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
				.executes(c -> aide(c, null))
				.then(Commands.literal("help").executes(c -> aide(c, null))
						.then(Commands.argument("commande", StringArgumentType.word())
								.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(AIDES.stream().map(Aide::nom), b))
								.executes(c -> aide(c, StringArgumentType.getString(c, "commande")))))
				.then(Commands.literal("aide").executes(c -> aide(c, null))
						.then(Commands.argument("commande", StringArgumentType.word())
								.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(AIDES.stream().map(Aide::nom), b))
								.executes(c -> aide(c, StringArgumentType.getString(c, "commande")))))
				.then(Commands.literal("village").executes(HameauCommande::village))
				.then(Commands.literal("journal").executes(c -> journal(c, ""))
						.then(Commands.literal("tout").executes(c -> journal(c, "tout")))
						.then(Commands.literal("livre").executes(c -> journal(c, "livre"))))
				.then(Commands.literal("chantier")
						.then(Commands.argument("choix", StringArgumentType.word())
								.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(List.of("valider", "ici", "annuler"), b))
								.then(Commands.argument("prenom", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms).executes(HameauCommande::chantier))))
				.then(Commands.literal("ordres").executes(c -> ordres(c, null))
						.then(Commands.argument("prenom", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms)
								.executes(c -> ordres(c, StringArgumentType.getString(c, "prenom")))))
				.then(Commands.literal("voix")
						.executes(c -> voix(c, null))
						.then(Commands.literal("synthese").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("moteur", StringArgumentType.word())
										.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(HameauConfig.get().voix.moteurs.keySet(), b))
										.executes(c -> moteur(c, true, StringArgumentType.getString(c, "moteur")))))
						.then(Commands.literal("transcription").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("moteur", StringArgumentType.word())
										.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(HameauConfig.get().voix.moteurs.keySet(), b))
										.executes(c -> moteur(c, false, StringArgumentType.getString(c, "moteur")))))
						.then(Commands.literal("essai").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(c -> essai(c, "Bonjour voyageur, bienvenue au village !"))
								.then(Commands.argument("texte", StringArgumentType.greedyString()).executes(c -> essai(c, StringArgumentType.getString(c, "texte")))))
						.then(Commands.argument("prenom et voix", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms)
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(c -> voix(c, StringArgumentType.getString(c, "prenom et voix")))))
				.then(Commands.literal("univers")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> univers(c, null))
						.then(Commands.argument("texte", StringArgumentType.greedyString()).executes(c -> univers(c, StringArgumentType.getString(c, "texte")))))
				.then(Commands.literal("personnalite")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("prenom et demande", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms)
								.executes(HameauCommande::personnalite)))
				.then(Commands.literal("renaitre")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("prenom", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms)
								.executes(HameauCommande::renaitre)))
				.then(Commands.literal("recruter")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("prenom", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms)
								.executes(HameauCommande::recruter)))
				.then(Commands.literal("liberer")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("prenom", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms)
								.executes(HameauCommande::liberer)))
				.then(Commands.literal("presenter")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("prenom", StringArgumentType.greedyString()).suggests(HameauCommande::prenoms)
								.executes(c -> presenter(c, nomme(c, StringArgumentType.getString(c, "prenom"))))))
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
						.executes(HameauCommande::modeles)
						.then(Commands.argument("modele", StringArgumentType.word())
								.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(HameauConfig.get().modeles.keySet(), b))
								.executes(c -> modele(c, StringArgumentType.getString(c, "modele"), null))
								.then(Commands.literal("plans").then(Commands.argument("plans", StringArgumentType.word())
										.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(HameauConfig.get().modeles.keySet(), b))
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

	private record Aide(String nom, String usage, boolean ops, String resume, List<String> detail) {
	}

	private static final List<Aide> AIDES = List.of(
			new Aide("help", "/hameau help [commande]", false, "Cette liste, ou le détail d'une commande.",
					List.of("Exemple : /hameau help faire", "Les prénoms se complètent avec Tab et s'écrivent avec ou sans accents.")),
			new Aide("qui", "/hameau qui", false, "Les villageois des environs : prénom, métier, humeur, distance.",
					List.of("Pratique pour retrouver le prénom de quelqu'un avant de lui parler.")),
			new Aide("village", "/hameau village", false, "Le nom du village où tu te trouves, et ce qui le rend différent des autres.",
					List.of("Chaque village a sa culture, inventée par Claude : ses habitants en tirent leurs prénoms, leur parler, leurs coutumes.")),
			new Aide("journal", "/hameau journal [tout|livre]", false, "Ce qui s'est passé au village depuis ta dernière lecture : chantiers, annonces, arrivées, morts, larcins.",
					List.of("/hameau journal tout : tout ce que le village a retenu (80 faits au plus).", "/hameau journal livre : te donne le journal sous forme de livre.",
							"Sans village à moins de 128 blocs : le journal du village le plus actif.")),
			new Aide("chantier", "/hameau chantier <valider|ici|annuler> <prénom>", false, "Ton avis sur l'emplacement qu'un villageois te montre avant de bâtir.",
					List.of("Quand un villageois s'apprête à bâtir près de toi, il balise l'emplacement de particules et attend 45 secondes : clique sur un des boutons du chat.",
							"« ici » : place-toi au centre de l'endroit voulu, il bâtira autour de toi. Sans réponse, il commence à l'endroit balisé.")),
			new Aide("ordres", "/hameau ordres [prénom]", false, "Ouvre le menu d'ordres d'un villageois à ton service.",
					List.of("Plus simple : accroupi, main vide, clic droit sur lui. Chaque objet du menu est un ordre : suivre, rester, miner, couper, ranger…",
							"Sans prénom : celui de tes serviteurs qui est le plus proche.")),
			new Aide("etat", "/hameau etat", false, "Hameau tourne-t-il ? Dépense du jour et totale, modèle, villageois éveillés.",
					List.of("« plafond de dépense atteint » : les villageois se taisent jusqu'à demain, ou jusqu'à ce qu'on relève le plafond dans config/hameau.json.")),
			new Aide("ame", "/hameau ame [prénom]", true, "La fiche intime d'un villageois : caractère, histoire, liens, opinions, projet, souvenirs.",
					List.of("Sans prénom : le villageois le plus proche de toi.", "Exemple : /hameau ame Josselin")),
			new Aide("penser", "/hameau penser [prénom]", true, "Le fait réfléchir tout de suite, sans attendre son tour.",
					List.of("Sans prénom : le plus proche. Coûte un appel à Claude.", "Utile quand un villageois reste planté là après un événement.")),
			new Aide("dire", "/hameau dire <prénom> <texte>", true, "Lui parler d'où que tu sois, sans être à côté de lui.",
					List.of("Exemple : /hameau dire Odile rejoins-moi sur la place", "Près de lui, pas besoin de commande : écris dans le chat en citant son prénom.",
							"Tu ne lis sa réponse que si tu es à moins de 24 blocs de lui.")),
			new Aide("faire", "/hameau faire <prénom> <action> [cible] [; action cible…]", true, "Lui impose une action, sans demander son avis à Claude.",
					List.of("Actions : aller, suivre, fuir, donner, fabriquer, danser, manger, frapper, couper, casser, miner, labourer, recolter, ramasser, poser, ecrire, utiliser, batir, prendre, deposer, arreter.",
							"/hameau faire Perrin miner fer 8   (il creuse sa galerie jusqu'au filon)",
							"/hameau faire Perrin frapper vache ; ramasser   (chasse)",
							"Cible : un prénom ou un pseudo, « maison », « travail », « place », ou des coordonnées x y z.",
							"Exemples : /hameau faire Perrin aller place",
							"/hameau faire Perrin couper 3 64 9 ; aller place",
							"/hameau faire Perrin batir 10 64 10 une cabane de pêcheur",
							"/hameau faire Perrin fabriquer cake   (identifiant Minecraft en anglais)")),
			new Aide("personnalite", "/hameau personnalite <prénom> <demande>", true, "Change son caractère : décris en français ce que tu veux, Claude réécrit sa fiche.",
					List.of("Exemples : /hameau personnalite Josselin un vieux pirate grincheux qui déteste les poules",
							"/hameau personnalite Odile elle devient timide et parle en rimes",
							"/hameau personnalite Raoul il s'appelle maintenant Barberousse",
							"Il garde ses souvenirs et ses relations. Coûte un appel à Claude.")),
			new Aide("renaitre", "/hameau renaitre <prénom|tous>", true, "Claude lui invente une identité toute neuve : prénom, caractère, histoire.",
					List.of("Il garde ses souvenirs et ses relations ; les autres le connaissent sous son nouveau prénom.",
							"« tous » : tous les villageois connus, au fur et à mesure qu'un joueur passe près d'eux. Un appel à Claude par villageois.")),
			new Aide("recruter", "/hameau recruter <prénom> [joueur]", true, "Le met à ton service d'office : il te suit et exécute tes ordres sans discuter.",
					List.of("Sans cette commande, tu peux aussi le convaincre toi-même en lui parlant : paie-le, promets, menace… c'est lui qui décide.",
							"Une fois à ton service : accroupi, main vide, clic droit sur lui ouvre le menu d'ordres (suivre, rester, miner, couper, ranger…). Tu peux aussi le lui dire de vive voix.",
							"/hameau recruter Perrin Etiennoo le met au service d'un autre joueur. /hameau liberer Perrin le congédie.")),
			new Aide("liberer", "/hameau liberer <prénom>", true, "Le congédie : il n'est plus au service de personne.", List.of()),
			new Aide("presenter", "/hameau presenter <prénom>", true, "Fait d'un étranger un habitant du village où il se trouve.",
					List.of("Un villageois apparu par /summon ou par un œuf reste un étranger, sans village, tant qu'on ne l'a pas présenté.",
							"Les villageois présents assistent à la présentation et s'en souviennent.")),
			new Aide("univers", "/hameau univers [texte|rien]", true, "Le décor de ce serveur, que tous les villageois et tous les villages prennent en compte.",
					List.of("Exemple : /hameau univers Le monde sort d'une longue guerre contre les pillards ; tout le monde se méfie des inconnus et la magie est interdite.",
							"Sans texte : affiche le décor actuel. « rien » : l'efface. Effet immédiat sur les réflexions ; les personnalités déjà inventées ne changent pas (/hameau renaitre).",
							"Pour aller plus loin, les textes donnés à Claude sont dans config/hameau/ sur le serveur : esprit.txt (comportement des villageois), naissance.txt (genre de personnages inventés), village.txt (genre de villages). Après modification : /hameau recharger.")),
			new Aide("voix", "/hameau voix [prénom] [voix]", false, "La voix parlée : état, voix disponibles, voix de chacun, choix des services.",
					List.of("Pour les entendre, installe le mod Simple Voice Chat (Fabric) : les villageois parlent alors à voix haute, en 3D. Leur volume se règle dans Simple Voice Chat, catégorie « Villageois ».",
							"Tu peux aussi leur parler au micro : à moins de 10 blocs d'un villageois, regarde-le (ou dis son prénom) et parle. Ce qui a été compris s'affiche en gris dans ton chat.",
							"/hameau voix : ce qui marche, les quotas du jour, la liste des voix.",
							"/hameau voix Josselin Bill : donne la voix « Bill » à Josselin (opérateurs).",
							"/hameau voix synthese <moteur> : quel service fait parler les villageois. /hameau voix transcription <moteur> : lequel comprend ton micro. Les deux peuvent différer.",
							"Moteurs fournis : elevenlabs, openai, mistral et groq (transcription seulement). D'autres s'ajoutent dans config/hameau.json (« voix.moteurs ») ; clé dans config/hameau-cles/<moteur>.txt.",
							"/hameau voix essai [phrase] : fait dire une phrase puis la fait transcrire, et te dit ce qui a marché.")),
			new Aide("oubli", "/hameau oubli <joueur>", true, "Tous les villageois oublient ce joueur : griefs, opinions, souvenirs.",
					List.of("Exemple : /hameau oubli Etiennoo", "Pour repartir de zéro après une bagarre qui a mal tourné.")),
			new Aide("modele", "/hameau modele [profil] [plans <profil>]", true, "Change le modèle qui fait penser les villageois, sans redémarrer. Sans rien : la liste des profils.",
					List.of("Profils fournis : haiku, sonnet, opus (Anthropic), mistral, mistral-large, qwen, openai, openrouter. Tu peux en ajouter dans config/hameau.json (« modeles ») : toute API compatible OpenAI convient.",
							"La clé de chaque service va dans config/hameau-cles/<profil>.txt sur le serveur (jamais dans le chat), puis /hameau recharger.",
							"« plans » règle à part le modèle qui dessine les constructions.", "Exemple : /hameau modele mistral plans opus",
							"Pense à renseigner les prix du profil, sinon le plafond de dépense ne compte rien.")),
			new Aide("pause", "/hameau pause", true, "Suspend tous les appels à Claude : les villageois ne réfléchissent plus.", List.of("/hameau reprendre pour relancer.")),
			new Aide("reprendre", "/hameau reprendre", true, "Relance les appels à Claude après une pause.", List.of()),
			new Aide("recharger", "/hameau recharger", true, "Relit config/hameau.json, les textes de config/hameau/ et la clé API, sans redémarrer.", List.of()));

	private static Component cliquable(final Aide aide, final ChatFormatting couleur) {
		String saisie = aide.usage().split(" [<\\[]", 2)[0] + (aide.usage().contains("<") || aide.usage().contains("[") ? " " : "");
		return Component.literal(aide.usage()).withStyle(style -> style.withColor(couleur)
				.withClickEvent(new ClickEvent.SuggestCommand(saisie))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Clique pour l'écrire dans le chat"))));
	}

	private static int aide(final CommandContext<CommandSourceStack> c, final String commande) {
		boolean op = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(c.getSource());
		if (commande != null) {
			String cherche = Ames.simplifier(commande);
			for (Aide aide : AIDES) {
				if (aide.nom().equals(cherche)) {
					c.getSource().sendSuccess(() -> cliquable(aide, ChatFormatting.YELLOW), false);
					ligne(c, aide.resume() + (aide.ops() ? " (réservé aux opérateurs)" : ""), ChatFormatting.WHITE);
					aide.detail().forEach(texte -> ligne(c, texte, ChatFormatting.GRAY));
					return 1;
				}
			}
			ligne(c, "Pas de commande « " + commande + " ». Voici celles qui existent :", ChatFormatting.RED);
		}
		ligne(c, "Hameau — les commandes" + (op ? "" : " (les grisées sont réservées aux opérateurs)"), ChatFormatting.GOLD);
		for (Aide aide : AIDES) {
			c.getSource().sendSuccess(() -> Component.empty().append(cliquable(aide, aide.ops() && !op ? ChatFormatting.DARK_GRAY : ChatFormatting.YELLOW))
					.append(Component.literal(" — " + aide.resume()).withStyle(ChatFormatting.GRAY)), false);
		}
		ligne(c, "/hameau help <commande> pour le détail et des exemples.", ChatFormatting.GOLD);
		ligne(c, "Sans commande : parle dans le chat près d'un villageois (cite son prénom pour t'adresser à lui) ; accroupi + clic droit avec un objet pour le lui offrir.", ChatFormatting.GRAY);
		return 1;
	}

	private static int village(final CommandContext<CommandSourceStack> c) {
		Ames.Village village = Ames.villageVers((int) c.getSource().getPosition().x, (int) c.getSource().getPosition().z);
		if (village == null || village.nom == null) {
			ligne(c, village == null ? "Aucun village connu ici. Il sera fondé dès qu'un villageois des environs s'éveillera près d'un joueur."
					: "Ce village n'a pas encore de nom : Claude est en train de l'inventer.", ChatFormatting.GRAY);
			return 0;
		}
		int habitants = 0;
		for (Ame ame : Ames.toutes()) {
			habitants += ame.village != null && ame.village == village.id ? 1 : 0;
		}
		ligne(c, village.nom + " — " + habitants + " habitants", ChatFormatting.YELLOW);
		ligne(c, village.culture, ChatFormatting.GRAY);
		village.chronique.forEach(fait -> ligne(c, "• " + fait, ChatFormatting.GOLD));
		return 1;
	}

	private static int univers(final CommandContext<CommandSourceStack> c, final String texte) {
		if (texte == null) {
			ligne(c, Textes.univers.isBlank() ? "Aucun décor défini. /hameau univers <texte> pour en donner un." : "Décor actuel : " + Textes.univers.strip(), ChatFormatting.YELLOW);
			return 1;
		}
		boolean efface = texte.trim().equalsIgnoreCase("rien");
		Textes.changerUnivers(efface ? "" : texte.trim());
		Cerveau.rafraichir();
		ligne(c, efface ? "Décor effacé." : "Décor enregistré : les villageois en tiennent compte dès leur prochaine réflexion.", ChatFormatting.YELLOW);
		return 1;
	}

	private static int voix(final CommandContext<CommandSourceStack> c, final String arguments) throws CommandSyntaxException {
		if (arguments == null) {
			ligne(c, "Voix — " + Voix.etat() + ".", ChatFormatting.YELLOW);
			List<String> noms = new ArrayList<>();
			for (Voix.Timbre timbre : Voix.catalogue()) {
				noms.add(timbre.prenom() + " (" + switch (timbre.genre()) {
					case "female" -> "femme";
					case "male" -> "homme";
					default -> "neutre";
				} + ")");
			}
			if (!noms.isEmpty()) {
				ligne(c, String.join(", ", noms), ChatFormatting.GRAY);
			}
			return 1;
		}
		String[] parties = arguments.trim().split("\\s+", 2);
		Ame ame = Ames.de(nomme(c, parties[0]));
		if (parties.length > 1) {
			Voix.Timbre timbre = Voix.parNom(parties[1]);
			if (timbre == null) {
				ligne(c, "Pas de voix « " + parties[1] + " ». /hameau voix pour la liste.", ChatFormatting.RED);
				return 0;
			}
			Voix.donner(ame, timbre);
		}
		ligne(c, ame.voixNom == null ? ame.nom + " n'a pas encore de voix : elle sera choisie à sa première réplique entendue." : ame.nom + " parle avec la voix « " + ame.voixNom + " ».", ChatFormatting.YELLOW);
		return 1;
	}

	private static int journal(final CommandContext<CommandSourceStack> c, final String mode) {
		CommandSourceStack source = c.getSource();
		Ames.Village village = Ames.villageVers((int) source.getPosition().x, (int) source.getPosition().z);
		if (village == null || village.journal.isEmpty()) {
			for (Ames.Village autre : Ames.villages()) {
				if (!autre.journal.isEmpty() && (village == null || village.journal.isEmpty() || autre.inscrits > village.inscrits)) {
					village = autre;
				}
			}
		}
		if (village == null || village.journal.isEmpty()) {
			ligne(c, "Rien n'a encore été consigné : le journal se remplit au fil de la vie du village.", ChatFormatting.GRAY);
			return 0;
		}
		String lecteur = source.getTextName();
		String nom = village.nom != null ? village.nom : "village sans nom";
		List<String> faits = village.journal;
		if (mode.equals("livre") && source.getEntity() instanceof net.minecraft.server.level.ServerPlayer joueur) {
			// Une page de livre tient environ 230 caractères.
			List<net.minecraft.server.network.Filterable<Component>> pages = new ArrayList<>();
			StringBuilder page = new StringBuilder();
			for (String fait : faits.subList(Math.max(0, faits.size() - 60), faits.size())) {
				if (page.length() + fait.length() > 230 && !page.isEmpty()) {
					pages.add(net.minecraft.server.network.Filterable.passThrough(Component.literal(page.toString())));
					page = new StringBuilder();
				}
				page.append(fait.length() > 230 ? fait.substring(0, 230) : fait).append("\n\n");
			}
			pages.add(net.minecraft.server.network.Filterable.passThrough(Component.literal(page.toString())));
			net.minecraft.world.item.ItemStack livre = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
			String titre = "Journal de " + nom;
			livre.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT, new net.minecraft.world.item.component.WrittenBookContent(
					net.minecraft.server.network.Filterable.passThrough(titre.length() > 32 ? titre.substring(0, 32) : titre), "Le village", 0, pages, true));
			if (!joueur.getInventory().add(livre)) {
				joueur.spawnAtLocation(joueur.level(), livre);
			}
			ligne(c, "Le journal de " + nom + " est dans ton inventaire.", ChatFormatting.YELLOW);
		} else {
			int nouveaux = Math.min(faits.size(), village.inscrits - village.lus.getOrDefault(lecteur, 0));
			int montres = mode.equals("tout") ? faits.size() : nouveaux > 0 ? Math.min(nouveaux, 20) : Math.min(8, faits.size());
			ligne(c, "Journal de " + nom + " — " + (mode.equals("tout") ? "tout" : nouveaux > 0 ? nouveaux + " nouveauté" + (nouveaux > 1 ? "s" : "") : "rien de neuf, voici les derniers faits"), ChatFormatting.GOLD);
			faits.subList(faits.size() - montres, faits.size()).forEach(fait -> ligne(c, fait, ChatFormatting.GRAY));
		}
		village.lus.put(lecteur, village.inscrits);
		return 1;
	}

	private static int chantier(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		if (!(c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer joueur)) {
			ligne(c, "Seul un joueur peut donner son avis sur un chantier.", ChatFormatting.RED);
			return 0;
		}
		Villager villageois = nomme(c, StringArgumentType.getString(c, "prenom"));
		String choix = StringArgumentType.getString(c, "choix").toLowerCase();
		if (joueur.distanceTo(villageois) > 48 || !List.of("valider", "ici", "annuler").contains(choix)) {
			ligne(c, joueur.distanceTo(villageois) > 48 ? "Tu es trop loin de lui." : "Choix possibles : valider, ici, annuler.", ChatFormatting.RED);
			return 0;
		}
		String reponse = Chantiers.decider(c.getSource().getServer(), villageois, Ames.de(villageois), choix, joueur);
		ligne(c, reponse != null ? reponse : Ames.de(villageois).nom + " n'attend aucun avis en ce moment.", reponse != null ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
		return reponse != null ? 1 : 0;
	}

	private static int ordres(final CommandContext<CommandSourceStack> c, final String prenom) throws CommandSyntaxException {
		if (!(c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer joueur)) {
			ligne(c, "Seul un joueur peut ouvrir le menu d'ordres.", ChatFormatting.RED);
			return 0;
		}
		String maitre = joueur.getName().getString();
		Villager villageois = null;
		if (prenom != null) {
			villageois = nomme(c, prenom);
		} else {
			for (Villager candidat : alentour(c.getSource())) {
				if (maitre.equals(Ames.de(candidat).maitre)) {
					villageois = candidat;
					break;
				}
			}
		}
		if (villageois == null || !maitre.equals(Ames.de(villageois).maitre)) {
			ligne(c, villageois == null ? "Personne n'est à ton service dans les parages. /hameau help recruter" : Ames.de(villageois).nom + " n'est pas à ton service.", ChatFormatting.RED);
			return 0;
		}
		MenuOrdres.ouvrir(joueur, villageois, Ames.de(villageois));
		return 1;
	}

	private static int personnalite(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		String[] parties = StringArgumentType.getString(c, "prenom et demande").trim().split("\\s+", 2);
		if (parties.length < 2) {
			throw new SimpleCommandExceptionType(Component.literal("Usage : /hameau personnalite <prénom> <ce que tu veux changer>")).create();
		}
		Villager villageois = nomme(c, parties[0]);
		Ame ame = Ames.de(villageois);
		if (!Cerveau.pret() || !Ames.budget.autorise()) {
			// Sans Claude pour réécrire la fiche, la demande devient telle quelle le fond du personnage.
			ame.histoire = "Voici qui tu es, avant tout le reste : " + parties[1];
			ligne(c, "Claude est indisponible (clé absente ou plafond atteint) : la demande est ajoutée telle quelle à la fiche de " + ame.nom + ".", ChatFormatting.YELLOW);
			return 1;
		}
		ame.consigne = parties[1];
		ame.ebauche = true;
		ame.echecsNaissance = 0;
		ame.prochaineNaissance = 0;
		if (ame.enCours) {
			ligne(c, ame.nom + " est en pleine réflexion : il changera dans quelques secondes.", ChatFormatting.YELLOW);
			return 1;
		}
		Vie.naitre(c.getSource().getServer(), villageois, ame, c.getSource());
		ligne(c, "Claude réécrit " + ame.nom + "…", ChatFormatting.YELLOW);
		return 1;
	}

	private static int renaitre(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		String prenom = StringArgumentType.getString(c, "prenom").trim();
		List<Ame> ames = new ArrayList<>();
		if (prenom.equalsIgnoreCase("tous")) {
			ames.addAll(Ames.toutes());
		} else {
			ames.add(Ames.de(nomme(c, prenom)));
		}
		for (Ame ame : ames) {
			ame.consigne = null;
			ame.ebauche = true;
			ame.echecsNaissance = 0;
			ame.prochaineNaissance = 0;
		}
		ligne(c, (ames.size() == 1 ? ames.getFirst().nom + " va renaître" : ames.size() + " villageois vont renaître") + " : nouvelle identité dans quelques secondes, dès qu'un joueur est à portée.", ChatFormatting.YELLOW);
		return ames.size();
	}

	private static int recruter(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		String[] parties = StringArgumentType.getString(c, "prenom").trim().split("\\s+", 2);
		Villager villageois = nomme(c, parties[0]);
		Ame ame = Ames.de(villageois);
		String maitre = parties.length > 1 ? parties[1].trim() : c.getSource().getTextName();
		if (c.getSource().getServer().getPlayerList().getPlayerByName(maitre) == null && Ames.parNom(maitre) == null) {
			ligne(c, "Personne ne s'appelle « " + maitre + " » : ni joueur connecté, ni villageois.", ChatFormatting.RED);
			return 0;
		}
		ame.noter("Te voilà au service de " + maitre + " : c'est ainsi, et tu t'y fais à ta manière.");
		Vie.engager(villageois, ame, maitre);
		ame.presser(c.getSource().getServer().getTickCount(), 2);
		ligne(c, ame.nom + " est au service de " + maitre + ".", ChatFormatting.YELLOW);
		return 1;
	}

	private static int liberer(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		Villager villageois = nomme(c, StringArgumentType.getString(c, "prenom"));
		Ame ame = Ames.de(villageois);
		if (ame.maitre == null) {
			ligne(c, ame.nom + " n'est au service de personne.", ChatFormatting.GRAY);
			return 0;
		}
		ame.noter(ame.maitre + " t'a rendu ta liberté.");
		Vie.affranchir(villageois, ame);
		ligne(c, ame.nom + " est libre.", ChatFormatting.YELLOW);
		return 1;
	}

	private static int presenter(final CommandContext<CommandSourceStack> c, final Villager villageois) {
		Ame ame = Ames.de(villageois);
		if (!ame.etranger) {
			Ames.Village sien = Ames.village(ame);
			ligne(c, ame.nom + " est déjà d'ici" + (sien != null && sien.nom != null ? " : il est de " + sien.nom + "." : "."), ChatFormatting.GRAY);
			return 0;
		}
		ame.etranger = false;
		villageois.removeTag(Ames.TAG_ETRANGER);
		Ames.Village village = Ames.rattacher(villageois, ame);
		String ou = village.nom != null ? "au village de " + village.nom : "au village";
		String qui = c.getSource().getTextName();
		long maintenant = c.getSource().getServer().getTickCount();
		ame.noter(qui + " t'a présenté " + ou + " : tu en fais désormais partie, on t'y a accueilli comme l'un des leurs.");
		ame.retenir("(" + Perception.moment((ServerLevel) villageois.level()) + ") " + qui + " t'a présenté " + ou + ", qui t'a adopté.");
		ame.presser(maintenant, 2);
		Vie.temoins(villageois, null, qui + " vient de présenter " + ame.nom + " " + ou + " : cet étranger venu d'ailleurs est maintenant des vôtres.", true);
		ligne(c, ame.nom + " est présenté " + ou + " : il en fait partie.", ChatFormatting.YELLOW);
		return 1;
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
		ligne(c, "Modèle " + config.profil + " : " + config.modele + " (plans : " + config.batir.modele + "), une réflexion toutes les " + config.intervallePensee + " s environ, vie hors ligne : " + (config.horsLigne() ? "oui" : "non") + ".", ChatFormatting.GRAY);
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
		Ames.Village village = Ames.village(ame);
		if (ame.ebauche) {
			ligne(c, "Brouillon tiré au sort : Claude n'a pas encore inventé sa personnalité.", ChatFormatting.RED);
		}
		ligne(c, ame.etranger ? "Étranger, sans village (/hameau presenter pour l'y faire entrer)." : village != null && village.nom != null ? "Du village de " + village.nom + "." : "Village pas encore fondé.", ChatFormatting.GRAY);
		if (ame.histoire != null) {
			ligne(c, ame.histoire, ChatFormatting.GRAY);
		}
		if (ame.role != null || ame.maitre != null) {
			ligne(c, (ame.role != null ? "Place au village : " + ame.role + ". " : "") + (ame.maitre != null ? "Au service de " + ame.maitre + (ame.suit ? " (le suit)." : " (attend sur place).") : ""), ChatFormatting.GOLD);
		}
		ligne(c, "Manie : " + ame.manie + ". Parle : " + ame.parler + ".", ChatFormatting.GRAY);
		ligne(c, "Désire : " + ame.desir + ". Craint : " + ame.peur + ". Humeur : " + ame.humeur + ".", ChatFormatting.GRAY);
		for (String lien : ame.liens) {
			ligne(c, "♦ " + lien, ChatFormatting.GOLD);
		}
		if (ame.chantier != null) {
			ligne(c, "Chantier : « " + ame.chantier.nom + " » en " + ame.chantier.x + " " + ame.chantier.y + " " + ame.chantier.z + ", reste " + ame.chantier.restants.size() + " blocs sur " + ame.chantier.total + ".", ChatFormatting.GOLD);
		}
		String activite = Actions.activite(villageois.getUUID());
		if (activite != null) {
			ligne(c, "En train de : " + activite, ChatFormatting.GRAY);
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
		Cerveau.Decision decision = new Cerveau.Decision(null, null, null, null, false, premiere.action(), premiere.cible(), premiere.objet(), etapes, null, null, null, Map.of(), null, null, null, null, 0, 0);
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

	private static String cle(final String profil, final String url) {
		return Cles.lire(profil) != null || Cles.local(url) ? "clé présente" : "PAS DE CLÉ : config/hameau-cles/" + profil.toLowerCase() + ".txt";
	}

	private static int modeles(final CommandContext<CommandSourceStack> c) {
		HameauConfig config = HameauConfig.get();
		ligne(c, "Réflexions : " + config.profil + " (" + config.modele + ") — plans : " + config.batir.profil + " (" + config.batir.modele + ").", ChatFormatting.YELLOW);
		config.modeles.forEach((nom, modele) -> ligne(c, nom + " — " + (modele.id.isBlank() ? "identifiant à renseigner" : modele.id) + " — " + (modele.url.isBlank() ? "Anthropic" : modele.url) + " — " + cle(nom, modele.url)
				+ (modele.prixEntree == 0 && modele.prixSortie == 0 ? " — prix non renseignés" : ""), nom.equals(config.profil) ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		ligne(c, "/hameau modele <nom> [plans <nom>] pour changer. Les profils se règlent dans config/hameau.json (« modeles »), puis /hameau recharger.", ChatFormatting.GOLD);
		return 1;
	}

	private static int modele(final CommandContext<CommandSourceStack> c, final String reflexions, final String plans) {
		HameauConfig config = HameauConfig.get();
		if (!config.choisirModele(reflexions) || (plans != null && !config.choisirModelePlans(plans))) {
			ligne(c, "Profil inconnu, ou sans identifiant de modèle. Profils : " + String.join(", ", config.modeles.keySet()) + ".", ChatFormatting.RED);
			return 0;
		}
		HameauConfig.sauvegarder();
		ligne(c, "Réflexions : " + config.profil + " (" + config.modele + ") — plans de construction : " + config.batir.profil + " (" + config.batir.modele + "). Effet immédiat, réglage conservé.", ChatFormatting.YELLOW);
		if (!Cerveau.pret()) {
			ligne(c, "Attention, " + cle(config.profil, config.url) + ". Mets-y la clé, puis /hameau recharger : d'ici là les villageois se taisent.", ChatFormatting.RED);
		} else if (config.prixEntreeParMillion == 0 && config.prixSortieParMillion == 0) {
			ligne(c, "Les prix de ce profil ne sont pas renseignés : la dépense comptée restera à zéro et le plafond ne protégera plus rien.", ChatFormatting.RED);
		}
		return 1;
	}

	private static int moteur(final CommandContext<CommandSourceStack> c, final boolean synthese, final String nom) {
		HameauConfig.Voix voix = HameauConfig.get().voix;
		HameauConfig.Moteur moteur = voix.moteurs.get(nom.toLowerCase());
		String modele = moteur == null ? null : synthese ? moteur.modeleVoix : moteur.modeleTranscription;
		if (modele == null || modele.isBlank()) {
			ligne(c, moteur == null ? "Moteur inconnu. Moteurs : " + String.join(", ", voix.moteurs.keySet()) + "." : "« " + nom + " » n'a pas de modèle de " + (synthese ? "synthèse" : "transcription") + " (config/hameau.json).", ChatFormatting.RED);
			return 0;
		}
		if (synthese) {
			voix.synthese = nom.toLowerCase();
		} else {
			voix.transcription = nom.toLowerCase();
		}
		HameauConfig.sauvegarder();
		Voix.demarrer();
		ligne(c, (synthese ? "Les villageois parlent désormais avec " : "Le micro est désormais transcrit par ") + nom + " (" + modele + "). " + cle(nom, moteur.url) + "."
				+ (synthese ? " Chaque villageois recevra une voix de ce moteur à sa prochaine réplique." : ""), ChatFormatting.YELLOW);
		ligne(c, "/hameau voix essai pour vérifier que ça répond.", ChatFormatting.GRAY);
		return 1;
	}

	private static int essai(final CommandContext<CommandSourceStack> c, final String texte) {
		List<Villager> proches = c.getSource().getLevel().getEntitiesOfClass(Villager.class, AABB.ofSize(c.getSource().getPosition(), 32, 16, 32), Entity::isAlive);
		proches.sort(Comparator.comparingDouble(v -> v.distanceToSqr(c.getSource().getPosition())));
		ligne(c, "Essai de la voix…", ChatFormatting.GRAY);
		Voix.essai(c.getSource(), proches.isEmpty() ? null : proches.getFirst(), texte.length() > 200 ? texte.substring(0, 200) : texte);
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
		Voix.demarrer();
		ligne(c, "Réglages, textes et clés relus." + (Cerveau.pret() ? "" : " Aucune clé API trouvée."), ChatFormatting.YELLOW);
		return 1;
	}
}
