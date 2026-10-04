package com.ncombarieu.hameau;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/** L'appel à Claude : une fiche de situation en entrée, une décision en sortie. */
public final class Cerveau {
	private static final Path FICHIER_CLE = FabricLoader.getInstance().getConfigDir().resolve("hameau-cle.txt");
	private static final ExecutorService FILS = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("hameau-", 0).factory());

	private static AnthropicClient client;
	private static String systeme;

	private Cerveau() {
	}

	public record Etape(String action, String cible, String objet) {
	}

	public record Decision(String pensee, String geste, String parole, String a, boolean prive, String action, String cible, String objet,
			java.util.List<Etape> suite, String emotion, String humeur, String projet, Map<String, Integer> relations, String souvenir, long tokensEntree, long tokensSortie) {
	}

	/** Plan de construction : une palette de blocs et des couches empilées, de bas en haut. */
	public record Plan(String nom, Map<String, String> palette, java.util.List<java.util.List<String>> couches, long tokensEntree, long tokensSortie, boolean secours, long perdusEntree, long perdusSortie) {
	}

	/** Une personnalité inventée par Claude. Les champs absents de la réponse sont null. */
	public record Persona(String nom, Boolean femme, java.util.List<String> traits, String manie, String parler, String desir, String peur, String histoire, String voix,
			String lienAvec, String lienPourToi, String lienPourLui, int opinion, int opinionDeLui, long tokensEntree, long tokensSortie) {
		boolean complete() {
			return !traits.isEmpty() && manie != null && parler != null && desir != null && peur != null;
		}
	}

	public record Fondation(String nom, String culture, long tokensEntree, long tokensSortie) {
	}

	public static boolean pret() {
		return client != null;
	}

	/** La clé vient de la variable ANTHROPIC_API_KEY, sinon de config/hameau-cle.txt. */
	public static void demarrer() {
		Textes.charger();
		systeme = consignes();
		String cle = System.getenv("ANTHROPIC_API_KEY");
		if (cle == null || cle.isBlank()) {
			try {
				cle = Files.exists(FICHIER_CLE) ? Files.readString(FICHIER_CLE, StandardCharsets.UTF_8).trim() : null;
			} catch (IOException e) {
				Hameau.LOGGER.error("Impossible de lire {}", FICHIER_CLE, e);
			}
		}
		if (cle == null || cle.isBlank()) {
			client = null;
			Hameau.LOGGER.warn("Hameau : aucune clé API (ANTHROPIC_API_KEY ou {}). Les villageois resteront muets.", FICHIER_CLE);
			return;
		}
		client = AnthropicOkHttpClient.builder().apiKey(cle).maxRetries(1).timeout(Duration.ofSeconds(40)).build();
		systeme = consignes();
	}

	/** À rappeler quand un texte de Textes change. */
	static void rafraichir() {
		systeme = consignes();
	}

	private static String consignes() {
		HameauConfig.Autonomie autonomie = HameauConfig.get().autonomie;
		StringBuilder s = new StringBuilder(Textes.esprit).append(Textes.decor()).append("""

				Réponds UNIQUEMENT par un objet JSON, sans rien autour :
				{"pensee":"ce que tu te dis, une phrase","geste":"ce qu'on te voit faire, à la 3e personne, très court, ex. « hausse les épaules en riant »","parole":"ce que tu dis, ou null","a":"à qui tu parles, ou null","prive":false,"action":"...","cible":"...","objet":"...","suite":[],"emotion":"joie|colere|amour|tristesse|peur|rire|surprise|neutre","humeur":"un ou deux mots","projet":"ce que tu comptes faire dans les heures qui viennent","relations":{"Nom":entier de -15 à 15},"souvenir":"fait important à retenir longtemps, ou null"}

				"prive" : true si tu chuchotes ; seul ton interlocuteur entend alors.
				"suite" : pour mener une tâche jusqu'au bout, la liste des actions suivantes, enchaînées sans que tu aies à y repenser, 5 au plus, ex. [{"action":"couper","cible":"3 64 9"},{"action":"aller","cible":"place"},{"action":"donner","cible":"Odile","objet":"bûche"}]. Sinon [].
				Actions possibles :
				- "rien" : poursuivre ce que tu fais déjà (ton activité en cours si ta fiche en indique une, sinon ta routine).
				- "arreter" : abandonner pour de bon ton activité en cours ; un chantier abandonné reste inachevé. Parler ne t'oblige pas à arrêter : tu peux répondre à quelqu'un tout en continuant, avec "rien".
				- "aller" : te rendre quelque part. cible = nom d'une personne proche, "maison", "travail", "place" ou "x y z".
				- "suivre" : accompagner quelqu'un un moment. cible = nom.
				- "fuir" : t'éloigner de quelqu'un. cible = nom.
				- "donner" : offrir un objet de ton inventaire. cible = nom, objet = nom de l'objet.
				- "fabriquer" : confectionner de tes mains un objet courant (nourriture, outil, fleur, meuble, vêtement, graines, jouet…) qui va dans ton inventaire. objet = identifiant Minecraft en anglais, ex. "cake", "bread", "oak_sign", "torch", "wheat_seeds", "3 cookie".
				- "danser" : danser, sauter de joie, faire la fête.
				""");
		if (autonomie.frapper) {
			s.append("- \"frapper\" : donner des coups à quelqu'un. Normal pour riposter quand on te frappe, pour relever un défi ou une bagarre qu'on te propose, ou quand la colère déborde ; selon ton caractère tu cognes volontiers ou tu t'y refuses. Les témoins s'en souviendront. cible = nom.\n");
		}
		if (autonomie.casser) {
			s.append("- \"couper\" : abattre un arbre à la hache ; le bois va dans ton inventaire. cible = \"x y z\" du pied du tronc.\n");
			s.append("- \"casser\" : casser ou creuser un bloc avec l'outil qui convient (pioche, pelle, hache, main) ; tu ramasses ce qu'il donne. cible = \"x y z\".\n");
			s.append("- \"labourer\" : retourner un bloc de terre à la houe pour le cultiver. cible = \"x y z\".\n");
		}
		if (autonomie.poser) {
			s.append("- \"poser\" : poser un bloc de ton inventaire. cible = \"x y z\", objet = nom du bloc.\n");
		}
		if (autonomie.poser) {
			s.append("- \"ecrire\" : planter une pancarte et y écrire quelques mots (message, enseigne, annonce, avertissement, moquerie, mot doux). cible = \"x y z\" ou rien pour l'écrire devant toi, objet = le texte, 50 caractères au plus.\n");
			s.append("- \"utiliser\" : actionner quelque chose : ouvrir ou fermer une porte, une trappe, un portillon, sonner la cloche du village, basculer un levier. cible = \"x y z\".\n");
		}
		if (autonomie.batir) {
			s.append("- \"batir\" : construire ou aménager ce que tu imagines, jusqu'à 15 blocs de côté : maison, villa, tour, pont, puits, étal de marché, enclos, potager, bassin, fontaine, statue, monument, autel, cachette, piège, tombe, scène, terrain de jeu… cible = \"x y z\" du centre de l'emplacement (si on te dit « ici », ce sont les coordonnées de celui qui te parle), objet = description de ce que tu veux réaliser, en une phrase. Si ta fiche indique un chantier en cours, \"batir\" sans objet le reprend. Avec cible = nom de quelqu'un qui a un chantier, tu vas l'aider.\n");
		}
		if (autonomie.prendreDansCoffres) {
			s.append("- \"prendre\" : prendre ce que contient un coffre. C'est un vol si ce n'est pas à toi. cible = \"x y z\".\n");
		}
		s.append("""

				Sois bref : pensée 15 mots au plus, geste 8 mots, parole 25 mots, projet 12 mots, souvenir 15 mots. JSON compact, sans ``` ni commentaire.
				Règles : français parlé, vivant. Donne presque toujours un "geste" : c'est ce que les autres voient de toi, même quand tu te tais. Parle dès que quelqu'un est à portée et que tu as une raison, même futile ; tu peux aussi te taire. "relations" ne contient que les personnes sur qui ton opinion change à l'instant. N'utilise que des noms et des coordonnées présents dans ta fiche.""");
		return s.toString();
	}

	public static CompletableFuture<Decision> demander(final String fiche) {
		HameauConfig config = HameauConfig.get();
		AnthropicClient c = client;
		String consignes = systeme;
		return CompletableFuture.supplyAsync(() -> {
			Thread.currentThread().setContextClassLoader(Cerveau.class.getClassLoader());
			MessageCreateParams.Builder requete = MessageCreateParams.builder()
					.model(config.modele)
					.maxTokens(config.maxTokensReponse)
					.system(consignes)
					.addUserMessage(fiche);
			if (config.effort != null && !config.effort.isBlank()) {
				requete.outputConfig(com.anthropic.models.messages.OutputConfig.builder().effort(switch (config.effort.toLowerCase()) {
					case "high" -> com.anthropic.models.messages.OutputConfig.Effort.HIGH;
					case "medium" -> com.anthropic.models.messages.OutputConfig.Effort.MEDIUM;
					default -> com.anthropic.models.messages.OutputConfig.Effort.LOW;
				}).build());
			}
			Message reponse = c.messages().create(requete.build());
			String texte = reponse.content().stream()
					.flatMap(bloc -> bloc.text().stream())
					.map(TextBlock::text)
					.collect(Collectors.joining());
			return lire(texte, reponse.usage().inputTokens(), reponse.usage().outputTokens());
		}, FILS);
	}

	private static final String NAISSANCE = """

			Réponds UNIQUEMENT par un objet JSON, sans rien autour ni ``` :
			{"nom":"…","traits":["…","…","…"],"manie":"…","parler":"…","desir":"…","peur":"…","histoire":"…","lien":{"avec":"…","pour_toi":"…","pour_lui":"…","opinion":0,"opinion_de_lui":0}}

			- "nom" : un prénom seul, en un mot (lettres et tiret uniquement), dans le style des prénoms du village, différent de tous ceux déjà pris. Écarte les premiers prénoms qui te viennent : ce sont ceux qu'on a déjà vus partout.
			- "traits" : 3 ou 4 traits de caractère, accordés à son sexe, dont au moins un vrai défaut.
			- "manie" : une habitude bien à lui, écrite comme un groupe verbal à la 3e personne sans sujet, par exemple « range ses outils par ordre de taille ».
			- "parler" : sa façon de s'exprimer (rythme, vocabulaire, tics de langage), sous la même forme, par exemple « parle bas et finit ses phrases par une question ».
			- "desir" : ce qu'il veut vraiment, concret et à sa portée dans un village, à l'infinitif.
			- "peur" : ce qu'il redoute, en un groupe nominal.
			- "histoire" : deux phrases à la 2e personne (« Tu… ») : d'où il vient, ce qui l'a marqué, un secret ou une affaire en cours. Rien qui ne puisse exister dans Minecraft.
			- "lien" : un lien ancien avec UN des habitants listés, seulement si on te le demande ; sinon null. "avec" = son prénom exact ; "pour_toi" = ce que ton personnage sait de ce lien, à la 2e personne, en nommant l'autre ; "pour_lui" = ce que l'autre en sait, à la 2e personne, en nommant ton personnage par son nouveau prénom (null s'il l'ignore) ; "opinion" et "opinion_de_lui" = de -50 à 50.
			- "voix" : seulement si une liste de voix t'est proposée, le prénom de celle qui lui ressemble le plus (âge, tempérament), en préférant une voix que personne ne porte encore ; ajoute alors "voix":"…" à l'objet.
			Tout en français. Chaque champ tient en une phrase courte, sauf "histoire".""";

	private static final String FONDATION = """

			Réponds UNIQUEMENT par un objet JSON, sans rien autour ni ``` :
			{"nom":"nom du village","culture":"…"}

			"culture" : quatre ou cinq phrases en français, qui disent de quoi vit le village et ce dont il est fier, une coutume ou une croyance bien à lui, comment sonnent les prénoms d'ici (décris le style, sans donner de liste), une façon de parler commune (tournures, salutations, jurons), et une affaire qui divise ou inquiète les habitants en ce moment. Rien qui ne puisse exister dans Minecraft.""";

	/** Un appel simple, avec le modèle des réflexions : consignes, demande, texte en retour. */
	private static Message appeler(final AnthropicClient c, final HameauConfig config, final String consignes, final String demande) {
		Thread.currentThread().setContextClassLoader(Cerveau.class.getClassLoader());
		MessageCreateParams.Builder requete = MessageCreateParams.builder()
				.model(config.modele)
				.maxTokens(Math.max(config.maxTokensReponse, 1200))
				.system(consignes)
				.addUserMessage(demande);
		if (config.effort != null && !config.effort.isBlank()) {
			requete.outputConfig(com.anthropic.models.messages.OutputConfig.builder().effort(com.anthropic.models.messages.OutputConfig.Effort.LOW).build());
		}
		return c.messages().create(requete.build());
	}

	private static String texte(final Message reponse) {
		return reponse.content().stream().flatMap(bloc -> bloc.text().stream()).map(TextBlock::text).collect(Collectors.joining());
	}

	/** Invente (ou réinvente) la personnalité d'un villageois. */
	public static CompletableFuture<Persona> incarner(final String demande) {
		HameauConfig config = HameauConfig.get();
		AnthropicClient c = client;
		String consignes = Textes.naissance + Textes.decor() + NAISSANCE;
		return CompletableFuture.supplyAsync(() -> {
			Message reponse = appeler(c, config, consignes, demande);
			JsonObject json = extraire(texte(reponse));
			java.util.List<String> traits = new java.util.ArrayList<>();
			if (json.has("traits") && json.get("traits").isJsonArray()) {
				for (JsonElement trait : json.getAsJsonArray("traits")) {
					if (trait.isJsonPrimitive() && !trait.getAsString().isBlank() && traits.size() < 5) {
						traits.add(trait.getAsString().trim());
					}
				}
			}
			JsonObject lien = json.has("lien") && json.get("lien").isJsonObject() ? json.getAsJsonObject("lien") : new JsonObject();
			Boolean femme = json.has("femme") && json.get("femme").isJsonPrimitive() && json.getAsJsonPrimitive("femme").isBoolean() ? json.get("femme").getAsBoolean() : null;
			return new Persona(chaine(json, "nom"), femme, traits, chaine(json, "manie"), chaine(json, "parler"), chaine(json, "desir"), chaine(json, "peur"), chaine(json, "histoire"), chaine(json, "voix"),
					chaine(lien, "avec"), chaine(lien, "pour_toi"), chaine(lien, "pour_lui"), entier(lien, "opinion"), entier(lien, "opinion_de_lui"),
					reponse.usage().inputTokens(), reponse.usage().outputTokens());
		}, FILS);
	}

	/** Invente le nom et la culture d'un village. */
	public static CompletableFuture<Fondation> fonder(final String demande) {
		HameauConfig config = HameauConfig.get();
		AnthropicClient c = client;
		String consignes = Textes.village + Textes.decor() + FONDATION;
		return CompletableFuture.supplyAsync(() -> {
			Message reponse = appeler(c, config, consignes, demande);
			JsonObject json = extraire(texte(reponse));
			return new Fondation(chaine(json, "nom"), chaine(json, "culture"), reponse.usage().inputTokens(), reponse.usage().outputTokens());
		}, FILS);
	}

	private static int entier(final JsonObject json, final String cle) {
		return json.has(cle) && json.get(cle).isJsonPrimitive() && json.getAsJsonPrimitive(cle).isNumber() ? Math.clamp(json.get(cle).getAsInt(), -50, 50) : 0;
	}

	private static final String ARCHITECTE = """
			Tu dessines le plan d'une construction Minecraft qu'un villageois va bâtir bloc par bloc. Réponds UNIQUEMENT par un objet JSON, sans rien autour ni ``` :
			{"nom":"nom court du bâtiment","palette":{"P":"minecraft:oak_planks","L":"minecraft:oak_log"},"couches":[["LPPPL","P...P"],["..."]]}

			Format : "couches" va de bas en haut. Chaque couche est une liste de lignes, du nord au sud ; chaque ligne est une chaîne dont chaque caractère est un bloc, d'ouest en est. "." = vide. Toutes les lignes ont la même longueur et toutes les couches le même nombre de lignes. Chaque caractère autre que "." doit figurer dans "palette".
			Limites : %d blocs de côté au plus, %d couches au plus. Adapte la taille à ce qui est demandé : une cabane fait 5 à 7 blocs de côté, une maison 7 à 9, une villa, une grande bâtisse ou un monument 11 à 15. Si on demande grand, fais grand.
			Couche 0 : le sol du bâtiment, posé au niveau du terrain. Les murs commencent à la couche 1.
			Blocs permis : blocs pleins et simples (planches, bûches, pierre, pierre taillée, briques, grès, laine, verre, terre cuite, terre, terre labourée, sable, gravier), dalles, vitres, barrières, portillons, portes, lanternes, torches posées au sol, bottes de foin, bibliothèques, établis, fleurs, feuillages, eau (minecraft:water, seulement dans un creux fermé). Interdits : escaliers, lits, coffres, lave, redstone.
			"~" (sans entrée dans la palette) : creuser, c'est-à-dire vider cet emplacement. Pour un bassin, un puits ou une fosse, la couche 0 peut ainsi être creusée puis remplie.
			Porte : place son caractère uniquement à la couche 1, dans un mur extérieur, et laisse "." juste au-dessus à la couche 2 : la moitié haute se pose toute seule.
			S'il s'agit d'un bâtiment, il doit tenir debout et servir : murs fermés, intérieur vide et haut d'au moins 2 blocs, une porte, des fenêtres, un toit complet (plat, ou en gradins de planches et de dalles). Tout le reste (pont, enclos, potager, fontaine, statue, étal, monument, piège…) suit sa propre logique : dessine ce qui est demandé, pas une maison.
			Le plan doit refléter la demande, mais aussi le goût, le métier et le caractère du bâtisseur.""";

	/** Demande un plan de construction au modèle des plans ; en cas d'échec, une seconde tentative avec le modèle courant. */
	public static CompletableFuture<Plan> dessiner(final String demande) {
		HameauConfig config = HameauConfig.get();
		AnthropicClient c = client;
		String consignes = String.format(ARCHITECTE, config.batir.largeurMax, config.batir.hauteurMax);
		return CompletableFuture.supplyAsync(() -> {
			Thread.currentThread().setContextClassLoader(Cerveau.class.getClassLoader());
			long perdusEntree = 0;
			long perdusSortie = 0;
			try {
				MessageCreateParams.Builder requete = MessageCreateParams.builder()
						.model(config.batir.modele)
						.maxTokens(9000L)
						.system(consignes)
						.addUserMessage(demande);
				if (!config.batir.modele.contains("haiku")) {
					requete.outputConfig(com.anthropic.models.messages.OutputConfig.builder().effort(com.anthropic.models.messages.OutputConfig.Effort.LOW).build());
				}
				Message reponse = c.messages().create(requete.build());
				Plan plan = lirePlan(reponse, false, 0, 0);
				if (plan != null) {
					return plan;
				}
				perdusEntree = reponse.usage().inputTokens();
				perdusSortie = reponse.usage().outputTokens();
				Hameau.LOGGER.warn("Hameau : plan inutilisable de {}, nouvel essai avec {}", config.batir.modele, config.modele);
			} catch (RuntimeException e) {
				Hameau.LOGGER.warn("Hameau : {} n'a pas pu dessiner le plan ({}), nouvel essai avec {}", config.batir.modele, e.toString(), config.modele);
			}
			Message reponse = c.messages().create(MessageCreateParams.builder()
					.model(config.modele)
					.maxTokens(4000L)
					.system(consignes)
					.addUserMessage(demande)
					.build());
			return lirePlan(reponse, true, perdusEntree, perdusSortie);
		}, FILS);
	}

	private static Plan lirePlan(final Message reponse, final boolean secours, final long perdusEntree, final long perdusSortie) {
		String texte = reponse.content().stream().flatMap(bloc -> bloc.text().stream()).map(TextBlock::text).collect(Collectors.joining());
		try {
			JsonObject json = extraire(texte);
			Map<String, String> palette = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("palette").entrySet()) {
				palette.put(e.getKey(), e.getValue().getAsString());
			}
			java.util.List<java.util.List<String>> couches = new java.util.ArrayList<>();
			for (JsonElement couche : json.getAsJsonArray("couches")) {
				java.util.List<String> lignes = new java.util.ArrayList<>();
				for (JsonElement ligne : couche.getAsJsonArray()) {
					lignes.add(ligne.getAsString());
				}
				couches.add(lignes);
			}
			String nom = chaine(json, "nom");
			return couches.isEmpty() ? null : new Plan(nom == null ? "construction" : nom, palette, couches, reponse.usage().inputTokens(), reponse.usage().outputTokens(), secours, perdusEntree, perdusSortie);
		} catch (RuntimeException e) {
			Hameau.LOGGER.warn("Hameau : plan illisible ({}) : {}", e.toString(), texte.length() > 1500 ? texte.substring(0, 700) + " […] " + texte.substring(texte.length() - 700) : texte);
			return null;
		}
	}

	/** Lit le JSON de la réponse, en tolérant du texte parasite autour et une réponse tronquée. */
	static Decision lire(final String texte, final long entree, final long sortie) {
		String pensee = null;
		String parole = null;
		String a = null;
		String action = "rien";
		String cible = null;
		String objet = null;
		String humeur = null;
		String souvenir = null;
		String geste = null;
		String emotion = null;
		String projet = null;
		boolean prive = false;
		java.util.List<Etape> suite = new java.util.ArrayList<>();
		Map<String, Integer> relations = new LinkedHashMap<>();
		try {
			JsonObject json = extraire(texte);
			pensee = chaine(json, "pensee");
			parole = chaine(json, "parole");
			a = chaine(json, "a");
			String lue = chaine(json, "action");
			action = lue == null ? "rien" : lue.toLowerCase();
			cible = chaine(json, "cible");
			objet = chaine(json, "objet");
			humeur = chaine(json, "humeur");
			souvenir = chaine(json, "souvenir");
			geste = chaine(json, "geste");
			emotion = chaine(json, "emotion");
			projet = chaine(json, "projet");
			prive = "true".equalsIgnoreCase(chaine(json, "prive"));
			if (json.has("suite") && json.get("suite").isJsonArray()) {
				for (JsonElement e : json.getAsJsonArray("suite")) {
					if (e.isJsonObject() && suite.size() < 6 && chaine(e.getAsJsonObject(), "action") != null) {
						JsonObject etape = e.getAsJsonObject();
						suite.add(new Etape(chaine(etape, "action").toLowerCase(), chaine(etape, "cible"), chaine(etape, "objet")));
					}
				}
			}
			if (json.has("relations") && json.get("relations").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("relations").entrySet()) {
					if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
						relations.put(e.getKey(), Math.clamp(e.getValue().getAsInt(), -15, 15));
					}
				}
			}
		} catch (RuntimeException e) {
			Hameau.LOGGER.warn("Hameau : réponse illisible ({}) : {}", e.getMessage(), texte);
		}
		return new Decision(pensee, geste, parole, a, prive, action, cible, objet, suite, emotion, humeur, projet, relations, souvenir, entree, sortie);
	}

	/** Extrait l'objet JSON ; si la réponse a été coupée, on la referme au dernier champ complet. */
	private static JsonObject extraire(final String texte) {
		int debut = texte.indexOf('{');
		if (debut < 0) {
			throw new IllegalStateException("pas de JSON");
		}
		// Premier objet complet : on suit les accolades, en ignorant celles qui se trouvent dans des chaînes.
		int profondeur = 0;
		boolean dansChaine = false;
		for (int i = debut; i < texte.length(); i++) {
			char c = texte.charAt(i);
			if (dansChaine) {
				if (c == '\\') {
					i++;
				} else if (c == '"') {
					dansChaine = false;
				}
			} else if (c == '"') {
				dansChaine = true;
			} else if (c == '{') {
				profondeur++;
			} else if (c == '}' && --profondeur == 0) {
				return JsonParser.parseString(texte.substring(debut, i + 1)).getAsJsonObject();
			}
		}
		String candidat = texte.substring(debut).replace("```", "").trim();
		RuntimeException derniere = null;
		for (int essai = 0; essai < 8 && candidat.length() > 1; essai++) {
			for (String fin : new String[] {"", "}", "}}"}) {
				try {
					return JsonParser.parseString(candidat + fin).getAsJsonObject();
				} catch (RuntimeException e) {
					derniere = e;
				}
			}
			int virgule = candidat.lastIndexOf(',');
			if (virgule < 0) {
				break;
			}
			candidat = candidat.substring(0, virgule);
		}
		throw derniere != null ? derniere : new IllegalStateException("JSON vide");
	}

	private static String chaine(final JsonObject json, final String cle) {
		if (!json.has(cle) || !json.get(cle).isJsonPrimitive()) {
			return null;
		}
		String valeur = json.get(cle).getAsString().trim();
		return valeur.isEmpty() || valeur.equalsIgnoreCase("null") ? null : valeur;
	}
}
