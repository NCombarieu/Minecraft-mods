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
	private static final ExecutorService FILS = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("hameau-", 0).factory());

	private static AnthropicClient client;
	private static String systeme;

	private Cerveau() {
	}

	public record Etape(String action, String cible, String objet) {
	}

	public record Decision(String pensee, String geste, String parole, String a, boolean prive, String action, String cible, String objet,
			java.util.List<Etape> suite, String emotion, String humeur, String projet, Map<String, Integer> relations, String souvenir, String role, String annonce, String sert,
			long tokensEntree, long tokensSortie) {
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

	/** Le modèle des réflexions est-il joignable (clé présente) ? */
	public static boolean pret() {
		HameauConfig config = HameauConfig.get();
		return config.url == null || config.url.isBlank() ? client != null : Cles.local(config.url) || Cles.lire(config.profil) != null;
	}

	/** Relit les textes et la clé Anthropic (les clés des autres services sont lues à chaque appel, dans config/hameau-cles/). */
	public static void demarrer() {
		Textes.charger();
		systeme = consignes();
		String cle = Cles.lire("anthropic");
		client = cle == null ? null : AnthropicOkHttpClient.builder().apiKey(cle).maxRetries(1).timeout(Duration.ofSeconds(40)).build();
		if (!pret()) {
			Hameau.LOGGER.warn("Hameau : aucune clé API pour le modèle « {} ». Les villageois resteront muets.", HameauConfig.get().profil);
		}
	}

	/** Ce qu'un modèle a répondu, quel que soit le service. */
	record Reponse(String texte, long entree, long sortie) {
	}

	private static final java.util.Set<String> NOUVELLE_LIMITE = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	/**
	 * Un appel à un modèle : par le SDK d'Anthropic si « url » est vide, sinon par l'API « chat/completions » que partagent
	 * Mistral, Qwen, OpenAI, OpenRouter, Groq, Ollama…
	 */
	private static Reponse completer(final AnthropicClient c, final String url, final String profil, final String modele, final String effort, final long maxTokens,
			final String consignes, final String demande) {
		return completer(c, url, profil, modele, effort, maxTokens, consignes, demande, 60);
	}

	private static Reponse completer(final AnthropicClient c, final String url, final String profil, final String modele, final String effort, final long maxTokens,
			final String consignes, final String demande, final int delaiSecondes) {
		if (url == null || url.isBlank()) {
			if (c == null) {
				throw new IllegalStateException("pas de clé Anthropic");
			}
			Thread.currentThread().setContextClassLoader(Cerveau.class.getClassLoader());
			MessageCreateParams.Builder requete = MessageCreateParams.builder().model(modele).maxTokens(maxTokens).system(consignes).addUserMessage(demande);
			if (effort != null && !effort.isBlank()) {
				requete.outputConfig(com.anthropic.models.messages.OutputConfig.builder().effort(switch (effort.toLowerCase()) {
					case "high" -> com.anthropic.models.messages.OutputConfig.Effort.HIGH;
					case "medium" -> com.anthropic.models.messages.OutputConfig.Effort.MEDIUM;
					default -> com.anthropic.models.messages.OutputConfig.Effort.LOW;
				}).build());
			}
			Message reponse = c.messages().create(requete.build());
			String texte = reponse.content().stream().flatMap(bloc -> bloc.text().stream()).map(TextBlock::text).collect(Collectors.joining());
			return new Reponse(texte, reponse.usage().inputTokens(), reponse.usage().outputTokens());
		}
		String cle = Cles.lire(profil);
		if (cle == null && !Cles.local(url)) {
			throw new IllegalStateException("pas de clé pour « " + profil + " » (config/hameau-cles/" + profil + ".txt)");
		}
		JsonObject corps = new JsonObject();
		corps.addProperty("model", modele);
		// Les modèles récents d'OpenAI veulent « max_completion_tokens » ; les autres services, « max_tokens ».
		boolean nouveauNom = NOUVELLE_LIMITE.contains(url + " " + modele);
		corps.addProperty(nouveauNom ? "max_completion_tokens" : "max_tokens", maxTokens);
		if (effort != null && !effort.isBlank()) {
			corps.addProperty("reasoning_effort", effort.toLowerCase());
		}
		com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
		for (String[] message : new String[][] {{"system", consignes}, {"user", demande}}) {
			JsonObject m = new JsonObject();
			m.addProperty("role", message[0]);
			m.addProperty("content", message[1]);
			messages.add(m);
		}
		corps.add("messages", messages);
		java.net.http.HttpRequest.Builder requete = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url.replaceAll("/+$", "") + "/chat/completions"))
				.header("Content-Type", "application/json").timeout(Duration.ofSeconds(delaiSecondes))
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(corps.toString(), StandardCharsets.UTF_8));
		if (cle != null) {
			requete.header("Authorization", "Bearer " + cle);
		}
		try {
			java.net.http.HttpResponse<String> reponse = HTTP.send(requete.build(), java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (reponse.statusCode() == 429 || reponse.statusCode() >= 500) {
				// Service débordé ou hoquet passager : un second essai, comme le fait le SDK d'Anthropic.
				Thread.sleep(1500);
				reponse = HTTP.send(requete.build(), java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			}
			if (reponse.statusCode() == 400 && !nouveauNom && reponse.body().contains("max_completion_tokens")) {
				NOUVELLE_LIMITE.add(url + " " + modele);
				return completer(c, url, profil, modele, effort, maxTokens, consignes, demande, delaiSecondes);
			}
			if (reponse.statusCode() != 200) {
				throw new IllegalStateException(profil + " a répondu " + reponse.statusCode() + " : " + (reponse.body().length() > 300 ? reponse.body().substring(0, 300) : reponse.body()));
			}
			JsonObject json = JsonParser.parseString(reponse.body()).getAsJsonObject();
			JsonElement contenu = json.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content");
			// Certains modèles réfléchissent à voix haute avant de répondre : on ne garde que la réponse.
			String texte = (contenu == null || contenu.isJsonNull() ? "" : contenu.getAsString()).replaceAll("(?s)<think>.*?</think>", "");
			JsonObject usage = json.has("usage") && json.get("usage").isJsonObject() ? json.getAsJsonObject("usage") : new JsonObject();
			return new Reponse(texte, usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsLong() : 0, usage.has("completion_tokens") ? usage.get("completion_tokens").getAsLong() : 0);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
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

				Trois champs facultatifs, à n'ajouter que le jour où cela arrive vraiment (la plupart du temps, aucun) :
				"role":"…" quand ta place au village change : le métier que tu t'inventes, la fonction ou le titre que tu prends ou qu'on te donne, en quelques mots. Les autres le verront.
				"annonce":"…" pour un fait public et durable que tout le village doit désormais savoir (ce qui se décide, s'établit ou change pour tous), en une phrase. Il entre dans la mémoire commune du village.
				"sert":"Nom" quand tu acceptes d'entrer au service de quelqu'un qui t'a convaincu, payé ou soumis ; "sert":"personne" quand tu reprends ta liberté.

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
				- "danser" : danser, sauter de joie.
				- "manger" : manger quelque chose que tu as en poche ; cela te soigne.
				- "ramasser" : ramasser les objets tombés à terre près de toi.
				- "recolter" : moissonner les cultures mûres autour de toi et ressemer.
				- "deposer" : ranger dans un coffre ce que tu portes. cible = "x y z", objet = quoi (rien = tout).
				""");
		if (autonomie.frapper) {
			s.append("- \"frapper\" : donner des coups à quelqu'un. Normal pour riposter quand on te frappe, pour relever un défi ou une bagarre qu'on te propose, ou quand la colère déborde ; selon ton caractère tu cognes volontiers ou tu t'y refuses. Les témoins s'en souviendront. cible = nom. Sert aussi à chasser une bête ou à tuer un monstre (cible = son espèce) : tu ramasses ce qu'elle laisse.\n");
		}
		if (autonomie.casser) {
			s.append("- \"couper\" : abattre un arbre à la hache ; le bois va dans ton inventaire. cible = \"x y z\" du pied du tronc.\n");
			s.append("- \"casser\" : casser ou creuser un bloc avec l'outil qui convient (pioche, pelle, hache, main) ; tu ramasses ce qu'il donne. cible = \"x y z\".\n");
			s.append("- \"miner\" : aller chercher un minerai ou de la pierre sous terre, à la pioche : tu creuses ta galerie jusqu'au filon le plus proche et tu remontes avec. cible = ce que tu cherches (fer, charbon, pierre, diamant…), objet = combien de blocs (6 par défaut).\n");
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
			Reponse reponse = completer(c, config.url, config.profil, config.modele, config.effort, config.maxTokensReponse, consignes, fiche);
			return lire(reponse.texte(), reponse.entree(), reponse.sortie());
		}, FILS);
	}

	private static final String NAISSANCE = """

			Réponds UNIQUEMENT par un objet JSON, sans rien autour ni ``` :
			{"nom":"…","traits":["…","…","…"],"manie":"…","parler":"…","desir":"…","peur":"…","histoire":"…","lien":{"avec":"…","pour_toi":"…","pour_lui":"…","opinion":0,"opinion_de_lui":0}}

			- "nom" : un prénom seul, en un mot (lettres et tiret uniquement), dans le style des prénoms du village, différent de tous ceux déjà pris. Écarte les premiers prénoms qui te viennent : ce sont ceux qu'on a déjà vus partout.
			- "traits" : 3 ou 4 traits de caractère, accordés à son sexe, dont un défaut.
			- "manie" : une habitude bien à lui, écrite comme un groupe verbal à la 3e personne sans sujet, par exemple « range ses outils par ordre de taille ».
			- "parler" : sa façon de s'exprimer (rythme, vocabulaire, tics de langage), sous la même forme, par exemple « parle bas et finit ses phrases par une question ».
			- "desir" : ce qu'il veut vraiment, concret et à sa portée dans un village, à l'infinitif.
			- "peur" : ce qu'il redoute, en un groupe nominal.
			- "histoire" : deux phrases à la 2e personne (« Tu… ») : d'où il vient, ce qui l'a marqué, ce qui l'occupe en ce moment. Rien qui ne puisse exister dans Minecraft.
			- "lien" : un lien ancien avec UN des habitants listés, seulement si on te le demande ; sinon null. "avec" = son prénom exact ; "pour_toi" = ce que ton personnage sait de ce lien, à la 2e personne, en nommant l'autre ; "pour_lui" = ce que l'autre en sait, à la 2e personne, en nommant ton personnage par son nouveau prénom (null s'il l'ignore) ; "opinion" et "opinion_de_lui" = de -50 à 50.
			- "voix" : seulement si une liste de voix t'est proposée, le prénom de celle qui lui ressemble le plus (âge, tempérament), en préférant une voix que personne ne porte encore ; ajoute alors "voix":"…" à l'objet.
			Tout en français. Chaque champ tient en une phrase courte, sauf "histoire".""";

	private static final String FONDATION = """

			Réponds UNIQUEMENT par un objet JSON, sans rien autour ni ``` :
			{"nom":"nom du village","culture":"…"}

			"culture" : quatre ou cinq phrases en français, qui disent de quoi vit le village et ce dont il est fier, une coutume ou une croyance bien à lui, comment sonnent les prénoms d'ici (décris le style, sans donner de liste), une façon de parler commune (tournures, salutations, jurons), et l'affaire dont tout le monde parle en ce moment. Rien qui ne puisse exister dans Minecraft.""";

	/** Un appel simple, avec le modèle des réflexions. */
	private static Reponse appeler(final AnthropicClient c, final HameauConfig config, final String consignes, final String demande) {
		return completer(c, config.url, config.profil, config.modele, config.effort == null || config.effort.isBlank() ? "" : "low", Math.max(config.maxTokensReponse, 1200), consignes, demande);
	}

	/** Invente (ou réinvente) la personnalité d'un villageois. */
	public static CompletableFuture<Persona> incarner(final String demande) {
		HameauConfig config = HameauConfig.get();
		AnthropicClient c = client;
		String consignes = Textes.naissance + Textes.decor() + NAISSANCE;
		return CompletableFuture.supplyAsync(() -> {
			Reponse reponse = appeler(c, config, consignes, demande);
			JsonObject json = extraire(reponse.texte());
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
					reponse.entree(), reponse.sortie());
		}, FILS);
	}

	/** Invente le nom et la culture d'un village. */
	public static CompletableFuture<Fondation> fonder(final String demande) {
		HameauConfig config = HameauConfig.get();
		AnthropicClient c = client;
		String consignes = Textes.village + Textes.decor() + FONDATION;
		return CompletableFuture.supplyAsync(() -> {
			Reponse reponse = appeler(c, config, consignes, demande);
			JsonObject json = extraire(reponse.texte());
			return new Fondation(chaine(json, "nom"), chaine(json, "culture"), reponse.entree(), reponse.sortie());
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
			long perdusEntree = 0;
			long perdusSortie = 0;
			try {
				boolean anthropic = config.batir.url == null || config.batir.url.isBlank();
				// Hors Anthropic, on reprend l'effort réglé dans le profil : sans lui, un modèle qui raisonne peut tout dépenser à réfléchir et ne rien répondre.
				HameauConfig.Modele profilPlans = config.modeles.get(config.batir.profil);
				String effort = anthropic ? (config.batir.modele.contains("haiku") ? "" : "low") : profilPlans == null || profilPlans.effort == null ? "" : profilPlans.effort;
				Reponse reponse = completer(c, config.batir.url, config.batir.profil, config.batir.modele, effort, 9000L, consignes, demande, 240);
				Plan plan = lirePlan(reponse, false, 0, 0);
				if (plan != null) {
					return plan;
				}
				perdusEntree = reponse.entree();
				perdusSortie = reponse.sortie();
				Hameau.LOGGER.warn("Hameau : plan inutilisable de {}, nouvel essai avec {}", config.batir.modele, config.modele);
			} catch (RuntimeException e) {
				Hameau.LOGGER.warn("Hameau : {} n'a pas pu dessiner le plan ({}), nouvel essai avec {}", config.batir.modele, e.toString(), config.modele);
			}
			boolean secoursAnthropic = config.url == null || config.url.isBlank();
			return lirePlan(completer(c, config.url, config.profil, config.modele, secoursAnthropic ? "" : config.effort, secoursAnthropic ? 4000L : 9000L, consignes, demande, 240), true, perdusEntree, perdusSortie);
		}, FILS);
	}

	private static Plan lirePlan(final Reponse reponse, final boolean secours, final long perdusEntree, final long perdusSortie) {
		String texte = reponse.texte();
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
			return couches.isEmpty() ? null : new Plan(nom == null ? "construction" : nom, palette, couches, reponse.entree(), reponse.sortie(), secours, perdusEntree, perdusSortie);
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
		String role = null;
		String annonce = null;
		String sert = null;
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
			role = chaine(json, "role");
			annonce = chaine(json, "annonce");
			sert = chaine(json, "sert");
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
		return new Decision(pensee, geste, parole, a, prive, action, cible, objet, suite, emotion, humeur, projet, relations, souvenir, role, annonce, sert, entree, sortie);
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
