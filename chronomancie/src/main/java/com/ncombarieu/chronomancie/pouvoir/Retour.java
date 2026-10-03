package com.ncombarieu.chronomancie.pouvoir;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.ncombarieu.chronomancie.ChronoConfig;
import com.ncombarieu.chronomancie.fx.Effets;
import com.ncombarieu.chronomancie.progression.Succes;
import com.ncombarieu.chronomancie.temps.Instantane;
import com.ncombarieu.chronomancie.temps.Memoire;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Sablier du Retour : le joueur remonte visiblement son propre trajet, à l'envers et en accéléré,
 * puis retrouve la santé qu'il avait à ce moment-là.
 */
public final class Retour {
	private static final Map<UUID, Session> SESSIONS = new HashMap<>();
	private static final DustColorTransitionOptions POUSSIERE = new DustColorTransitionOptions(Effets.OR, Effets.VIOLET, 1.2F);

	private Retour() {
	}

	private static final class Session {
		private final ServerPlayer joueur;
		/** Du plus récent au plus ancien. */
		private final List<Instantane> chemin;
		private final int duree;
		private int tick;
		private Vec3 dernierePosition;

		private Session(final ServerPlayer joueur, final List<Instantane> chemin, final int duree) {
			this.joueur = joueur;
			this.chemin = chemin;
			this.duree = duree;
			this.dernierePosition = joueur.position();
		}
	}

	public static boolean enCours(final ServerPlayer joueur) {
		return SESSIONS.containsKey(joueur.getUUID());
	}

	public static boolean demarrer(final ServerPlayer joueur) {
		if (enCours(joueur)) {
			return false;
		}
		List<Instantane> passe = Memoire.derniers(joueur, ChronoConfig.get().sablier.secondesRemontees * 20);
		if (passe.size() < 20) {
			joueur.sendOverlayMessage(Component.literal("Ton fil du temps est encore trop court…").withStyle(ChatFormatting.GRAY));
			return false;
		}
		List<Instantane> chemin = passe.reversed();
		int duree = Mth.clamp(chemin.size() / 5, 16, 36);
		SESSIONS.put(joueur.getUUID(), new Session(joueur, chemin, duree));
		Memoire.pause(joueur, true);

		ServerLevel level = joueur.level();
		Effets.son(level, joueur.position(), SoundEvents.BEACON_DEACTIVATE, 1.0F, 1.6F);
		Effets.son(level, joueur.position(), SoundEvents.ENDERMAN_TELEPORT, 0.6F, 0.5F);
		// Silhouette laissée sur place, comme une image rémanente
		for (int i = 0; i < 12; i++) {
			Effets.particules(level, POUSSIERE, joueur.position().add(0, i * 0.16, 0), 3, 0.15, 0);
		}
		Succes.RETOUR.accorder(joueur);
		return true;
	}

	public static void tick() {
		Iterator<Session> it = SESSIONS.values().iterator();
		while (it.hasNext()) {
			Session s = it.next();
			ServerPlayer joueur = s.joueur;
			if (joueur.isRemoved() || !joueur.isAlive()) {
				Memoire.pause(joueur, false);
				it.remove();
				continue;
			}
			s.tick++;
			float progression = Math.min(1.0F, s.tick / (float) s.duree);
			// Accélération douce : lent au départ, vif au milieu, lent à l'arrivée
			float lisse = progression * progression * (3 - 2 * progression);
			int index = Math.round(lisse * (s.chemin.size() - 1));
			Instantane cible = s.chemin.get(index);
			ServerLevel level = joueur.level();

			if (cible.dimension() == level.dimension() && peutSeTenir(joueur, cible.position())) {
				joueur.teleportTo(level, cible.position().x, cible.position().y, cible.position().z, Set.of(), cible.yRot(), cible.xRot(), false);
				joueur.resetFallDistance();
				Vec3 avant = s.dernierePosition;
				Vec3 apres = cible.position();
				// Traînées qui filent vers la nouvelle position
				for (int i = 0; i < 4; i++) {
					Vec3 depart = avant.add((level.getRandom().nextDouble() - 0.5) * 0.8, level.getRandom().nextDouble() * 1.8, (level.getRandom().nextDouble() - 0.5) * 0.8);
					Vec3 arrivee = apres.add(0, 0.9, 0);
					level.sendParticles(new TrailParticleOption(arrivee, i % 2 == 0 ? Effets.OR : Effets.VIOLET, 12), depart.x, depart.y, depart.z, 1, 0, 0, 0, 0);
				}
				Effets.particules(level, ParticleTypes.REVERSE_PORTAL, apres.add(0, 1, 0), 6, 0.3, 0.02);
				s.dernierePosition = apres;
			}
			if (s.tick % 3 == 0) {
				Effets.son(level, joueur.position(), SoundEvents.NOTE_BLOCK_HAT, 0.5F, 0.6F + 1.4F * progression);
			}

			if (s.tick >= s.duree) {
				terminer(joueur, s.chemin.getLast());
				it.remove();
			}
		}
	}

	private static boolean peutSeTenir(final ServerPlayer joueur, final Vec3 pos) {
		return joueur.level().noCollision(joueur, joueur.getBoundingBox().move(pos.subtract(joueur.position())));
	}

	private static void terminer(final ServerPlayer joueur, final Instantane origine) {
		ServerLevel level = joueur.level();
		if (origine.sante() > joueur.getHealth()) {
			joueur.setHealth(Math.min(joueur.getMaxHealth(), origine.sante()));
		}
		if (!origine.enFeu()) {
			joueur.clearFire();
		}
		joueur.resetFallDistance();
		joueur.setDeltaMovement(Vec3.ZERO);
		joueur.connection.send(new ClientboundSetEntityMotionPacket(joueur));
		Memoire.pause(joueur, false);
		Memoire.effacer(joueur);

		Vec3 pos = joueur.position().add(0, 1, 0);
		Effets.son(level, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 1.2F);
		Effets.son(level, pos, SoundEvents.BELL_RESONATE, 0.5F, 2.0F);
		Effets.particules(level, ColorParticleOption.create(ParticleTypes.FLASH, 0xFF000000 | Effets.OR), pos, 1, 0, 0);
		Effets.particules(level, ParticleTypes.END_ROD, pos, 30, 0.1, 0.15);
		Effets.anneau(level, POUSSIERE, joueur.position().add(0, 0.1, 0), 1.2, 24, 0);
	}

	public static void toutArreter() {
		for (Session s : SESSIONS.values()) {
			Memoire.pause(s.joueur, false);
		}
		SESSIONS.clear();
	}
}
