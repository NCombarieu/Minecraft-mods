package com.ncombarieu.hameau;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Les clés d'API, une par service, jamais dans hameau.json ni dans le chat : un fichier config/hameau-cles/&lt;nom&gt;.txt par profil.
 * « mistral-large » se rabat sur la clé « mistral ». Les deux fichiers d'origine (Anthropic, ElevenLabs) restent lus.
 */
public final class Cles {
	static final Path DOSSIER = FabricLoader.getInstance().getConfigDir().resolve("hameau-cles");

	private Cles() {
	}

	/** @return la clé de ce profil, ou null s'il n'y en a pas */
	static String lire(final String profil) {
		String nom = profil == null ? "" : profil.toLowerCase();
		switch (nom) {
			case "", "anthropic", "haiku", "sonnet", "opus" -> {
				String cle = variable("ANTHROPIC_API_KEY");
				return cle != null ? cle : premier(DOSSIER.resolve("anthropic.txt"), FabricLoader.getInstance().getConfigDir().resolve("hameau-cle.txt"));
			}
			case "elevenlabs" -> {
				String cle = variable("ELEVENLABS_API_KEY");
				return cle != null ? cle : premier(DOSSIER.resolve("elevenlabs.txt"), FabricLoader.getInstance().getConfigDir().resolve("hameau-voix-cle.txt"));
			}
			default -> {
				return premier(DOSSIER.resolve(nom + ".txt"), DOSSIER.resolve(nom.split("-", 2)[0] + ".txt"));
			}
		}
	}

	private static String variable(final String nom) {
		String valeur = System.getenv(nom);
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

	private static String premier(final Path... fichiers) {
		for (Path fichier : fichiers) {
			try {
				if (Files.exists(fichier)) {
					String cle = Files.readString(fichier, StandardCharsets.UTF_8).trim();
					if (!cle.isEmpty()) {
						return cle;
					}
				}
			} catch (IOException e) {
				Hameau.LOGGER.error("Impossible de lire {}", fichier, e);
			}
		}
		return null;
	}

	/** Un service qui tourne sur la machine même (Ollama…) n'a pas besoin de clé. */
	static boolean local(final String url) {
		return url != null && (url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1"));
	}
}
