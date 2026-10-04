package com.ncombarieu.hameau;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * La voix des villageois : chaque réplique est synthétisée par ElevenLabs puis diffusée depuis le villageois par le mod
 * Simple Voice Chat (voir voix/VoixPlugin). Sans ce mod sur le serveur, ou sans clé, tout reste muet comme avant.
 */
public final class Voix {
	private static final Path FICHIER_CLE = FabricLoader.getInstance().getConfigDir().resolve("hameau-voix-cle.txt");
	private static final String API = "https://api.elevenlabs.io/v1";
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

	public static volatile Diffuseur diffuseur;
	private static volatile String cle;
	private static volatile List<Timbre> catalogue = List.of();
	/** Dernier refus d'ElevenLabs (quota épuisé, clé invalide…), affiché par /hameau voix ; null si tout va bien. */
	static volatile String panne;
	private static volatile long pauseJusqua;
	private static volatile long ecoutePauseJusqua;
	static volatile MinecraftServer serveur;

	private Voix() {
	}

	/** Lit la clé (ELEVENLABS_API_KEY ou config/hameau-voix-cle.txt) et charge la liste des voix du compte. */
	static void demarrer() {
		String lue = System.getenv("ELEVENLABS_API_KEY");
		if (lue == null || lue.isBlank()) {
			try {
				lue = Files.exists(FICHIER_CLE) ? Files.readString(FICHIER_CLE, StandardCharsets.UTF_8).trim() : null;
			} catch (IOException e) {
				Hameau.LOGGER.error("Impossible de lire {}", FICHIER_CLE, e);
			}
		}
		cle = lue == null || lue.isBlank() ? null : lue;
		panne = null;
		pauseJusqua = 0;
		if (cle == null) {
			catalogue = List.of();
			return;
		}
		String c = cle;
		CompletableFuture.runAsync(() -> {
			try {
				HttpResponse<String> reponse = HTTP.send(HttpRequest.newBuilder(URI.create(API + "/voices")).header("xi-api-key", c).timeout(Duration.ofSeconds(20)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
			} catch (IOException | InterruptedException | RuntimeException e) {
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

	/** Les villageois écoutent-ils les micros en ce moment ? */
	public static boolean ecoute() {
		HameauConfig.Voix config = HameauConfig.get().voix;
		return config.actif && config.ecoute && cle != null && serveur != null && System.currentTimeMillis() >= ecoutePauseJusqua;
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
			net.minecraft.server.level.ServerPlayer parleur = server.getPlayerList().getPlayer(joueur);
			if (parleur == null || parleur.isSpectator() || Vie.enPause || !Ames.budget.ecouteAutorisee()
					|| parleur.level().getEntitiesOfClass(Villager.class, parleur.getBoundingBox().inflate(10), Villager::isAlive).isEmpty()) {
				return;
			}
			long secondes = Math.max(1, son.length / 48000);
			byte[] wav = wav(son);
			String frontiere = "hameau" + Long.toHexString(HASARD.nextLong());
			java.io.ByteArrayOutputStream corps = new java.io.ByteArrayOutputStream();
			for (String[] champ : new String[][] {{"model_id", "scribe_v1"}, {"language_code", "fr"}, {"tag_audio_events", "false"}}) {
				corps.writeBytes(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"" + champ[0] + "\"\r\n\r\n" + champ[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
			}
			corps.writeBytes(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"parole.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			corps.writeBytes(wav);
			corps.writeBytes(("\r\n--" + frontiere + "--\r\n").getBytes(StandardCharsets.UTF_8));
			HttpRequest requete = HttpRequest.newBuilder(URI.create(API + "/speech-to-text")).header("xi-api-key", cle)
					.header("Content-Type", "multipart/form-data; boundary=" + frontiere).timeout(Duration.ofSeconds(20))
					.POST(HttpRequest.BodyPublishers.ofByteArray(corps.toByteArray())).build();
			HTTP.sendAsync(requete, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((reponse, erreur) -> server.execute(() -> {
				if (erreur != null || reponse.statusCode() != 200) {
					boolean durable = erreur == null && (reponse.statusCode() == 401 || reponse.statusCode() == 402 || reponse.statusCode() == 403);
					ecoutePauseJusqua = System.currentTimeMillis() + (durable ? 30 * 60_000L : 20_000L);
					Hameau.LOGGER.warn("Hameau : parole de {} non transcrite : {}", parleur.getName().getString(), erreur != null ? erreur.toString() : reponse.statusCode() + " " + abrege(reponse.body()));
					return;
				}
				Ames.budget.ecoutee(secondes);
				String dit = texte(JsonParser.parseString(reponse.body()).getAsJsonObject(), "text").replaceAll("\\([^)]*\\)", " ").replaceAll("\\s+", " ").trim();
				// Un souffle ou un bruit donne une transcription vide ou d'un seul signe.
				if (dit.replaceAll("[^\\p{L}\\p{N}]", "").length() < 2 || parleur.isRemoved()) {
					return;
				}
				Hameau.LOGGER.info("<{}> (au micro) {}", parleur.getName().getString(), dit);
				parleur.sendSystemMessage(net.minecraft.network.chat.Component.literal("Tu dis : « " + dit + " »").withStyle(net.minecraft.ChatFormatting.DARK_GRAY, net.minecraft.ChatFormatting.ITALIC));
				Evenements.entendre(parleur, dit, true);
			}));
		});
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

	/** Les villageois parlent-ils à voix haute en ce moment ? */
	static boolean active() {
		return HameauConfig.get().voix.actif && diffuseur != null && cle != null && !catalogue.isEmpty();
	}

	static String etat() {
		HameauConfig.Voix config = HameauConfig.get().voix;
		if (!config.actif) {
			return "désactivée dans config/hameau.json";
		}
		if (diffuseur == null) {
			return "le mod Simple Voice Chat n'est pas installé sur le serveur";
		}
		if (cle == null) {
			return "pas de clé ElevenLabs (config/hameau-voix-cle.txt)";
		}
		if (catalogue.isEmpty()) {
			return panne != null ? panne : "liste des voix en cours de chargement";
		}
		return (panne != null ? "en panne : " + panne : "active") + (config.ecoute ? ", micro écouté (" + Ames.budget.ecouteJour + " s aujourd'hui, plafond " + config.plafondSecondesEcouteParJour + ")" : ", micro non écouté") + " — " + catalogue.size() + " voix, " + Ames.budget.voixJour + " caractères dits aujourd'hui (plafond " + config.plafondCaracteresParJour
				+ "), " + Ames.budget.voixCaracteres + " au total";
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
		if (cle == null || !HameauConfig.get().voix.actif) {
			return "";
		}
		List<String> lignes = new ArrayList<>();
		for (Timbre timbre : catalogue) {
			if (convient(timbre, ame.femme)) {
				int porteurs = porteurs(timbre);
				lignes.add(timbre.prenom() + " (" + String.join(", ", List.of(timbre.age().replace('_', ' '), timbre.nom().contains(" - ") ? timbre.nom().split(" - ", 2)[1] : timbre.description()))
						+ (porteurs > 0 ? " ; déjà portée par " + porteurs + " habitant" + (porteurs > 1 ? "s" : "") : "") + ")");
			}
		}
		return String.join(" ; ", lignes);
	}

	static void donner(final Ame ame, final Timbre timbre) {
		ame.voixId = timbre.id();
		ame.voixNom = timbre.prenom();
	}

	/** Donne une voix à qui n'en a pas : du bon sexe, et la moins portée possible pour que les voisins ne se ressemblent pas. */
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

	/**
	 * Fait dire la réplique à voix haute, si quelqu'un peut l'entendre.
	 * @param seul le seul joueur qui doit entendre (chuchotement), ou null pour tous ceux à portée
	 */
	static void parler(final MinecraftServer server, final Villager villageois, final Ame ame, final String replique, final UUID seul) {
		HameauConfig.Voix config = HameauConfig.get().voix;
		Diffuseur d = diffuseur;
		if (!active() || System.currentTimeMillis() < pauseJusqua || EN_VOL.get() >= 3) {
			return;
		}
		String texte = replique.replaceAll("[*_#«»\"]", " ").replaceAll("\\s+", " ").trim();
		if (texte.length() > 400) {
			texte = texte.substring(0, 400);
		}
		float distance = seul != null ? Math.min(config.distance, 8) : config.distance;
		if (texte.isEmpty() || !Ames.budget.voixAutorisee(texte.length()) || !d.aDesAuditeurs(villageois, seul, distance)) {
			return;
		}
		Timbre timbre = attribuer(ame);
		if (timbre == null) {
			return;
		}
		if (ame.debit == 0) {
			ame.debit = 0.9F + HASARD.nextFloat() * 0.22F;
		}
		JsonObject corps = new JsonObject();
		corps.addProperty("text", texte);
		corps.addProperty("model_id", config.modele);
		corps.addProperty("language_code", "fr");
		JsonObject reglages = new JsonObject();
		reglages.addProperty("speed", Math.clamp(ame.debit + (ame.enfant ? 0.08F : 0F), 0.7F, 1.2F));
		corps.add("voice_settings", reglages);
		HttpRequest requete = HttpRequest.newBuilder(URI.create(API + "/text-to-speech/" + timbre.id() + "?output_format=pcm_48000"))
				.header("xi-api-key", cle).header("Content-Type", "application/json").timeout(Duration.ofSeconds(20))
				.POST(HttpRequest.BodyPublishers.ofString(corps.toString(), StandardCharsets.UTF_8)).build();
		int caracteres = texte.length();
		UUID uuid = villageois.getUUID();
		EN_VOL.incrementAndGet();
		HTTP.sendAsync(requete, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((reponse, erreur) -> server.execute(() -> {
			EN_VOL.decrementAndGet();
			if (erreur != null) {
				Hameau.LOGGER.warn("Hameau : voix de {} indisponible : {}", ame.nom, erreur.toString());
				return;
			}
			if (reponse.statusCode() != 200) {
				String detail = abrege(new String(reponse.body(), StandardCharsets.UTF_8));
				// Quota épuisé ou clé refusée : inutile d'insister à chaque réplique.
				boolean durable = reponse.statusCode() == 401 || reponse.statusCode() == 402 || reponse.statusCode() == 403;
				pauseJusqua = System.currentTimeMillis() + (durable ? 30 * 60_000L : 60_000L);
				panne = (detail.contains("quota") ? "quota ElevenLabs épuisé" : "ElevenLabs a répondu " + reponse.statusCode()) + ", nouvel essai dans " + (durable ? "30 minutes" : "une minute");
				Hameau.LOGGER.warn("Hameau : ElevenLabs refuse la voix de {} ({}) : {}", ame.nom, reponse.statusCode(), detail);
				return;
			}
			panne = null;
			Ames.budget.voixDite(caracteres);
			Villager present = Vie.trouver(server, uuid);
			Diffuseur actuel = diffuseur;
			if (present == null || !present.isAlive() || actuel == null) {
				return;
			}
			short[] son = new short[reponse.body().length / 2];
			ByteBuffer.wrap(reponse.body()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(son);
			actuel.jouer(present, son, seul, distance);
		}));
	}
}
