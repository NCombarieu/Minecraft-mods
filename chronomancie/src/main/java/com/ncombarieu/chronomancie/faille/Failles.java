package com.ncombarieu.chronomancie.faille;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.ncombarieu.chronomancie.ChronoConfig;
import com.ncombarieu.chronomancie.fx.Effets;
import com.ncombarieu.chronomancie.item.Artefacts;
import com.ncombarieu.chronomancie.item.Traite;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Fait naître les failles près des joueurs et les fait vivre. */
public final class Failles {
	private static final List<Faille> ACTIVES = new ArrayList<>();
	private static final String TAG_ACCUEILLI = "chronomancie.accueilli";

	private Failles() {
	}

	public static List<Faille> actives() {
		return List.copyOf(ACTIVES);
	}

	public static void tick(final MinecraftServer server) {
		Iterator<Faille> it = ACTIVES.iterator();
		while (it.hasNext()) {
			Faille faille = it.next();
			faille.tick();
			if (faille.terminee()) {
				it.remove();
			}
		}

		ChronoConfig.Failles config = ChronoConfig.get().failles;
		if (config.apparitionNaturelle && server.getTickCount() % (Math.max(1, config.intervalleVerification) * 20) == 0) {
			apparitionsNaturelles(server, config);
		}
	}

	private static void apparitionsNaturelles(final MinecraftServer server, final ChronoConfig.Failles config) {
		ServerLevel monde = server.overworld();
		RandomSource random = monde.getRandom();
		double chance = monde.isDarkOutside() ? config.chanceNuit : config.chanceJour;
		for (ServerPlayer joueur : monde.players()) {
			if (ACTIVES.size() >= config.maxSimultanees) {
				return;
			}
			if (joueur.isSpectator() || random.nextDouble() >= chance || failleProche(monde, joueur.position(), 128)) {
				continue;
			}
			// Il faut être à l'air libre (ou presque) pour voir la faille s'ouvrir
			if (!monde.canSeeSky(joueur.blockPosition().above(2)) && joueur.getY() < monde.getSeaLevel() - 8) {
				continue;
			}
			Vec3 lieu = trouverLieu(monde, joueur.position(), config.distanceMin, config.distanceMax, random);
			if (lieu != null) {
				Faille faille = ouvrir(monde, lieu, Epoque.values()[random.nextInt(Epoque.values().length)]);
				annoncer(faille, joueur);
			}
		}
	}

	public static Faille ouvrir(final ServerLevel level, final Vec3 lieu, final Epoque epoque) {
		Faille faille = new Faille(level, lieu, epoque);
		faille.ouvrir();
		ACTIVES.add(faille);
		return faille;
	}

	private static boolean failleProche(final Level level, final Vec3 pos, final double distance) {
		for (Faille f : ACTIVES) {
			if (f.level() == level && f.centre().distanceToSqr(pos) < distance * distance) {
				return true;
			}
		}
		return false;
	}

	/** Un point de la surface, sur un sol ferme et dans une zone chargée. */
	public static Vec3 trouverLieu(final ServerLevel level, final Vec3 autour, final int min, final int max, final RandomSource random) {
		for (int essai = 0; essai < 12; essai++) {
			double angle = random.nextDouble() * Mth.TWO_PI;
			double distance = min + random.nextDouble() * Math.max(0, max - min);
			int x = Mth.floor(autour.x + Math.cos(angle) * distance);
			int z = Mth.floor(autour.z + Math.sin(angle) * distance);
			BlockPos colonne = new BlockPos(x, 0, z);
			if (!level.isLoaded(colonne)) {
				continue;
			}
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos sol = new BlockPos(x, y - 1, z);
			if (y <= level.getMinY() || !level.getFluidState(sol).isEmpty() || !level.getBlockState(sol).isSolid()) {
				continue;
			}
			return new Vec3(x + 0.5, y + 2.2, z + 0.5);
		}
		return null;
	}

	private static void annoncer(final Faille faille, final ServerPlayer temoin) {
		for (ServerPlayer joueur : faille.level().players()) {
			double distance = joueur.position().distanceTo(faille.centre());
			if (distance > 160) {
				continue;
			}
			Vec3 delta = faille.centre().subtract(joueur.position());
			joueur.sendSystemMessage(Component.literal("⌛ Le voile du temps se déchire… ").withStyle(ChatFormatting.DARK_PURPLE)
					.append(Component.literal("une faille s'est ouverte " + direction(delta) + ", à " + Math.round(distance) + " blocs.")
							.withStyle(ChatFormatting.LIGHT_PURPLE)));
			Effets.son(faille.level(), joueur.position(), SoundEvents.TRIAL_SPAWNER_OMINOUS_ACTIVATE, 0.7F, 0.5F);
		}
	}

	private static String direction(final Vec3 delta) {
		String[] noms = { "au sud", "au sud-ouest", "à l'ouest", "au nord-ouest", "au nord", "au nord-est", "à l'est", "au sud-est" };
		// En jeu : +z = sud, -x = ouest
		double angle = Math.toDegrees(Math.atan2(-delta.x, delta.z));
		int index = Math.floorMod(Math.round((float) (angle / 45.0)), 8);
		return noms[index];
	}

	/** Premier passage d'un joueur sur le serveur : on lui offre le traité. */
	public static void accueillir(final ServerPlayer joueur) {
		if (joueur.entityTags().contains(TAG_ACCUEILLI)) {
			return;
		}
		joueur.addTag(TAG_ACCUEILLI);
		Artefacts.offrir(joueur, Traite.creer());
		joueur.sendSystemMessage(Component.literal("⌛ Un étrange traité s'est glissé dans ton sac… Le temps, ici, n'est pas tout à fait fiable.")
				.withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC));
	}

	public static void toutFermer() {
		ACTIVES.forEach(Faille::fermer);
		ACTIVES.clear();
	}
}
