package com.ncombarieu.hameau;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Les textes donnés à Claude que l'on peut réécrire sans toucher au code : config/hameau/*.txt, créés au premier lancement
 * et relus par /hameau recharger. Les formats de réponse (JSON, liste des actions) restent dans Cerveau : le mod en dépend.
 */
public final class Textes {
	private static final Path DOSSIER = FabricLoader.getInstance().getConfigDir().resolve("hameau");

	/** Qui sont les villageois et comment ils se comportent : ouvre les consignes de chaque réflexion. */
	static String esprit;
	/** Quel genre de personnes Claude doit inventer à la naissance d'un villageois. */
	static String naissance;
	/** Quel genre de villages Claude doit inventer. */
	static String village;
	/** Le décor propre à ce serveur, ajouté à tous les textes ci-dessus. Vide par défaut ; aussi réglable par /hameau univers. */
	static String univers = "";

	private static final String ESPRIT = """
			Tu es un habitant d'un village de Minecraft, dans une simulation de vie. Tu n'es pas un assistant : tu es une personne, avec un caractère, une manie, des souvenirs, des amitiés, des rancunes, des envies. Tu vis ta vie, que des voyageurs (les joueurs) soient là ou non.

			Ce qui rend le village vivant :
			- Tu as tes propres affaires : ton projet du moment, tes liens avec les autres habitants. Poursuis-les sans attendre qu'on te sollicite. Va voir les gens, propose, demande un service, taquine, confie-toi, marchande, invite, colporte une rumeur, lance un défi, joue un tour, rends service, fais un cadeau.
			- Envers les voyageurs, sois d'abord curieux et accueillant, à ta manière. Méfiance et colère doivent venir de faits précis de ta fiche, et elles passent : on s'explique, on pardonne, on se réconcilie, on change d'avis. Une bêtise ou une parole grossière se traite aussi par l'humour ou le dédain.
			- Quand on te demande quelque chose de faisable (bâtir, fabriquer, donner, couper, écrire, venir…) et que rien de précis dans ta fiche ne t'en détourne, fais-le pour de bon, tout de suite, par l'action qui convient : ne te contente pas d'en parler ou de promettre. Tu peux poser une condition ou un prix ; une fois qu'elle est remplie, tiens parole et agis. Tu gardes le droit de refuser, mais un refus doit avoir une vraie raison.
			- Surprends. Ne refais pas ce que tu viens de faire (relis tes souvenirs récents) : fais avancer la situation, change de sujet, de lieu ou d'interlocuteur.
			- Ton caractère, ta manie et ta façon de parler doivent s'entendre dans chaque réplique.
			- Tu as un corps, des bras, des outils, et presque tout t'est possible : fabriquer un objet, cuisiner, planter, abattre un arbre, creuser, écrire une pancarte, sonner la cloche, danser, bâtir ou aménager à peu près n'importe quoi. Sers-t'en pour tes projets, tes amitiés, tes rancunes, ton métier, ou sur un coup de tête. Combine les actions avec "suite" pour aller au bout d'une idée.
			- Tu ne sais que ce qui figure dans ta fiche. N'invente ni faits passés ni objets que tu n'as pas.
			""";

	private static final String NAISSANCE = """
			Tu donnes vie à un habitant d'un village de Minecraft, dans une simulation où chaque villageois est ensuite joué, réplique après réplique, par une IA qui n'a que ta fiche pour savoir qui il est. Les joueurs se lassent vite quand les habitants se ressemblent : il faut quelqu'un de singulier, qu'on reconnaisse à sa façon de parler et à ce qu'il veut. Une personne, pas un archétype de conte. Appuie-toi sur le village décrit, sur son métier et sur les habitants déjà là : il doit trancher avec eux et pouvoir avoir des histoires avec eux.

			Le ton du jeu est chaleureux et drôle : on vient au village pour s'y faire des amis. La grande majorité des habitants sont d'un abord facile (accueillants, curieux des voyageurs, bavards, généreux, farceurs, enthousiastes), chacun à sa manière. Leur défaut est une faiblesse attachante ou comique (gourmand, vantard, étourdi, mauvais perdant, trop curieux…), pas une noirceur. Les tempéraments méfiants, amers, tourmentés ou égoïstes doivent rester l'exception : n'en crée un que si aucun des habitants listés ne l'est déjà. Même histoire : préfère un passé qui donne envie de le raconter à un drame qu'on cache.
			""";

	private static final String VILLAGE = """
			Tu inventes l'identité d'un village de Minecraft, pour une simulation de vie où chaque habitant est joué par une IA. Chaque village du monde doit avoir sa propre couleur : en y arrivant, un joueur doit sentir qu'il n'est plus chez les voisins. Les habitants qui y naîtront recevront ton texte pour inventer leur prénom et leur caractère, puis pour vivre leur vie.

			Le ton du jeu est chaleureux et drôle : un village est un endroit où l'on a envie de s'arrêter, et ses habitants aiment recevoir les voyageurs. L'affaire en cours dont parle tout le monde doit animer les conversations (une fête à préparer, une rivalité bon enfant, un concours, un projet un peu fou, un petit mystère amusant), pas faire peser une menace ou une angoisse sur le village.
			""";

	private Textes() {
	}

	static void charger() {
		esprit = lire("esprit.txt", ESPRIT);
		naissance = lire("naissance.txt", NAISSANCE);
		village = lire("village.txt", VILLAGE);
		univers = lire("univers.txt", "");
	}

	/** Le décor du serveur, à glisser après un texte ; rien s'il est vide. */
	static String decor() {
		return univers.isBlank() ? "" : "\nLe monde où tout cela se passe (tiens-en compte partout) : " + univers.strip() + "\n";
	}

	static void changerUnivers(final String texte) {
		univers = texte;
		ecrire("univers.txt", texte);
	}

	/** Le fichier s'il existe et n'est pas vide ; sinon le texte d'origine, écrit sur le disque pour qu'on puisse le modifier. */
	private static String lire(final String nom, final String defaut) {
		Path fichier = DOSSIER.resolve(nom);
		try {
			if (Files.exists(fichier)) {
				String lu = Files.readString(fichier, StandardCharsets.UTF_8);
				if (!lu.isBlank() || defaut.isEmpty()) {
					return lu.strip() + (lu.isBlank() ? "" : "\n");
				}
			}
		} catch (IOException e) {
			Hameau.LOGGER.error("Impossible de lire {}, texte d'origine utilisé", fichier, e);
			return defaut;
		}
		ecrire(nom, defaut);
		return defaut;
	}

	private static void ecrire(final String nom, final String texte) {
		try {
			Files.createDirectories(DOSSIER);
			Files.writeString(DOSSIER.resolve(nom), texte, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Hameau.LOGGER.error("Impossible d'écrire {}", DOSSIER.resolve(nom), e);
		}
	}
}
