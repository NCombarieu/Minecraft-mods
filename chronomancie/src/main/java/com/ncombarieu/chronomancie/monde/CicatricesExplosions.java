package com.ncombarieu.chronomancie.monde;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.ncombarieu.chronomancie.ChronoConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Le monde se souvient des explosions : chaque bloc soufflé est noté (position, état exact)
 * pour que le Chronoscope puisse le remettre en place.
 */
public final class CicatricesExplosions {
	/** Garde-fou mémoire : au-delà, on oublie les plus anciennes. */
	private static final int BLOCS_MAX = 200_000;

	public record BlocPerdu(BlockPos pos, BlockState etat) {
	}

	public static final class Cicatrice {
		public final long date;
		public final Vec3 centre;
		public final Component cause;
		public final List<BlocPerdu> blocs;

		private Cicatrice(final long date, final Vec3 centre, final Component cause, final List<BlocPerdu> blocs) {
			this.date = date;
			this.centre = centre;
			this.cause = cause;
			this.blocs = blocs;
		}
	}

	private static final Map<ResourceKey<Level>, Deque<Cicatrice>> MEMOIRE = new HashMap<>();
	private static int total;

	private CicatricesExplosions() {
	}

	/** Appelé par le mixin juste avant que l'explosion ne détruise les blocs. */
	public static void noter(final ServerLevel level, final Vec3 centre, final Component cause, final List<BlockPos> cibles) {
		List<BlocPerdu> blocs = new ArrayList<>();
		for (BlockPos pos : cibles) {
			BlockState etat = level.getBlockState(pos);
			// Pas les coffres & co (leur contenu est déjà tombé au sol), ni la TNT, ni les liquides
			if (etat.isAir() || etat.hasBlockEntity() || etat.is(Blocks.TNT) || etat.liquid() || etat.is(Blocks.FIRE)) {
				continue;
			}
			blocs.add(new BlocPerdu(pos.immutable(), etat));
		}
		if (blocs.isEmpty()) {
			return;
		}
		MEMOIRE.computeIfAbsent(level.dimension(), k -> new ArrayDeque<>()).addLast(new Cicatrice(level.getGameTime(), centre, cause, blocs));
		total += blocs.size();
		while (total > BLOCS_MAX) {
			oublierLaPlusAncienne();
		}
	}

	private static void oublierLaPlusAncienne() {
		Deque<Cicatrice> plusAncienne = null;
		for (Deque<Cicatrice> file : MEMOIRE.values()) {
			if (!file.isEmpty() && (plusAncienne == null || file.peekFirst().date < plusAncienne.peekFirst().date)) {
				plusAncienne = file;
			}
		}
		if (plusAncienne == null) {
			total = 0;
			return;
		}
		total -= plusAncienne.removeFirst().blocs.size();
	}

	/** Les cicatrices encore fraîches autour d'un point. Elles sont retirées de la mémoire. */
	public static List<Cicatrice> prendre(final ServerLevel level, final Vec3 pos, final double rayon, final boolean retirer) {
		Deque<Cicatrice> file = MEMOIRE.get(level.dimension());
		if (file == null) {
			return List.of();
		}
		long limite = level.getGameTime() - ChronoConfig.get().chronoscope.memoireMinutes * 60L * 20L;
		List<Cicatrice> trouvees = new ArrayList<>();
		Iterator<Cicatrice> it = file.iterator();
		while (it.hasNext()) {
			Cicatrice c = it.next();
			if (c.date < limite) {
				total -= c.blocs.size();
				it.remove();
			} else if (c.centre.distanceToSqr(pos) <= rayon * rayon) {
				trouvees.add(c);
				if (retirer) {
					total -= c.blocs.size();
					it.remove();
				}
			}
		}
		return trouvees;
	}

	public static void toutOublier() {
		MEMOIRE.clear();
		total = 0;
	}
}
