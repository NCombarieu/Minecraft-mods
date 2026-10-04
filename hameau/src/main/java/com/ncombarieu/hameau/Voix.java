package com.ncombarieu.hameau;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * La voix : chaque réplique d'un villageois est synthétisée puis diffusée depuis lui par le mod Simple Voice Chat (voir voix/VoixPlugin),
 * et ce que les joueurs disent au micro est transcrit pour que les villageois l'entendent. Le service de synthèse et celui de
 * transcription se choisissent séparément parmi les « moteurs » de config/hameau.json : ElevenLabs, ou toute API compatible OpenAI.
 */
public final class Voix {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final Random HASARD = new Random();
	private static final AtomicInteger EN_VOL = new AtomicInteger();

	/** Ce que fournit Simple Voice Chat quand il est installé sur le serveur. */
	public interface Diffuseur {
		/** Un joueur équipé du mod est-il à portée d'oreille ? seul : ne compter que ce joueur. */
		boolean aDesAuditeurs(Villager villageois, UUID seul, float distance);

		/** Diffuse un son (48 kHz, mono) depuis le villageois. */
		void jouer(Villager villageois, short[] son, UUID seul, float distance);

		/** Appelé régulièrement : clôt les prises de parole terminées. */
		void tick();
	}

	public record Timbre(String id, String nom, String genre, String age, String description) {
		/** « Roger » pour « Roger - Laid-Back, Casual, Resonant ». */
		String prenom() {
			return nom.split("\\s", 2)[0];
		}
	}

	/** Un service a refusé la demande (quota, clé, modèle inconnu…). */
	private static final class Refus extends RuntimeException {
		final int statut;

		Refus(final int statut, final String detail) {
			super(detail);
			this.statut = statut;
		}
	}

	public static volatile Diffuseur diffuseur;
	static volatile MinecraftServer serveur;
	private static volatile String cleSynthese;
	private static volatile String cleTranscription;
	private static volatile List<Timbre> catalogue = List.of();
	/** Dernier refus du service de synthèse, affiché par /hameau voix ; null si tout va bien. */
	static volatile String panne;
	private static volatile long pauseJusqua;
	private static volatile long ecoutePauseJusqua;

	private Voix() {
	}

	private static HameauConfig.Moteur moteur(final String nom) {
		return nom == null ? null : HameauConfig.get().voix.moteurs.get(nom.toLowerCase());
	}

	private static String adresse(final HameauConfig.Moteur moteur) {
		return moteur.url.replaceAll("/+$", "");
	}

	private static HttpRequest.Builder requete(final HameauConfig.Moteur moteur, final String chemin, final String cle) {
		HttpRequest.Builder requete = HttpRequest.newBuilder(URI.create(adresse(moteur) + chemin)).timeout(Duration.ofSeconds(25));
		if (cle != null) {
			requete.header(moteur.type.equals("elevenlabs") ? "xi-api-key" : "Authorization", moteur.type.equals("elevenlabs") ? cle : "Bearer " + cle);
		}
		return requete;
	}

	/** Relit les clés et dresse la liste des voix du moteur de synthèse. */
	static void demarrer() {
		HameauConfig.Voix config = HameauConfig.get().voix;
		HameauConfig.Moteur synthese = moteur(config.synthese);
		HameauConfig.Moteur transcription = moteur(config.transcription);
		cleSynthese = synthese == null ? null : Cles.lire(config.synthese);
		cleTranscription = transcription == null ? null : Cles.lire(config.transcription);
		panne = null;
		pauseJusqua = 0;
		ecoutePauseJusqua = 0;
		catalogue = List.of();
		if (synthese == null || synthese.modeleVoix == null || synthese.modeleVoix.isBlank() || (cleSynthese == null && !Cles.local(synthese.url))) {
			return;
		}
		if (!synthese.type.equals("elevenlabs")) {
			// « nova:f » : une voix de femme nommée nova.
			List<Timbre> lus = new ArrayList<>();
			for (String voix : synthese.voix) {
				String[] parties = voix.split(":", 2);
				String genre = parties.length < 2 ? "neutral" : parties[1].startsWith("f") ? "female" : parties[1].startsWith("m") || parties[1].startsWith("h") ? "male" : "neutral";
				lus.add(new Timbre(parties[0].trim(), parties[0].trim(), genre, "", ""));
			}
			catalogue = List.copyOf(lus);
			return;
		}
		String cle = cleSynthese;
		CompletableFuture.runAsync(() -> {
			try {
				HttpResponse<String> reponse = HTTP.send(requete(synthese, "/voices", cle).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (reponse.statusCode() != 200) {
					panne = "liste des voix refusée (" + reponse.statusCode() + ")";
					Hameau.LOGGER.warn("Hameau : ElevenLabs refuse la liste des voix ({}) : {}", reponse.statusCode(), abrege(reponse.body()));
					return;
				}
				List<Timbre> lus = new ArrayList<>();
				for (JsonElement e : JsonParser.parseString(reponse.body()).getAsJsonObject().getAsJsonArray("voices")) {
					JsonObject v = e.getAsJsonObject();
					JsonObject labels = v.has("labels") && v.get("labels").isJsonObject() ? v.getAsJsonObject("labels") : new JsonObject();
					lus.add(new Timbre(v.get("voice_id").getAsString(), v.get("name").getAsString(), texte(labels, "gender"), texte(labels, "age"), texte(labels, "descriptive")));
				}
				catalogue = List.copyOf(lus);
				Hameau.LOGGER.info("Hameau : {} voix disponibles chez ElevenLabs.", lus.size());
			} catch (java.io.IOException | InterruptedException | RuntimeException e) {
				panne = "liste des voix illisible";
				Hameau.LOGGER.warn("Hameau : liste des voix illisible : {}", e.toString());
			}
		});
	}

	private static String texte(final JsonObject json, final String cle) {
		return json.has(cle) && json.get(cle).isJsonPrimitive() ? json.get(cle).getAsString() : "";
	}

	private static String abrege(final String texte) {
		return texte.length() > 300 ? texte.substring(0, 300) + "…" : texte;
	}

	static void tick(final MinecraftServer server) {
		serveur = server;
		Diffuseur d = diffuseur;
		if (d != null && server.getTickCount() % 4 == 0) {
			d.tick();
		}
	}

	/** Le moteur de synthèse est-il utilisable (modèle, clé, voix) ? */
	private static boolean syntheseePrete() {
		return HameauConfig.get().voix.actif && !catalogue.isEmpty();
	}

	/** Les villageois parlent-ils à voix haute en ce moment ? */
	static boolean active() {
		return syntheseePrete() && diffuseur != null;
	}

	/** Les villageois écoutent-ils les micros en ce moment ? */
	public static boolean ecoute() {
		HameauConfig.Voix config = HameauConfig.get().voix;
		HameauConfig.Moteur moteur = moteur(config.transcription);
		return config.actif && config.ecoute && serveur != null && moteur != null && moteur.modeleTranscription != null && !moteur.modeleTranscription.isBlank()
				&& (cleTranscription != null || Cles.local(moteur.url)) && System.currentTimeMillis() >= ecoutePauseJusqua;
	}

	static String etat() {
		HameauConfig.Voix config = HameauConfig.get().voix;
		if (!config.actif) {
			return "désactivée dans config/hameau.json";
		}
		HameauConfig.Moteur synthese = moteur(config.synthese);
		String parole;
		if (synthese == null) {
			parole = "moteur « " + config.synthese + " » inconnu";
		} else if (synthese.modeleVoix == null || synthese.modeleVoix.isBlank()) {
			parole = "« " + config.synthese + " » ne fait pas de synthèse (modeleVoix vide)";
		} else if (cleSynthese == null && !Cles.local(synthese.url)) {
			parole = "pas de clé (config/hameau-cles/" + config.synthese + ".txt)";
		} else if (catalogue.isEmpty()) {
			parole = panne != null ? panne : synthese.type.equals("elevenlabs") ? "liste des voix en cours de chargement" : "aucune voix listée pour ce moteur dans config/hameau.json";
		} else {
			parole = (panne != null ? "en panne : " + panne : "prête") + ", " + catalogue.size() + " voix, " + Ames.budget.voixJour + " caractères dits aujourd'hui (plafond " + config.plafondCaracteresParJour + ")";
		}
		String oreille = !config.ecoute ? "coupée" : ecoute() ? "prête, " + Ames.budget.ecouteJour + " s aujourd'hui (plafond " + config.plafondSecondesEcouteParJour + ")"
				: "indisponible (moteur, modèle ou clé manquant, ou service en pause après un refus)";
		return "synthèse par " + config.synthese + " : " + parole + " — transcription par " + config.transcription + " : " + oreille
				+ (diffuseur == null ? " — Simple Voice Chat absent du serveur : rien ne sera entendu" : "");
	}

	static List<Timbre> catalogue() {
		return catalogue;
	}

	static Timbre parNom(final String nom) {
		if (nom == null) {
			return null;
		}
		String cherche = Ames.simplifier(nom);
		for (Timbre timbre : catalogue) {
			if (Ames.simplifier(timbre.prenom()).equals(cherche) || Ames.simplifier(timbre.nom()).equals(cherche)) {
				return timbre;
			}
		}
		return null;
	}

	private static boolean convient(final Timbre timbre, final boolean femme) {
		return !timbre.genre().equals(femme ? "male" : "female");
	}

	private static int porteurs(final Timbre timbre) {
		int porteurs = 0;
		for (Ame ame : Ames.toutes()) {
			porteurs += timbre.id().equals(ame.voixId) ? 1 : 0;
		}
		return porteurs;
	}

	/** Les voix possibles pour cette âme, à proposer à Claude quand il invente sa personnalité. Vide si la voix est coupée. */
	static String choix(final Ame ame) {
		if (!syntheseePrete()) {
			return "";
		}
		List<String> lignes = new ArrayList<>();
		for (Timbre timbre : catalogue) {
			if (convient(timbre, ame.femme)) {
				int porteurs = porteurs(timbre);
				String portrait = String.join(", ", List.of(timbre.age().replace('_', ' '), timbre.nom().contains(" - ") ? timbre.nom().split(" - ", 2)[1] : timbre.description())).replaceAll("^, |, $", "");
				lignes.add(timbre.prenom() + (portrait.isBlank() && porteurs == 0 ? "" : " (" + portrait + (porteurs > 0 ? (portrait.isBlank() ? "" : " ; ") + "déjà portée par " + porteurs + " habitant" + (porteurs > 1 ? "s" : "") : "") + ")"));
			}
		}
		return String.join(" ; ", lignes);
	}

	static void donner(final Ame ame, final Timbre timbre) {
		ame.voixId = timbre.id();
		ame.voixNom = timbre.prenom();
	}

	/** Donne une voix à qui n'en a pas (ou plus, après un changement de moteur) : du bon sexe, et la moins portée possible. */
	private static Timbre attribuer(final Ame ame) {
		for (Timbre timbre : catalogue) {
			if (timbre.id().equals(ame.voixId)) {
				return timbre;
			}
		}
		List<Timbre> meilleurs = new ArrayList<>();
		int moins = Integer.MAX_VALUE;
		for (Timbre timbre : catalogue) {
			if (!convient(timbre, ame.femme)) {
				continue;
			}
			int porteurs = porteurs(timbre);
			if (porteurs < moins) {
				moins = porteurs;
				meilleurs.clear();
			}
			if (porteurs == moins) {
				meilleurs.add(timbre);
			}
		}
		if (meilleurs.isEmpty()) {
			return null;
		}
		Timbre choisi = meilleurs.get(HASARD.nextInt(meilleurs.size()));
		donner(ame, choisi);
		return choisi;
	}

	/** Demande le son d'une réplique au moteur de synthèse. @return le son en 48 kHz mono */
	private static CompletableFuture<short[]> synthetiser(final String texte, final Timbre timbre, final Ame ame) {
		HameauConfig.Voix config = HameauConfig.get().voix;
		HameauConfig.Moteur moteur = moteur(config.synthese);
		float debit = Math.clamp((ame == null || ame.debit == 0 ? 1F : ame.debit) + (ame != null && ame.enfant ? 0.08F : 0F), 0.7F, 1.2F);
		JsonObject corps = new JsonObject();
		String chemin;
		boolean elevenlabs = moteur.type.equals("elevenlabs");
		if (elevenlabs) {
			chemin = "/text-to-speech/" + timbre.id() + "?output_format=pcm_48000";
			corps.addProperty("text", texte);
			corps.addProperty("model_id", moteur.modeleVoix);
			corps.addProperty("language_code", "fr");
			JsonObject reglages = new JsonObject();
			reglages.addProperty("speed", debit);
			corps.add("voice_settings", reglages);
		} else {
			chemin = "/audio/speech";
			corps.addProperty("model", moteur.modeleVoix);
			corps.addProperty("voice", timbre.id());
			corps.addProperty("input", texte);
			corps.addProperty("response_format", "wav");
			if (moteur.consignes && ame != null) {
				corps.addProperty("instructions", "Parle en français, comme on se parle entre gens d'un village. Tu joues " + ame.nom + ", " + (ame.femme ? "une femme" : "un homme") + (ame.enfant ? ", encore enfant" : "")
						+ " : " + String.join(", ", ame.traits) + ". Sa façon de parler : " + ame.parler + ". Son humeur en ce moment : " + ame.humeur + ".");
			} else {
				corps.addProperty("speed", debit);
			}
		}
		HttpRequest requete = requete(moteur, chemin, cleSynthese).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(corps.toString(), StandardCharsets.UTF_8)).build();
		return HTTP.sendAsync(requete, HttpResponse.BodyHandlers.ofByteArray()).thenApply(reponse -> {
			if (reponse.statusCode() != 200) {
				throw new Refus(reponse.statusCode(), abrege(new String(reponse.body(), StandardCharsets.UTF_8)));
			}
			if (elevenlabs) {
				short[] son = new short[reponse.body().length / 2];
				ByteBuffer.wrap(reponse.body()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(son);
				return son;
			}
			return depuisWav(reponse.body());
		});
	}

	/** Lit un fichier WAV (PCM 16 bits) quelle que soit sa cadence, et le ramène en 48 kHz mono. */
	static short[] depuisWav(final byte[] wav) {
		ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
		int cadence = 24000;
		int canaux = 1;
		int debut = -1;
		int taille = 0;
		if (wav.length > 12 && wav[0] == 'R' && wav[1] == 'I' && wav[2] == 'F' && wav[3] == 'F') {
			int i = 12;
			while (i + 8 <= wav.length) {
				String bloc = new String(wav, i, 4, StandardCharsets.US_ASCII);
				long longueur = b.getInt(i + 4) & 0xFFFFFFFFL;
				if (bloc.equals("fmt ")) {
					canaux = Math.max(1, b.getShort(i + 10));
					cadence = b.getInt(i + 12);
					if (b.getShort(i + 22) != 16) {
						throw new IllegalStateException("son WAV en " + b.getShort(i + 22) + " bits : seul le 16 bits est lu");
					}
				} else if (bloc.equals("data")) {
					debut = i + 8;
					// Un son envoyé au fil de l'eau annonce une longueur fictive : on prend ce qui reste.
					taille = (int) Math.min(longueur, wav.length - debut);
					break;
				}
				i += 8 + (int) Math.min(longueur, Integer.MAX_VALUE - 16) + (int) (longueur & 1);
			}
		}
		if (debut < 0) {
			// Pas d'en-tête : du PCM brut, que l'API d'OpenAI livre en 24 kHz.
			debut = 0;
			taille = wav.length;
		}
		int trames = taille / (2 * canaux);
		short[] mono = new short[trames];
		for (int t = 0; t < trames; t++) {
			int somme = 0;
			for (int c = 0; c < canaux; c++) {
				somme += b.getShort(debut + (t * canaux + c) * 2);
			}
			mono[t] = (short) (somme / canaux);
		}
		if (cadence == 48000 || trames < 2) {
			return mono;
		}
		short[] son = new short[(int) ((long) trames * 48000 / cadence)];
		for (int i = 0; i < son.length; i++) {
			double position = (double) i * cadence / 48000;
			int avant = Math.min((int) position, trames - 1);
			int apres = Math.min(avant + 1, trames - 1);
			double part = position - avant;
			son[i] = (short) (mono[avant] * (1 - part) + mono[apres] * part);
		}
		return son;
	}

	/** Fichier WAV 16 kHz mono : trois fois plus léger que le 48 kHz du micro, et bien assez pour de la parole. */
	private static byte[] wav(final short[] son) {
		int echantillons = son.length / 3;
		ByteBuffer wav = ByteBuffer.allocate(44 + echantillons * 2).order(ByteOrder.LITTLE_ENDIAN);
		wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + echantillons * 2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
				.putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
				.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(echantillons * 2);
		for (int i = 0; i < echantillons; i++) {
			wav.putShort((short) ((son[i * 3] + son[i * 3 + 1] + son[i * 3 + 2]) / 3));
		}
		return wav.array();
	}

	/** Demande au moteur de transcription ce qui est dit dans ce son (48 kHz mono). */
	private static CompletableFuture<String> transcrire(final short[] son) {
		HameauConfig.Moteur moteur = moteur(HameauConfig.get().voix.transcription);
		boolean elevenlabs = moteur.type.equals("elevenlabs");
		String[][] champs = elevenlabs ? new String[][] {{"model_id", moteur.modeleTranscription}, {"language_code", "fr"}, {"tag_audio_events", "false"}}
				: new String[][] {{"model", moteur.modeleTranscription}, {"language", "fr"}, {"response_format", "json"}};
		String frontiere = "hameau" + Long.toHexString(HASARD.nextLong());
		ByteArrayOutputStream corps = new ByteArrayOutputStream();
		for (String[] champ : champs) {
			corps.writeBytes(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"" + champ[0] + "\"\r\n\r\n" + champ[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
		}
		corps.writeBytes(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"parole.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		corps.writeBytes(wav(son));
		corps.writeBytes(("\r\n--" + frontiere + "--\r\n").getBytes(StandardCharsets.UTF_8));
		HttpRequest requete = requete(moteur, elevenlabs ? "/speech-to-text" : "/audio/transcriptions", cleTranscription)
				.header("Content-Type", "multipart/form-data; boundary=" + frontiere).POST(HttpRequest.BodyPublishers.ofByteArray(corps.toByteArray())).build();
		return HTTP.sendAsync(requete, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(reponse -> {
			if (reponse.statusCode() != 200) {
				throw new Refus(reponse.statusCode(), abrege(reponse.body()));
			}
			// Les bruits notés entre parenthèses (« (rires) ») ne sont pas des paroles.
			return texte(JsonParser.parseString(reponse.body()).getAsJsonObject(), "text").replaceAll("\\([^)]*\\)", " ").replaceAll("\\s+", " ").trim();
		});
	}

	private static Refus refus(final Throwable erreur) {
		Throwable cause = erreur instanceof java.util.concurrent.CompletionException && erreur.getCause() != null ? erreur.getCause() : erreur;
		return cause instanceof Refus refus ? refus : null;
	}

	private static boolean durable(final Refus refus) {
		return refus != null && (refus.statut == 401 || refus.statut == 402 || refus.statut == 403 || refus.statut == 404 || refus.getMessage().contains("insufficient_quota"));
	}

	/**
	 * Fait dire la réplique à voix haute, si quelqu'un peut l'entendre.
	 * @param seul le seul joueur qui doit entendre (chuchotement), ou null pour tous ceux à portée
	 */
	static void parler(final MinecraftServer server, final Villager villageois, final Ame ame, final String replique, final UUID seul, final boolean pourUnJoueur) {
		HameauConfig.Voix config = HameauConfig.get().voix;
		Diffuseur d = diffuseur;
		if (!active() || System.currentTimeMillis() < pauseJusqua || EN_VOL.get() >= 4) {
			return;
		}
		String dit = replique.replaceAll("[*_#«»\"]", " ").replaceAll("\\s+", " ").trim();
		String texte = dit.length() > 400 ? dit.substring(0, 400) : dit;
		float distance = seul != null ? Math.min(config.distance, 8) : config.distance;
		// Ce qui se dit entre villageois ne vaut d'être entendu que de tout près.
		if (texte.isEmpty() || !Ames.budget.voixAutorisee(texte.length()) || !Ames.budget.autorise() || !d.aDesAuditeurs(villageois, seul, pourUnJoueur ? distance : Math.min(distance, config.distanceBavardage))) {
			return;
		}
		Timbre timbre = attribuer(ame);
		if (timbre == null) {
			return;
		}
		if (ame.debit == 0) {
			ame.debit = 0.9F + HASARD.nextFloat() * 0.22F;
		}
		UUID uuid = villageois.getUUID();
		EN_VOL.incrementAndGet();
		synthetiser(texte, timbre, ame).whenComplete((son, erreur) -> server.execute(() -> {
			EN_VOL.decrementAndGet();
			if (erreur != null) {
				Refus refus = refus(erreur);
				// Quota épuisé ou clé refusée : inutile d'insister à chaque réplique.
				pauseJusqua = System.currentTimeMillis() + (durable(refus) ? 30 * 60_000L : 60_000L);
				panne = (refus == null ? "service injoignable" : refus.getMessage().contains("quota") || refus.getMessage().contains("credits") ? "quota ou crédit épuisé" : "réponse " + refus.statut) + ", nouvel essai dans " + (durable(refus) ? "30 minutes" : "une minute");
				Hameau.LOGGER.warn("Hameau : voix de {} refusée par {} : {}", ame.nom, config.synthese, refus != null ? refus.statut + " " + refus.getMessage() : erreur.toString());
				return;
			}
			panne = null;
			Ames.budget.voixDite(texte.length());
			Ames.budget.depenser(son.length / 48000.0 / 60 * moteur(config.synthese).prixVoixParMinute);
			Villager present = Vie.trouver(server, uuid);
			Diffuseur actuel = diffuseur;
			if (present != null && present.isAlive() && actuel != null) {
				actuel.jouer(present, son, seul, distance);
			}
		}));
	}

	/**
	 * Un joueur vient de dire quelque chose au micro : si un villageois est assez près pour l'entendre, on transcrit et il l'entend comme un message du chat.
	 * @param son sa prise de parole, 48 kHz mono
	 */
	public static void entendu(final UUID joueur, final short[] son) {
		MinecraftServer server = serveur;
		if (server == null || !ecoute()) {
			return;
		}
		server.execute(() -> {
			ServerPlayer parleur = server.getPlayerList().getPlayer(joueur);
			if (parleur == null || parleur.isSpectator() || Vie.enPause || !Ames.budget.ecouteAutorisee()
					|| parleur.level().getEntitiesOfClass(Villager.class, parleur.getBoundingBox().inflate(24),
							v -> v.isAlive() && (v.distanceTo(parleur) <= 10 || parleur.getName().getString().equals(Ames.de(v).maitre))).isEmpty()) {
				return;
			}
			long secondes = Math.max(1, son.length / 48000);
			transcrire(son).whenComplete((dit, erreur) -> server.execute(() -> {
				if (erreur != null) {
					Refus refus = refus(erreur);
					ecoutePauseJusqua = System.currentTimeMillis() + (durable(refus) ? 30 * 60_000L : 20_000L);
					Hameau.LOGGER.warn("Hameau : parole de {} non transcrite par {} : {}", parleur.getName().getString(), HameauConfig.get().voix.transcription, refus != null ? refus.statut + " " + refus.getMessage() : erreur.toString());
					return;
				}
				Ames.budget.ecoutee(secondes);
				Ames.budget.depenser(secondes / 60.0 * moteur(HameauConfig.get().voix.transcription).prixTranscriptionParMinute);
				// Un souffle ou un bruit donne une transcription vide ou d'un seul signe.
				if (dit.replaceAll("[^\\p{L}\\p{N}]", "").length() < 2 || parleur.isRemoved()) {
					return;
				}
				Hameau.LOGGER.info("<{}> (au micro) {}", parleur.getName().getString(), dit);
				parleur.sendSystemMessage(Component.literal("Tu dis : « " + dit + " »").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
				Evenements.entendre(parleur, dit, true);
			}));
		});
	}

	/**
	 * /hameau voix essai : fait dire une phrase au moteur de synthèse, puis la donne à transcrire au moteur de transcription.
	 * Dit à l'opérateur ce qui marche, et fait entendre la phrase depuis le villageois le plus proche s'il y en a un.
	 */
	static void essai(final CommandSourceStack source, final Villager villageois, final String phrase) {
		MinecraftServer server = source.getServer();
		HameauConfig.Voix config = HameauConfig.get().voix;
		if (!syntheseePrete()) {
			source.sendFailure(Component.literal("Synthèse indisponible : " + etat()));
			return;
		}
		Ame ame = villageois != null ? Ames.de(villageois) : null;
		Timbre timbre = ame != null ? attribuer(ame) : catalogue.getFirst();
		if (timbre == null) {
			timbre = catalogue.getFirst();
		}
		String voix = timbre.prenom();
		long depart = System.currentTimeMillis();
		synthetiser(phrase, timbre, ame).whenComplete((son, erreur) -> server.execute(() -> {
			if (erreur != null) {
				Refus refus = refus(erreur);
				source.sendFailure(Component.literal("Synthèse par " + config.synthese + " : échec — " + (refus != null ? refus.statut + " " + refus.getMessage() : erreur.toString())));
				return;
			}
			Ames.budget.voixDite(phrase.length());
			Ames.budget.depenser(son.length / 48000.0 / 60 * moteur(config.synthese).prixVoixParMinute);
			source.sendSuccess(() -> Component.literal(String.format("Synthèse par %s : %.1f s de son reçues en %d ms (voix « %s »).", config.synthese, son.length / 48000.0, System.currentTimeMillis() - depart, voix))
					.withStyle(ChatFormatting.YELLOW), false);
			Diffuseur d = diffuseur;
			if (d != null && villageois != null && villageois.isAlive()) {
				d.jouer(villageois, son, null, config.distance);
			}
			HameauConfig.Moteur oreille = moteur(config.transcription);
			if (oreille == null || oreille.modeleTranscription == null || oreille.modeleTranscription.isBlank() || (cleTranscription == null && !Cles.local(oreille.url))) {
				source.sendFailure(Component.literal("Transcription par " + config.transcription + " : indisponible (moteur, modèle ou clé manquant)."));
				return;
			}
			long retour = System.currentTimeMillis();
			transcrire(son).whenComplete((dit, echec) -> server.execute(() -> {
				if (echec != null) {
					Refus refus = refus(echec);
					source.sendFailure(Component.literal("Transcription par " + config.transcription + " : échec — " + (refus != null ? refus.statut + " " + refus.getMessage() : echec.toString())));
					return;
				}
				source.sendSuccess(() -> Component.literal("Transcription par " + config.transcription + " en " + (System.currentTimeMillis() - retour) + " ms : « " + dit + " »").withStyle(ChatFormatting.YELLOW), false);
			}));
		}));
	}
}
