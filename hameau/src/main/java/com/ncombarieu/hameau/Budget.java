package com.ncombarieu.hameau;

import java.time.LocalDate;

/** Suivi de la dépense API, d'après les tokens réellement facturés. */
public final class Budget {
	public double totalUsd;
	public String jour = "";
	public double jourUsd;
	public long appels;
	public long tokensEntree;
	public long tokensSortie;

	/** Caractères dits à voix haute (ElevenLabs les facture au caractère). */
	public long voixCaracteres;
	public long voixJour;

	/** Secondes de parole des joueurs transcrites. */
	public long ecouteSecondes;
	public long ecouteJour;

	public boolean ecouteAutorisee() {
		changerDeJour();
		return ecouteJour < HameauConfig.get().voix.plafondSecondesEcouteParJour;
	}

	public void ecoutee(final long secondes) {
		changerDeJour();
		ecouteSecondes += secondes;
		ecouteJour += secondes;
	}

	public boolean voixAutorisee(final int caracteres) {
		changerDeJour();
		return voixJour + caracteres <= HameauConfig.get().voix.plafondCaracteresParJour;
	}

	public void voixDite(final int caracteres) {
		changerDeJour();
		voixCaracteres += caracteres;
		voixJour += caracteres;
	}

	private void changerDeJour() {
		String aujourdhui = LocalDate.now().toString();
		if (!aujourdhui.equals(jour)) {
			jour = aujourdhui;
			jourUsd = 0;
			voixJour = 0;
			ecouteJour = 0;
		}
	}

	public boolean autorise() {
		changerDeJour();
		HameauConfig c = HameauConfig.get();
		return jourUsd < c.plafondJournalierUsd && totalUsd < c.plafondTotalUsd;
	}

	public void enregistrer(final long entree, final long sortie) {
		HameauConfig c = HameauConfig.get();
		enregistrer(entree, sortie, c.prixEntreeParMillion, c.prixSortieParMillion);
	}

	public void enregistrer(final long entree, final long sortie, final double prixEntree, final double prixSortie) {
		changerDeJour();
		double cout = entree * prixEntree / 1e6 + sortie * prixSortie / 1e6;
		totalUsd += cout;
		jourUsd += cout;
		appels++;
		tokensEntree += entree;
		tokensSortie += sortie;
	}

	public String resume() {
		changerDeJour();
		HameauConfig c = HameauConfig.get();
		return String.format("%d appels, %.4f $ aujourd'hui (plafond %.2f), %.4f $ au total (plafond %.2f)",
				appels, jourUsd, c.plafondJournalierUsd, totalUsd, c.plafondTotalUsd);
	}
}
