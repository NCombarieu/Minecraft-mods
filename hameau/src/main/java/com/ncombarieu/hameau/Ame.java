package com.ncombarieu.hameau;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Tout ce qui fait d'un villageois une personne : identité, humeur, relations, souvenirs. */
public final class Ame {
	static final int MAX_RECENTS = 18;
	static final int MAX_MARQUANTS = 12;
	static final int MAX_NOUVEAUX = 10;

	public String uuid;
	public String nom;
	public String metier = "sans métier";
	public List<String> traits = new ArrayList<>();
	public String desir;
	public String peur;
	public boolean femme;
	public boolean enfant;
	/** Une habitude qui le distingue, et sa manière de s'exprimer. */
	public String manie;
	public String parler;
	/** Hauteur de la voix (son du villageois quand il parle). */
	public float voix;
	/** Liens anciens avec d'autres habitants : jamais oubliés. */
	public List<String> liens = new ArrayList<>();
	public boolean lie;
	/** Ce qu'il compte faire dans les heures qui viennent ; il l'entretient lui-même. */
	public String projet;
	public String humeur = "calme";
	/** Construction en cours : ce qu'il reste à poser, bloc par bloc. */
	public Chantier chantier;
	transient boolean planEnCours;

	public static final class Chantier {
		public String nom;
		public int x;
		public int y;
		public int z;
		public int total;
		/** « x y z identifiant [orientation] », dans l'ordre de pose. */
		public List<String> restants = new ArrayList<>();
	}

	/** Opinion sur les autres, par nom, de -100 (haine) à 100 (affection). */
	public Map<String, Integer> relations = new TreeMap<>();
	public List<String> marquants = new ArrayList<>();
	public List<String> recents = new ArrayList<>();
	/** Ce qui s'est passé depuis la dernière réflexion. */
	public List<String> nouveaux = new ArrayList<>();
	public String dernierePensee = "";
	public int x;
	public int y;
	public int z;

	transient long prochainePensee;
	transient long dernierAppel = -100000;
	transient boolean enCours;
	private transient Deque<Long> repliques = new ArrayDeque<>();
	/** Dernière fois qu'il a remarqué l'arrivée de chaque joueur. */
	transient Map<String, Long> abords;

	public void noter(final String evenement) {
		if (!nouveaux.isEmpty() && nouveaux.getLast().equals(evenement)) {
			return;
		}
		nouveaux.add(evenement);
		while (nouveaux.size() > MAX_NOUVEAUX) {
			nouveaux.removeFirst();
		}
	}

	/** Les événements digérés par une réflexion deviennent des souvenirs récents. */
	void digerer(final int combien, final String prefixe) {
		for (int i = 0; i < combien && !nouveaux.isEmpty(); i++) {
			recents.add(prefixe + nouveaux.removeFirst());
		}
		while (recents.size() > MAX_RECENTS) {
			recents.removeFirst();
		}
	}

	void retenir(final String souvenir) {
		marquants.add(souvenir);
		while (marquants.size() > MAX_MARQUANTS) {
			marquants.removeFirst();
		}
	}

	public int relation(final String autre) {
		return relations.getOrDefault(autre, 0);
	}

	public void ajusterRelation(final String autre, final int delta) {
		if (autre == null || autre.isBlank() || autre.equalsIgnoreCase(nom) || delta == 0) {
			return;
		}
		relations.put(autre, Math.clamp(relation(autre) + delta, -100, 100));
	}

	/** Avance la prochaine réflexion, sans jamais descendre sous le délai minimal entre deux appels. */
	void presser(final long maintenant, final int secondes) {
		long auPlusTot = dernierAppel + HameauConfig.get().delaiMinEntrePensees * 20L;
		prochainePensee = Math.min(prochainePensee, Math.max(maintenant + secondes * 20L, auPlusTot));
	}

	/** Limite les échanges en chaîne entre villageois : trois répliques provoquées par deux minutes. */
	boolean peutRepliquer(final long maintenant) {
		if (repliques == null) {
			repliques = new ArrayDeque<>();
		}
		while (!repliques.isEmpty() && maintenant - repliques.peekFirst() > 2400) {
			repliques.removeFirst();
		}
		if (repliques.size() >= 3) {
			return false;
		}
		repliques.addLast(maintenant);
		return true;
	}
}
