package com.ncombarieu.hameau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** La lecture des réponses des modèles : elle doit tenir bon face au texte parasite et aux réponses coupées. */
class CerveauTest {
	@Test
	void decisionComplete() {
		Cerveau.Decision d = Cerveau.lire("""
				{"pensee":"Il fait beau","geste":"sourit","parole":"Bonjour !","a":"iC0mb","prive":false,"action":"Donner","cible":"iC0mb","objet":"pain",
				"suite":[{"action":"ALLER","cible":"place"}],"emotion":"joie","humeur":"gai","projet":"vendre du pain","relations":{"iC0mb":40,"Odile":-99},
				"souvenir":"iC0mb m'a salué","role":"boulanger","annonce":"Le four est ouvert","sert":"iC0mb"}""", 12, 34);
		assertEquals("Il fait beau", d.pensee());
		assertEquals("Bonjour !", d.parole());
		assertEquals("donner", d.action(), "l'action est ramenée en minuscules");
		assertEquals("pain", d.objet());
		assertEquals(1, d.suite().size());
		assertEquals("aller", d.suite().getFirst().action());
		assertEquals(15, d.relations().get("iC0mb"), "un changement d'opinion est borné à 15");
		assertEquals(-15, d.relations().get("Odile"));
		assertEquals("boulanger", d.role());
		assertEquals("Le four est ouvert", d.annonce());
		assertEquals("iC0mb", d.sert());
		assertEquals(12, d.tokensEntree());
		assertEquals(34, d.tokensSortie());
	}

	@Test
	void texteParasiteAutour() {
		Cerveau.Decision d = Cerveau.lire("Voici ma réponse :\n```json\n{\"parole\":\"Salut\",\"action\":\"rien\"}\n```\nJ'espère que {cela} convient.", 0, 0);
		assertEquals("Salut", d.parole());
		assertEquals("rien", d.action());
	}

	@Test
	void accoladesDansUneChaine() {
		Cerveau.Decision d = Cerveau.lire("{\"parole\":\"Regarde ce {truc} bizarre }\",\"action\":\"danser\"}", 0, 0);
		assertEquals("Regarde ce {truc} bizarre }", d.parole());
		assertEquals("danser", d.action());
	}

	@Test
	void reponseCoupee() {
		// La limite de longueur a tranché au milieu d'un champ : on garde ce qui est complet.
		Cerveau.Decision d = Cerveau.lire("{\"pensee\":\"Je vais au marché\",\"parole\":\"En route\",\"action\":\"aller\",\"cible\":\"pla", 0, 0);
		assertEquals("Je vais au marché", d.pensee());
		assertEquals("En route", d.parole());
		assertEquals("aller", d.action());
		assertNull(d.cible(), "le champ coupé est abandonné");
	}

	@Test
	void nullsEtChampsAbsents() {
		Cerveau.Decision d = Cerveau.lire("{\"parole\":null,\"a\":\"null\",\"cible\":\"\",\"prive\":true}", 0, 0);
		assertNull(d.parole());
		assertNull(d.a(), "la chaîne « null » vaut une absence");
		assertNull(d.cible(), "une chaîne vide vaut une absence");
		assertTrue(d.prive());
		assertEquals("rien", d.action(), "sans action, le villageois continue ce qu'il fait");
		assertTrue(d.suite().isEmpty());
		assertNull(d.role());
	}

	@Test
	void reponseIllisible() {
		Cerveau.Decision d = Cerveau.lire("Désolé, je ne peux pas répondre.", 5, 6);
		assertEquals("rien", d.action());
		assertNull(d.parole());
		assertFalse(d.prive());
		assertEquals(5, d.tokensEntree(), "les tokens sont comptés même si la réponse ne sert à rien");
	}

	@Test
	void suiteBornee() {
		StringBuilder suite = new StringBuilder();
		for (int i = 0; i < 12; i++) {
			suite.append(i == 0 ? "" : ",").append("{\"action\":\"aller\",\"cible\":\"").append(i).append(" 64 0\"}");
		}
		Cerveau.Decision d = Cerveau.lire("{\"action\":\"rien\",\"suite\":[" + suite + ",{\"cible\":\"sans action\"}]}", 0, 0);
		assertEquals(6, d.suite().size(), "un plan ne dépasse pas six étapes");
	}
}
