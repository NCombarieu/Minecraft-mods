package com.ncombarieu.chronomancie.temps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * La mémoire du temps : chaque tick, on note où se trouve chaque joueur.
 * C'est ce fil que le Sablier remonte et que le Miroir fait rejouer.
 */
public final class Memoire {
	/** On garde 15 secondes : assez pour toutes les configurations raisonnables. */
	public static final int TICKS_MAX = 20 * 15;
	/** Au-delà de ce saut entre deux ticks (téléportation, perle…) on coupe le fil. */
	private static final double SAUT_MAX = 12.0;

	private static final Map<UUID, Fil> FILS = new HashMap<>();

	private Memoire() {
	}

	private static final class Fil {
		private final Deque<Instantane> instants = new ArrayDeque<>();
		private Object dernierCoup;
		private boolean enPause;
	}

	public static void enregistrer(final MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Fil fil = FILS.computeIfAbsent(player.getUUID(), uuid -> new Fil());
			if (fil.enPause) {
				continue;
			}
			if (!player.isAlive() || player.isSpectator()) {
				fil.instants.clear();
				continue;
			}
			Instantane precedent = fil.instants.peekLast();
			if (precedent != null && (precedent.dimension() != player.level().dimension()
					|| precedent.position().distanceToSqr(player.position()) > SAUT_MAX * SAUT_MAX)) {
				fil.instants.clear();
				precedent = null;
			}

			LivingEntity.SwingDescription coupActuel = player.getCurrentSwing();
			boolean coup = coupActuel != null && coupActuel != fil.dernierCoup;
			fil.dernierCoup = coupActuel;

			ItemStack main = player.getMainHandItem();
			ItemStack mainCopie = precedent != null && ItemStack.isSameItemSameComponents(precedent.mainDroite(), main)
					? precedent.mainDroite()
					: main.copy();

			fil.instants.addLast(new Instantane(player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
					player.getHealth(), player.isOnFire(), player.getPose(), mainCopie, coup));
			while (fil.instants.size() > TICKS_MAX) {
				fil.instants.removeFirst();
			}
		}
	}

	/** Les N derniers ticks, du plus ancien au plus récent. */
	public static List<Instantane> derniers(final ServerPlayer player, final int ticks) {
		Fil fil = FILS.get(player.getUUID());
		if (fil == null) {
			return List.of();
		}
		List<Instantane> resultat = new ArrayList<>(Math.min(ticks, fil.instants.size()));
		Iterator<Instantane> it = fil.instants.descendingIterator();
		while (it.hasNext() && resultat.size() < ticks) {
			resultat.add(it.next());
		}
		return resultat.reversed();
	}

	/** Pendant un retour dans le temps, on n'enregistre pas le trajet inverse. */
	public static void pause(final ServerPlayer player, final boolean pause) {
		FILS.computeIfAbsent(player.getUUID(), uuid -> new Fil()).enPause = pause;
	}

	public static void effacer(final ServerPlayer player) {
		Fil fil = FILS.get(player.getUUID());
		if (fil != null) {
			fil.instants.clear();
		}
	}

	public static void oublier(final UUID joueur) {
		FILS.remove(joueur);
	}

	public static void toutOublier() {
		FILS.clear();
	}
}
