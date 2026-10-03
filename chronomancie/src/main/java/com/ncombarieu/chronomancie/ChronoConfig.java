package com.ncombarieu.chronomancie;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Réglages du mod, lus depuis config/chronomancie.json (créé au premier lancement).
 * Toutes les durées sont en secondes, les distances en blocs.
 */
public final class ChronoConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Path FICHIER = FabricLoader.getInstance().getConfigDir().resolve("chronomancie.json");

	private static ChronoConfig instance = new ChronoConfig();

	public Failles failles = new Failles();
	public Sablier sablier = new Sablier();
	public Miroir miroir = new Miroir();
	public Stase stase = new Stase();
	public Chronoscope chronoscope = new Chronoscope();

	public static final class Failles {
		/** Les failles apparaissent-elles toutes seules près des joueurs ? */
		public boolean apparitionNaturelle = true;
		/** Toutes les N secondes, chaque joueur de la surface a une chance de voir une faille s'ouvrir. */
		public int intervalleVerification = 30;
		/** Chance (0 à 1) à chaque vérification, de jour. Par défaut ≈ une faille toutes les 25 min par joueur. */
		public double chanceJour = 0.02;
		/** Chance la nuit : le voile du temps est plus fin. */
		public double chanceNuit = 0.05;
		public int maxSimultanees = 3;
		public int distanceMin = 24;
		public int distanceMax = 48;
		/** Durée de vie d'une faille que personne ne vient refermer. */
		public int dureeVie = 300;
		public int vagues = 3;
	}

	public static final class Sablier {
		public int secondesRemontees = 8;
		public int recharge = 20;
		public int charges = 24;
	}

	public static final class Miroir {
		public int dureeEcho = 10;
		public int coupsAvantBris = 6;
		public int recharge = 30;
		public int charges = 16;
	}

	public static final class Stase {
		public double rayon = 7.0;
		public int duree = 6;
		public int recharge = 45;
		public int charges = 10;
	}

	public static final class Chronoscope {
		public int rayon = 24;
		/** Combien de temps le monde se souvient d'une explosion. */
		public int memoireMinutes = 30;
		public int maxBlocsParUsage = 800;
		public int recharge = 10;
		public int charges = 12;
	}

	public static ChronoConfig get() {
		return instance;
	}

	public static void charger() {
		if (Files.exists(FICHIER)) {
			try (Reader reader = Files.newBufferedReader(FICHIER, StandardCharsets.UTF_8)) {
				ChronoConfig lue = GSON.fromJson(reader, ChronoConfig.class);
				if (lue != null) {
					instance = lue;
				}
			} catch (IOException | RuntimeException e) {
				Chronomancie.LOGGER.error("Impossible de lire {}, réglages par défaut utilisés", FICHIER, e);
			}
		}
		// On réécrit le fichier pour y ajouter les nouveaux réglages éventuels
		sauvegarder();
	}

	private static void sauvegarder() {
		try {
			Files.createDirectories(FICHIER.getParent());
			try (Writer writer = Files.newBufferedWriter(FICHIER, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			Chronomancie.LOGGER.error("Impossible d'écrire {}", FICHIER, e);
		}
	}
}
