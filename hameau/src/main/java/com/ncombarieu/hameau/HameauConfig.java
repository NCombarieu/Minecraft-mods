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
	/** D'où vient ce modèle : le nom du profil choisi par /hameau modele, et l'adresse de son API (vide = Anthropic). Réglés par la commande. */
	public String profil = "haiku";
	public String url = "";

	/**
	 * Les modèles entre lesquels /hameau modele bascule. « url » vide : Anthropic. Sinon l'adresse d'une API compatible OpenAI
	 * (Mistral, Qwen, OpenAI, OpenRouter, Groq, Ollama…). La clé de chaque profil se met dans config/hameau-cles/&lt;nom&gt;.txt.
	 * Les prix (dollars par million de tokens) servent au suivi de la dépense : à renseigner pour que les plafonds aient un sens.
	 */
	public java.util.Map<String, Modele> modeles = new java.util.LinkedHashMap<>();

	public static final class Modele {
		public String url = "";
		public String id = "";
		public String effort = "";
		public int maxTokens = 1200;
		public double prixEntree;
		public double prixSortie;

		Modele() {
		}

		Modele(final String url, final String id, final String effort, final int maxTokens, final double prixEntree, final double prixSortie) {
			this.url = url;
			this.id = id;
			this.effort = effort;
			this.maxTokens = maxTokens;
			this.prixEntree = prixEntree;
			this.prixSortie = prixSortie;
		}
	}

	/** Ajoute les profils fournis d'origine s'ils manquent : on peut les modifier ou en ajouter d'autres dans le fichier. */
	private void completer() {
		if (modeles == null) {
			modeles = new java.util.LinkedHashMap<>();
		}
		modeles.putIfAbsent("haiku", new Modele("", "claude-haiku-4-5", "", 450, 1.0, 5.0));
		modeles.putIfAbsent("sonnet", new Modele("", "claude-sonnet-5-5", "low", 2500, 2.0, 10.0));
		modeles.putIfAbsent("opus", new Modele("", "claude-opus-5-5", "low", 2500, 4.0, 20.0));
		modeles.putIfAbsent("mistral", new Modele("https://api.mistral.ai/v1", "mistral-small-latest", "", 1200, 0, 0));
		modeles.putIfAbsent("mistral-large", new Modele("https://api.mistral.ai/v1", "mistral-large-latest", "", 1200, 0, 0));
		modeles.putIfAbsent("qwen", new Modele("https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "qwen-plus", "", 1200, 0, 0));
		modeles.putIfAbsent("openai", new Modele("https://api.openai.com/v1", "gpt-4o-mini", "", 1200, 0, 0));
		modeles.putIfAbsent("openrouter", new Modele("https://openrouter.ai/api/v1", "", "", 1200, 0, 0));
		if (voix == null) {
			voix = new Voix();
		}
		if (voix.moteurs == null) {
			voix.moteurs = new java.util.LinkedHashMap<>();
		}
		voix.moteurs.putIfAbsent("elevenlabs", new Moteur("elevenlabs", "https://api.elevenlabs.io/v1", "eleven_flash_v2_5", "scribe_v1", java.util.List.of(), false));
		voix.moteurs.putIfAbsent("openai", new Moteur("openai", "https://api.openai.com/v1", "gpt-4o-mini-tts", "gpt-4o-mini-transcribe",
				java.util.List.of("alloy:n", "ash:m", "ballad:m", "coral:f", "echo:m", "fable:n", "nova:f", "onyx:m", "sage:f", "shimmer:f"), true));
		voix.moteurs.putIfAbsent("mistral", new Moteur("openai", "https://api.mistral.ai/v1", "", "voxtral-mini-latest", java.util.List.of(), false));
		voix.moteurs.putIfAbsent("groq", new Moteur("openai", "https://api.groq.com/openai/v1", "", "whisper-large-v3", java.util.List.of(), false));
	}

	/** Un service de voix : « elevenlabs », ou « openai » pour toute API compatible OpenAI (/audio/speech, /audio/transcriptions). */
	public static final class Moteur {
		public String type = "openai";
		public String url = "";
		/** Modèle de synthèse vocale ; vide si ce service n'en fait pas. */
		public String modeleVoix = "";
		/** Modèle de transcription ; vide si ce service n'en fait pas. */
		public String modeleTranscription = "";
		/** Les voix du service, pour le type « openai » : « nom:f », « nom:m » ou « nom:n » (femme, homme, neutre). ElevenLabs fournit sa liste lui-même. */
		public java.util.List<String> voix = new java.util.ArrayList<>();
		/** Le modèle de voix accepte-t-il une consigne de jeu (« instructions ») ? On lui décrit alors le personnage et son humeur. */
		public boolean consignes;

		Moteur() {
		}

		Moteur(final String type, final String url, final String modeleVoix, final String modeleTranscription, final java.util.List<String> voix, final boolean consignes) {
			this.type = type;
			this.url = url;
			this.modeleVoix = modeleVoix;
			this.modeleTranscription = modeleTranscription;
			this.voix = new java.util.ArrayList<>(voix);
			this.consignes = consignes;
		}
	}
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
		/** Quel moteur (voir « moteurs ») fait parler les villageois, et lequel transcrit le micro des joueurs. Réglés par /hameau voix synthese|transcription. */
		public String synthese = "elevenlabs";
		public String transcription = "elevenlabs";
		/** Les services de voix disponibles. La clé de chacun se met dans config/hameau-cles/&lt;nom&gt;.txt. */
		public java.util.Map<String, Moteur> moteurs = new java.util.LinkedHashMap<>();
		/** On entend un villageois jusqu'à cette distance, en blocs. */
		public float distance = 24;
		/** Au-delà, les villageois se taisent jusqu'au lendemain (le texte reste dans le chat). */
		public int plafondCaracteresParJour = 5000;
		/** Les villageois entendent-ils ce que les joueurs disent au micro (Simple Voice Chat) près d'eux ? */
		public boolean ecoute = true;
		/** Secondes de parole transcrites par jour, au plus ; au-delà il faut de nouveau écrire dans le chat. */
		public int plafondSecondesEcouteParJour = 900;
	}

	public Batir batir = new Batir();

	public static final class Batir {
		/** Modèle qui dessine les plans de construction (un appel par bâtiment), et ses tarifs par million de tokens. */
		public String modele = "claude-sonnet-5-5";
		public String profil = "sonnet";
		public String url = "";
		/** Effort de réflexion pour les plans quand le modèle n'est pas d'Anthropic : "low", "medium" ou "high" ; vide pour un modèle qui ne raisonne pas. */
		public String effort = "medium";
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
		instance.completer();
		// Un fichier d'avant les profils : on retrouve le profil d'après l'identifiant du modèle.
		for (java.util.Map.Entry<String, Modele> e : instance.modeles.entrySet()) {
			if (e.getValue().id.equals(instance.modele) && (instance.profil == null || instance.profil(instance.profil) == null || !instance.profil(instance.profil).id.equals(instance.modele))) {
				instance.profil = e.getKey();
				instance.url = e.getValue().url;
			}
			if (e.getValue().id.equals(instance.batir.modele) && (instance.batir.profil == null || instance.profil(instance.batir.profil) == null || !instance.profil(instance.batir.profil).id.equals(instance.batir.modele))) {
				instance.batir.profil = e.getKey();
				instance.batir.url = e.getValue().url;
			}
		}
		sauvegarder();
	}

	private Modele profil(final String nom) {
		for (java.util.Map.Entry<String, Modele> e : modeles.entrySet()) {
			if (e.getKey().equalsIgnoreCase(nom)) {
				return e.getValue();
			}
		}
		return null;
	}

	/** Règle d'un coup le modèle des réflexions d'après un profil : adresse, identifiant, effort, marge de réponse, tarifs. */
	public boolean choisirModele(final String nom) {
		Modele choisi = profil(nom);
		if (choisi == null || choisi.id == null || choisi.id.isBlank()) {
			return false;
		}
		profil = nom.toLowerCase();
		url = choisi.url == null ? "" : choisi.url;
		modele = choisi.id;
		effort = choisi.effort == null ? "" : choisi.effort;
		maxTokensReponse = choisi.maxTokens;
		prixEntreeParMillion = choisi.prixEntree;
		prixSortieParMillion = choisi.prixSortie;
		return true;
	}

	/** Même choix pour le modèle qui dessine les plans de construction. */
	public boolean choisirModelePlans(final String nom) {
		Modele choisi = profil(nom);
		if (choisi == null || choisi.id == null || choisi.id.isBlank()) {
			return false;
		}
		batir.profil = nom.toLowerCase();
		batir.url = choisi.url == null ? "" : choisi.url;
		batir.modele = choisi.id;
		batir.prixEntreeParMillion = choisi.prixEntree;
		batir.prixSortieParMillion = choisi.prixSortie;
		return true;
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
