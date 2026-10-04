package com.ncombarieu.hameau;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/** Réglages lus depuis config/hameau.json (créé au premier lancement). Durées en secondes, distances en blocs. */
public final class HameauConfig {
	static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Path FICHIER = FabricLoader.getInstance().getConfigDir().resolve("hameau.json");
	private static HameauConfig instance = new HameauConfig();

	public String modele = "claude-haiku-4-5";
	/** Effort de réflexion : "low", "medium" ou "high". À laisser vide pour Haiku, qui ne l'accepte pas ; "low" conseillé pour Sonnet et Opus. */
	public String effort = "";
	/** Tarifs du modèle, en dollars par million de tokens : servent à suivre la dépense. */
	public double prixEntreeParMillion = 1.0;
	public double prixSortieParMillion = 5.0;
	/** Au-delà, les villageois cessent de réfléchir jusqu'au lendemain (ou définitivement pour le total). */
	public double plafondJournalierUsd = 2.0;
	public double plafondTotalUsd = 18.0;

	/** Temps moyen entre deux réflexions de routine d'un villageois. */
	public int intervallePensee = 180;
	/** Temps moyen entre deux petits imprévus de la vie du village (une envie, une trouvaille, une rumeur…). 0 = aucun. */
	public int intervalleIncident = 300;
	/** Un villageois remarque un joueur qui s'approche à cette distance et peut l'aborder de lui-même. */
	public int distanceAbord = 6;
	/** Délai minimal entre deux réflexions du même villageois, même s'il se passe quelque chose. */
	public int delaiMinEntrePensees = 12;
	/** Seuls les villageois à cette distance d'un joueur réfléchissent. */
	public int rayonActif = 40;
	public int maxVillageoisActifs = 10;
	public int appelsSimultanes = 2;
	public int maxTokensReponse = 450;

	/** Le village continue-t-il à vivre sans joueur connecté ? Aussi activable par -Dhameau.horsLigne=true. */
	public boolean vieHorsLigne = false;

	/** Les villageois ont-ils un corps humain (bras, outils en main) à la place du modèle d'origine ? */
	public boolean corps = true;
	/** Les enfants reçoivent-ils aussi un corps humain (réduit) ? Sinon ils gardent l'apparence de bébé villageois. */
	public boolean corpsEnfants = false;
	/** Pseudos de comptes Minecraft dont emprunter le skin. Vide : les neuf skins fournis avec le jeu. */
	public java.util.List<String> peauxJoueurs = new java.util.ArrayList<>();

	/** Le serveur redistribue lui-même le chat des joueurs, en messages système : chacun le voit quels que soient ses réglages de chat sécurisé. */
	public boolean chatFiable = true;

	public Voix voix = new Voix();

	/** Voix parlée des villageois : demande le mod Simple Voice Chat (serveur et joueurs) et une clé ElevenLabs dans config/hameau-voix-cle.txt. */
	public static final class Voix {
		public boolean actif = true;
		/** Modèle ElevenLabs : eleven_flash_v2_5 (rapide, économe), eleven_multilingual_v2 (plus naturel, deux fois plus cher). */
		public String modele = "eleven_flash_v2_5";
		/** On entend un villageois jusqu'à cette distance, en blocs. */
		public float distance = 24;
		/** Au-delà, les villageois se taisent jusqu'au lendemain (le texte reste dans le chat). */
		public int plafondCaracteresParJour = 5000;
	}

	public Batir batir = new Batir();

	public static final class Batir {
		/** Modèle qui dessine les plans de construction (un appel par bâtiment), et ses tarifs par million de tokens. */
		public String modele = "claude-sonnet-5-5";
		public double prixEntreeParMillion = 2.0;
		public double prixSortieParMillion = 10.0;
		/** Faux : le bâtisseur doit avoir chaque bloc en poche (il débite ses bûches en planches) et s'arrête quand il lui en manque. */
		public boolean materiauxGratuits = true;
		/** Rythme de pose : un bloc tous les N ticks (20 ticks = 1 seconde). */
		public int ticksParBloc = 5;
		public int largeurMax = 15;
		public int hauteurMax = 10;
	}

	public Autonomie autonomie = new Autonomie();

	public static final class Autonomie {
		public boolean frapper = true;
		public boolean casser = true;
		public boolean poser = true;
		public boolean prendreDansCoffres = true;
		public boolean batir = true;
		public float degatsParCoup = 2.0F;
		public int coupsMax = 3;
	}

	public static HameauConfig get() {
		return instance;
	}

	public boolean horsLigne() {
		return vieHorsLigne || Boolean.getBoolean("hameau.horsLigne");
	}

	public static void charger() {
		if (Files.exists(FICHIER)) {
			try (Reader reader = Files.newBufferedReader(FICHIER, StandardCharsets.UTF_8)) {
				HameauConfig lue = GSON.fromJson(reader, HameauConfig.class);
				if (lue != null) {
					instance = lue;
				}
			} catch (IOException | RuntimeException e) {
				Hameau.LOGGER.error("Impossible de lire {}, réglages par défaut utilisés", FICHIER, e);
			}
		}
		sauvegarder();
	}

	/** Règle d'un coup le modèle des réflexions, avec l'effort, la marge de réponse et les tarifs qui lui conviennent. */
	public boolean choisirModele(final String nom) {
		switch (nom.toLowerCase()) {
			case "haiku" -> regler("claude-haiku-4-5", "", 450, 1.0, 5.0);
			case "sonnet" -> regler("claude-sonnet-5-5", "low", 2500, 2.0, 10.0);
			case "opus" -> regler("claude-opus-5-5", "low", 2500, 4.0, 20.0);
			default -> {
				return false;
			}
		}
		return true;
	}

	private void regler(final String id, final String niveau, final int marge, final double entree, final double sortie) {
		modele = id;
		effort = niveau;
		maxTokensReponse = marge;
		prixEntreeParMillion = entree;
		prixSortieParMillion = sortie;
	}

	/** Même choix pour le modèle qui dessine les plans de construction. */
	public boolean choisirModelePlans(final String nom) {
		switch (nom.toLowerCase()) {
			case "haiku" -> reglerPlans("claude-haiku-4-5", 1.0, 5.0);
			case "sonnet" -> reglerPlans("claude-sonnet-5-5", 2.0, 10.0);
			case "opus" -> reglerPlans("claude-opus-5-5", 4.0, 20.0);
			default -> {
				return false;
			}
		}
		return true;
	}

	private void reglerPlans(final String id, final double entree, final double sortie) {
		batir.modele = id;
		batir.prixEntreeParMillion = entree;
		batir.prixSortieParMillion = sortie;
	}

	public static void sauvegarder() {
		try {
			Files.createDirectories(FICHIER.getParent());
			try (Writer writer = Files.newBufferedWriter(FICHIER, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			Hameau.LOGGER.error("Impossible d'écrire {}", FICHIER, e);
		}
	}
}
