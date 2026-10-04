package com.ncombarieu.hameau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Réglages, profils de modèles, suivi de la dépense, et les petites fonctions de lecture de texte. */
class ReglagesTest {
	@BeforeAll
	static void charger() {
		HameauConfig.charger();
	}

	@Test
	void profilsFournis() {
		HameauConfig config = HameauConfig.get();
		for (String profil : new String[] {"haiku", "sonnet", "opus", "mistral", "qwen", "openai", "openai-luna"}) {
			assertNotNull(config.modeles.get(profil), "profil manquant : " + profil);
		}
		assertNotNull(config.voix.moteurs.get("elevenlabs"));
		assertEquals("openai", config.voix.moteurs.get("groq").type);
	}

	@Test
	void choisirUnModele() {
		HameauConfig config = HameauConfig.get();
		assertTrue(config.choisirModele("OpenAI-Luna"), "le nom d'un profil s'écrit avec ou sans majuscules");
		assertEquals("gpt-6-luna", config.modele);
		assertEquals("https://api.openai.com/v1", config.url);
		assertEquals(0.10, config.prixEntreeParMillion);
		assertTrue(config.choisirModelePlans("opus"));
		assertEquals("claude-opus-5-5", config.batir.modele);
		assertEquals("", config.batir.url, "Anthropic n'a pas d'adresse");
		assertFalse(config.choisirModele("inconnu"));
		assertFalse(config.choisirModele("openrouter"), "un profil sans identifiant de modèle ne se choisit pas");
		assertEquals("gpt-6-luna", config.modele, "un choix refusé ne change rien");
		assertTrue(config.choisirModele("haiku"));
	}

	@Test
	void clesLocales() {
		assertTrue(Cles.local("http://localhost:11434/v1"));
		assertTrue(Cles.local("http://127.0.0.1:8080/v1"));
		assertFalse(Cles.local("https://api.openai.com/v1"));
		assertFalse(Cles.local(null));
	}

	@Test
	void depenseEtPlafonds() {
		HameauConfig config = HameauConfig.get();
		config.plafondJournalierUsd = 1.0;
		config.plafondTotalUsd = 10.0;
		Budget budget = new Budget();
		assertTrue(budget.autorise());
		budget.enregistrer(1_000_000, 100_000, 2.0, 5.0);
		assertEquals(2.5, budget.totalUsd, 1e-9);
		assertEquals(1, budget.appels);
		assertFalse(budget.autorise(), "le plafond du jour est dépassé");
		config.plafondJournalierUsd = 5.0;
		assertTrue(budget.autorise());
		budget.depenser(0.5);
		assertEquals(3.0, budget.jourUsd, 1e-9);
		assertEquals(1, budget.appels, "une dépense de voix n'est pas un appel");
	}

	@Test
	void plafondsDeLaVoix() {
		HameauConfig.Voix voix = HameauConfig.get().voix;
		voix.plafondCaracteresParJour = 100;
		voix.plafondSecondesEcouteParJour = 10;
		Budget budget = new Budget();
		assertTrue(budget.voixAutorisee(100));
		budget.voixDite(60);
		assertFalse(budget.voixAutorisee(41));
		assertTrue(budget.voixAutorisee(40));
		assertTrue(budget.ecouteAutorisee());
		budget.ecoutee(10);
		assertFalse(budget.ecouteAutorisee());
	}

	@Test
	void simplifierUnNom() {
		assertEquals("maelle", Ames.simplifier(" Maëlle "));
		assertEquals("eglantine", Ames.simplifier("ÉGLANTINE"));
		assertEquals("francois", Ames.simplifier("François"));
	}

	@Test
	void prenomsAcceptables() {
		assertTrue(Ames.prenomLibre("Ilvaïa", null));
		assertTrue(Ames.prenomLibre("Marie-Lou", null));
		assertFalse(Ames.prenomLibre("Jean Paul", null), "les commandes lisent le prénom comme un seul mot");
		assertFalse(Ames.prenomLibre("A", null));
		assertFalse(Ames.prenomLibre("R2D2", null));
		assertFalse(Ames.prenomLibre("Unprenomvraimentbeaucouptroplong", null));
		assertFalse(Ames.prenomLibre(null, null));
	}

	@Test
	void coordonneesDansDuTexte() {
		assertEquals(new BlockPos(-541, 71, 66), Actions.coordonnees("Chest en -541 71 66"));
		assertEquals(new BlockPos(3, 64, 9), Actions.coordonnees("3, 64, 9"));
		assertNull(Actions.coordonnees("la place"));
		assertNull(Actions.coordonnees(null));
	}

	@Test
	void synonymesDesActions() {
		assertEquals("frapper", Actions.normaliser("chasser"));
		assertEquals("batir", Actions.normaliser("construire"));
		assertEquals("casser", Actions.normaliser("creuser"));
		assertEquals("miner", Actions.normaliser("miner"));
		assertEquals("rien", Actions.normaliser("dormir"));
		assertEquals("rien", Actions.normaliser(null));
		assertEquals("planer", Actions.normaliser("planer"), "une action inconnue passe telle quelle, et sera refusée plus loin");
	}
}
